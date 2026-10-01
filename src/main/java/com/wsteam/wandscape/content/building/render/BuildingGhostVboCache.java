package com.wsteam.wandscape.content.building.render;

import com.mojang.blaze3d.vertex.*;
import com.wsteam.wandscape.content.building.data.BlockOffset;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.projection.BuildingRotation;
import com.wsteam.wandscape.content.building.preview.BuildingPreviewRenderer;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-baked GPU vertex buffers for building ghosts with zero-copy VBO rendering.
 *
 * <p>几何按 16³ 段切分，每段一个独立的 {@link VertexBuffer} 与索引。这么切有两个好处：
 *
 * <ul>
 *   <li><b>视锥剔除</b>：绘制时逐段判可见性，看不见的段连索引都不用重建、draw 也不发。
 *       那栋超大建筑 803 个非空段，站在外面看时能剔掉相当一部分。</li>
 *   <li><b>构建期峰值只受最大一段约束</b>：逐段构建、建完立刻关掉该段的 native 顶点缓冲，
 *       不再需要按整栋楼预留几百 MB（实测中位 468 格/段）。</li>
 * </ul>
 */
public final class BuildingGhostVboCache {

    private static final float GHOST_ALPHA = 0.55f;
    private static final int FULL_BRIGHT = 0xF000F0;

    /** 段尺寸（边长）与位移。 */
    private static final int SECTION_SHIFT = 4;
    private static final int SECTION_SIZE = 1 << SECTION_SHIFT;

    /**
     * 遮罩索引重建的最小间隔（tick）；anchor 变化时不等，立即重建。
     * 1 = 每 tick 至多一次，即把原来的「每渲染帧一次」降到「每 tick 一次」。
     */
    private static final long MASK_REBUILD_INTERVAL_TICKS = 1L;

    private static final Map<BuildingConfig, BakedGhostMesh[]> CACHE = new HashMap<>();
    private static final ByteBufferBuilder INDEX_BBB = new ByteBufferBuilder(4 * 1024 * 1024);

    private BuildingGhostVboCache() {}

    /** Draw the full ghost building using event camera ModelView matrix (120 FPS). */
    public static void drawGhost(Minecraft mc, Matrix4f cameraModelView, Matrix4f projection,
                                 Vec3 camPos, BlockPos anchor, BuildingConfig config, int rotationSteps,
                                 Frustum frustum) {
        BakedGhostMesh mesh = getOrBake(mc, config, rotationSteps, anchor);
        if (mesh == null) return;

        RenderType rt = RenderType.translucent();
        rt.setupRenderState();
        for (SectionMesh section : mesh.sections) {
            if (!isSectionVisible(section, anchor, frustum)) continue;
            if (section.indexClobbered) {
                restoreFullIndex(section);
                section.indexClobbered = false;
            }
            drawSection(section, cameraModelView, projection, camPos, anchor);
        }
        rt.clearRenderState();
    }

    /** Draw ghost skipping placed blocks (under-construction footprint). */
    public static void drawGhostSkipped(Minecraft mc, Matrix4f cameraModelView, Matrix4f projection,
                                        Vec3 camPos, BlockPos anchor, BuildingConfig config, int rotationSteps,
                                        Frustum frustum) {
        BakedGhostMesh mesh = getOrBake(mc, config, rotationSteps, anchor);
        if (mesh == null) return;

        RenderType rt = RenderType.translucent();
        rt.setupRenderState();
        for (SectionMesh section : mesh.sections) {
            if (!isSectionVisible(section, anchor, frustum)) continue;
            if (needsMaskRebuild(mc, section, anchor)) {
                rebuildMaskedIndex(mc, section, anchor);
            }
            drawSection(section, cameraModelView, projection, camPos, anchor);
        }
        rt.clearRenderState();
    }

    private static boolean isSectionVisible(SectionMesh section, BlockPos anchor, Frustum frustum) {
        if (frustum == null) return true;
        double x = anchor.getX() + section.originX;
        double y = anchor.getY() + section.originY;
        double z = anchor.getZ() + section.originZ;
        return frustum.isVisible(new AABB(x, y, z, x + SECTION_SIZE, y + SECTION_SIZE, z + SECTION_SIZE));
    }

    /**
     * Whether this section's masked index must be rewritten this frame.
     *
     * <p>重写一次要把该段所有格子的索引写一遍再 {@code glBufferData} 上 GPU。分段之后
     * 一次只有一段（中位 468 格，约几十 KB），而原来是整栋楼几十 MB —— 这是分段最大的收益。
     * 遮罩只在「有格子刚建好」时才变，所以这里做节流：anchor 一动就立刻重建（不重建会拿错
     * 位置的遮罩），否则每 {@link #MASK_REBUILD_INTERVAL_TICKS} tick 至多一次。
     */
    private static boolean needsMaskRebuild(Minecraft mc, SectionMesh section, BlockPos anchor) {
        // 当前上传的是完整索引（drawGhost 恢复过）——必须是遮罩版才算数。
        if (!section.indexClobbered) return true;
        if (!anchor.equals(section.maskAnchor)) return true;
        if (section.maskTick == Long.MIN_VALUE) return true;
        return currentTick(mc) - section.maskTick >= MASK_REBUILD_INTERVAL_TICKS;
    }

    private static long currentTick(Minecraft mc) {
        return mc.level != null ? mc.level.getGameTime() : 0L;
    }

    public static void closeAll() {
        synchronized (CACHE) {
            for (BakedGhostMesh[] buckets : CACHE.values()) {
                if (buckets == null) continue;
                for (BakedGhostMesh mesh : buckets) {
                    if (mesh == null) continue;
                    for (SectionMesh section : mesh.sections) {
                        section.vbo.close();
                    }
                }
            }
            CACHE.clear();
        }
    }

    private static BakedGhostMesh getOrBake(Minecraft mc, BuildingConfig config, int rotationSteps, BlockPos anchor) {
        if (config.pattern().isEmpty()) return null;
        int steps = rotationSteps & 3;
        BakedGhostMesh[] buckets = bucketsFor(config);
        BakedGhostMesh mesh = buckets[steps];
        if (mesh == null) {
            mesh = bake(mc, config, steps, anchor);
            synchronized (CACHE) {
                if (buckets[steps] == null) {
                    buckets[steps] = mesh;
                } else {
                    mesh = buckets[steps];
                }
            }
        }
        return mesh;
    }

    private static BakedGhostMesh[] bucketsFor(BuildingConfig config) {
        synchronized (CACHE) {
            BakedGhostMesh[] buckets = CACHE.get(config);
            if (buckets == null) {
                buckets = new BakedGhostMesh[4];
                CACHE.put(config, buckets);
            }
            return buckets;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── 构建 ──
    // ═══════════════════════════════════════════════════════════════

    private static BakedGhostMesh bake(Minecraft mc, BuildingConfig config, int steps, BlockPos anchor) {
        List<BlockOffset> pattern = config.pattern();
        int n = pattern.size();

        BlockOffset[] rotatedOffsets = new BlockOffset[n];
        Block[] cellBlocks = new Block[n];
        BlockState[] cellStates = new BlockState[n];

        for (int i = 0; i < n; i++) {
            rotatedOffsets[i] = BuildingRotation.rotateOffset(pattern.get(i), steps);
            // 用旋转后的 BlockState：旋转仅改变偏移与方块朝向（建筑旋转后每格仍占据
            // 轴对齐单位立方体 [pos,pos+1]），几何体本身不绕原点转。
            BlockState state = BuildingPreviewRenderer.resolveBlockState(
                    BuildingRotation.rotateBlockStateString(config.blockIdAt(i), steps));
            cellStates[i] = state;
            cellBlocks[i] = state != null ? state.getBlock() : null;
        }

        // 逐面剔除用的方块视图（B6）。**整栋一份**——跨段边界的邻居必须查得到，
        // 所以它不能按段切。建不出来（包围盒过大等）就是 null，退回不做剔除的老路径。
        BuildingGhostBlockView view = BuildingGhostBlockView.create(
                rotatedOffsets, cellStates, mc.level, anchor);
        RandomSource random = RandomSource.create(42L);

        int[][] sectionCells = groupIntoSections(rotatedOffsets);
        List<SectionMesh> sections = new ArrayList<>(sectionCells.length);
        for (int[] cells : sectionCells) {
            SectionMesh section = buildSection(mc, cells, rotatedOffsets, cellStates, cellBlocks, view, random);
            if (section != null) {
                sections.add(section);
            }
        }
        if (sections.isEmpty()) return null;
        return new BakedGhostMesh(sections.toArray(new SectionMesh[0]));
    }

    /** 把 pattern 下标按 16³ 段分组，返回「段 → 该段的 pattern 下标」。 */
    private static int[][] groupIntoSections(BlockOffset[] rotatedOffsets) {
        int n = rotatedOffsets.length;
        long[] keys = new long[n];
        Long2IntMap indexOf = new Long2IntOpenHashMap();
        indexOf.defaultReturnValue(-1);
        for (int i = 0; i < n; i++) {
            long key = sectionKey(rotatedOffsets[i]);
            keys[i] = key;
            if (!indexOf.containsKey(key)) {
                indexOf.put(key, indexOf.size());
            }
        }

        int sectionCount = indexOf.size();
        int[] counts = new int[sectionCount];
        for (int i = 0; i < n; i++) {
            counts[indexOf.get(keys[i])]++;
        }

        int[][] cells = new int[sectionCount][];
        for (int s = 0; s < sectionCount; s++) {
            cells[s] = new int[counts[s]];
        }
        int[] cursor = new int[sectionCount];
        for (int i = 0; i < n; i++) {
            int s = indexOf.get(keys[i]);
            cells[s][cursor[s]++] = i;
        }
        return cells;
    }

    private static long sectionKey(BlockOffset o) {
        return (((long) (o.x() >> SECTION_SHIFT) & 0x1FFFFF) << 32)
                | (((long) (o.y() >> SECTION_SHIFT) & 0x7FF) << 21)
                | ((o.z() >> SECTION_SHIFT) & 0x1FFFFF);
    }

    /**
     * 构建一个段。顶点缓冲在这里按**本段**的非空气格数预留，建完立刻关闭 ——
     * {@link VertexBuffer#upload} 是同步的 {@code glBufferData}，拷进 GPU 后它就没用了；
     * 而 {@link ByteBufferBuilder} 没有 Cleaner 也没有 finalizer，只有 {@code close()}
     * 会 {@code ALLOCATOR.free}，漏掉就是每次烘焙永久泄漏整个 capacity。
     */
    private static SectionMesh buildSection(Minecraft mc, int[] cells,
                                            BlockOffset[] rotatedOffsets, BlockState[] cellStates,
                                            Block[] cellBlocks, BuildingGhostBlockView view,
                                            RandomSource random) {
        int m = cells.length;
        BlockOffset first = rotatedOffsets[cells[0]];
        int originX = first.x() >> SECTION_SHIFT << SECTION_SHIFT;
        int originY = first.y() >> SECTION_SHIFT << SECTION_SHIFT;
        int originZ = first.z() >> SECTION_SHIFT << SECTION_SHIFT;

        ByteBufferBuilder vertBbb = new ByteBufferBuilder(sectionVertexCapacity(cells, cellStates));
        try {
            BufferBuilder bb = new BufferBuilder(vertBbb, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            int[] vertexCount = new int[1];
            MultiBufferSource ghostSource = rt -> new AlphaCountingConsumer(bb, vertexCount);
            // 剔除路径直接要 VertexConsumer（不是 MultiBufferSource）——AlphaCountingConsumer
            // 本来就是直接包 BufferBuilder 的，复用一个实例，别每格新建。
            VertexConsumer cellConsumer = view != null ? new AlphaCountingConsumer(bb, vertexCount) : null;

            int[] quadStart = new int[m];
            int[] quadCount = new int[m];
            BlockOffset[] offsets = new BlockOffset[m];
            Block[] blocks = new Block[m];
            int totalQuads = 0;

            PoseStack pose = new PoseStack();

            // 不再对几何体施加全局旋转：旋转几何体会把每格体积相对构造偏移最多 1 格
            // （90°/270° 偏 1 格、180° 两方向各偏 1 格）。改为每格平移到旋转后的偏移
            // （rotatedOffsets）并用旋转后的 BlockState 渲染，与服务端构造逐格一致。
            for (int c = 0; c < m; c++) {
                int i = cells[c];
                BlockState state = cellStates[i];
                BlockOffset rotated = rotatedOffsets[i];
                offsets[c] = rotated;
                blocks[c] = cellBlocks[i];

                // Skip animated blocks (chests, shulker boxes, signs, banners, ...). They
                // have no static block model — renderSingleBlock would tessellate them
                // through the item/BESR path into this BLOCK-format buffer, corrupting
                // vertex layout and sampling the wrong atlas (chest atlas vs block atlas).
                // They are drawn by a separate per-frame pass (BuildingGhostRenderer.renderGhostAnimated).
                if (state == null || state.getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED) {
                    quadStart[c] = totalQuads;
                    quadCount[c] = 0;
                    continue;
                }

                int before = vertexCount[0];
                pose.pushPose();
                pose.translate(rotated.x(), rotated.y(), rotated.z());

                if (view != null) {
                    // checkSides = true：原版会拿 view 逐个方向查邻居，被不透明邻居挡住的面直接
                    // 不产出——这也顺带让「六面都被包住」的方块产出 0 个四边形。用不带 AO 的
                    // 那版是因为原路径 renderSingleBlock 本就是平光；挂上 AO 会凭空多出转角明暗。
                    BlockPos cellPos = new BlockPos(rotated.x(), rotated.y(), rotated.z());
                    mc.getBlockRenderer().getModelRenderer().tesselateWithoutAO(
                            view, mc.getBlockRenderer().getBlockModel(state), state, cellPos,
                            pose, cellConsumer, true, random, state.getSeed(cellPos),
                            OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.translucent());
                } else {
                    mc.getBlockRenderer().renderSingleBlock(
                            state, pose, ghostSource, FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                            ModelData.EMPTY, RenderType.translucent());
                }

                pose.popPose();

                quadStart[c] = totalQuads;
                quadCount[c] = (vertexCount[0] - before) / 4;
                totalQuads += quadCount[c];
            }

            if (totalQuads == 0) return null;

            MeshData mesh = bb.buildOrThrow();
            VertexFormat.IndexType indexType = mesh.drawState().indexType();
            VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
            vbo.bind();
            vbo.upload(mesh);
            VertexBuffer.unbind();

            ByteBuffer fullIndex = ByteBuffer.allocateDirect(totalQuads * 6 * indexType.bytes);
            long fp = MemoryUtil.memAddress(fullIndex);
            for (int q = 0; q < totalQuads; q++) {
                int base = q * 4;
                writeQuadIndex(fp, indexType, base, base + 1, base + 2, base + 2, base + 3, base);
                fp += 6L * indexType.bytes;
            }

            return new SectionMesh(originX, originY, originZ, vbo, indexType,
                    quadStart, quadCount, offsets, blocks, fullIndex);
        } finally {
            vertBbb.close();
        }
    }

    /**
     * 本段顶点缓冲容量上界：段内非空气格数 × 6 面 × 4 顶点 × 32 字节（{@code BLOCK} 格式）。
     *
     * <p>不把空气算进去——空气占 pattern 相当比例，而空气质量为 0
     * （{@code AirBlock#getRenderShape} 返回 {@code INVISIBLE}），算进去是平白多占。
     * 真超过这个上界时 {@code ByteBufferBuilder} 自会增长，不会截断。
     */
    private static int sectionVertexCapacity(int[] cells, BlockState[] cellStates) {
        long nonAir = 0L;
        for (int i : cells) {
            BlockState state = cellStates[i];
            if (state != null && !state.isAir()) {
                nonAir++;
            }
        }
        long bytes = nonAir * 6L * 4L * 32L;
        if (bytes < 256L * 1024L) {
            return 256 * 1024;
        }
        return (int) Math.min(bytes, Integer.MAX_VALUE - 8);
    }

    // ═══════════════════════════════════════════════════════════════
    // ── 索引 ──
    // ═══════════════════════════════════════════════════════════════

    private static void restoreFullIndex(SectionMesh section) {
        long dest = INDEX_BBB.reserve(section.fullIndex.capacity());
        MemoryUtil.memCopy(MemoryUtil.memAddress(section.fullIndex), dest, section.fullIndex.capacity());
        uploadIndex(section);
    }

    private static void rebuildMaskedIndex(Minecraft mc, SectionMesh section, BlockPos anchor) {
        long dest = INDEX_BBB.reserve(section.fullIndex.capacity());
        int m = section.offsets.length;
        long p = dest;
        for (int c = 0; c < m; c++) {
            BlockOffset off = section.offsets[c];
            boolean skip = section.cellBlocks[c] != null
                    && mc.level.getBlockState(anchor.offset(off.x(), off.y(), off.z()))
                            .getBlock() == section.cellBlocks[c];
            int start = section.quadStart[c];
            int count = section.quadCount[c];
            for (int q = 0; q < count; q++) {
                int base = (start + q) * 4;
                if (skip) {
                    writeQuadIndex(p, section.indexType, base, base, base, base, base, base);
                } else {
                    writeQuadIndex(p, section.indexType, base, base + 1, base + 2, base + 2, base + 3, base);
                }
                p += 6L * section.indexType.bytes;
            }
        }
        uploadIndex(section);
        section.indexClobbered = true;
        section.maskAnchor = anchor.immutable();
        section.maskTick = currentTick(mc);
    }

    private static void uploadIndex(SectionMesh section) {
        ByteBufferBuilder.Result result = INDEX_BBB.build();
        if (result == null) return;
        section.vbo.bind();
        section.vbo.uploadIndexBuffer(result);
        VertexBuffer.unbind();
    }

    private static void writeQuadIndex(long ptr, VertexFormat.IndexType type,
                                       int i0, int i1, int i2, int i3, int i4, int i5) {
        if (type == VertexFormat.IndexType.SHORT) {
            MemoryUtil.memPutShort(ptr, (short) i0);
            MemoryUtil.memPutShort(ptr + 2, (short) i1);
            MemoryUtil.memPutShort(ptr + 4, (short) i2);
            MemoryUtil.memPutShort(ptr + 6, (short) i3);
            MemoryUtil.memPutShort(ptr + 8, (short) i4);
            MemoryUtil.memPutShort(ptr + 10, (short) i5);
        } else {
            MemoryUtil.memPutInt(ptr, i0);
            MemoryUtil.memPutInt(ptr + 4, i1);
            MemoryUtil.memPutInt(ptr + 8, i2);
            MemoryUtil.memPutInt(ptr + 12, i3);
            MemoryUtil.memPutInt(ptr + 16, i4);
            MemoryUtil.memPutInt(ptr + 20, i5);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── 绘制 ──
    // ═══════════════════════════════════════════════════════════════

    private static void drawSection(SectionMesh section, Matrix4f cameraModelView, Matrix4f projection,
                                    Vec3 camPos, BlockPos anchor) {
        // 只平移到 anchor，**不加段原点**：顶点是按绝对的旋转后偏移烘进去的（见 buildSection
        // 里那行 pose.translate(rotated.x(), ...)），段原点再加一次就是偏移两遍，整栋楼会
        // 炸成一堆错位的副本。段原点只服务于视锥剔除那一步（见 isSectionVisible）。
        Matrix4f modelView = new Matrix4f(cameraModelView).translate(
                (float) (anchor.getX() - camPos.x),
                (float) (anchor.getY() - camPos.y),
                (float) (anchor.getZ() - camPos.z));

        section.vbo.bind();
        section.vbo.drawWithShader(modelView, projection, GameRenderer.getRendertypeTranslucentShader());
        VertexBuffer.unbind();
    }

    private static final class BakedGhostMesh {
        final SectionMesh[] sections;

        BakedGhostMesh(SectionMesh[] sections) {
            this.sections = sections;
        }
    }

    private static final class SectionMesh {
        /** 段原点（相对 anchor 的 pattern 空间坐标，已对齐到 16 的倍数）。 */
        final int originX, originY, originZ;
        final VertexBuffer vbo;
        final VertexFormat.IndexType indexType;
        final int[] quadStart;
        final int[] quadCount;
        final BlockOffset[] offsets;
        final Block[] cellBlocks;
        final ByteBuffer fullIndex;
        boolean indexClobbered;
        /** 当前遮罩索引对应的 anchor；null = 还没建过遮罩。 */
        BlockPos maskAnchor;
        /** 上次重建遮罩的 tick；{@link Long#MIN_VALUE} = 还没建过。 */
        long maskTick = Long.MIN_VALUE;

        SectionMesh(int originX, int originY, int originZ,
                    VertexBuffer vbo, VertexFormat.IndexType indexType,
                    int[] quadStart, int[] quadCount,
                    BlockOffset[] offsets, Block[] cellBlocks,
                    ByteBuffer fullIndex) {
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.vbo = vbo;
            this.indexType = indexType;
            this.quadStart = quadStart;
            this.quadCount = quadCount;
            this.offsets = offsets;
            this.cellBlocks = cellBlocks;
            this.fullIndex = fullIndex;
        }
    }

    private static final class AlphaCountingConsumer implements VertexConsumer {
        private final VertexConsumer real;
        private final int[] count;

        AlphaCountingConsumer(VertexConsumer real, int[] count) {
            this.real = real;
            this.count = count;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            real.addVertex(x, y, z);
            count[0]++;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            real.setColor(r, g, b, (int) (a * GHOST_ALPHA));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            real.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            real.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            real.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            real.setNormal(x, y, z);
            return this;
        }
    }
}
