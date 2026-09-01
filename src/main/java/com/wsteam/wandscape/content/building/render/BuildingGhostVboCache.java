package com.wsteam.wandscape.content.building.render;

import com.mojang.blaze3d.vertex.*;
import com.wsteam.wandscape.content.building.data.BlockOffset;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.projection.BuildingRotation;
import com.wsteam.wandscape.content.building.preview.BuildingPreviewRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-baked GPU vertex buffers for building ghosts with zero-copy VBO rendering.
 */
public final class BuildingGhostVboCache {

    private static final float GHOST_ALPHA = 0.55f;
    private static final int FULL_BRIGHT = 0xF000F0;

    private static final Map<BuildingConfig, BakedGhostMesh[]> CACHE = new HashMap<>();
    private static final ByteBufferBuilder INDEX_BBB = new ByteBufferBuilder(4 * 1024 * 1024);

    private BuildingGhostVboCache() {}

    /** Draw the full ghost building using event camera ModelView matrix (120 FPS). */
    public static void drawGhost(Minecraft mc, Matrix4f cameraModelView, Matrix4f projection,
                                 Vec3 camPos, BlockPos anchor, BuildingConfig config, int rotationSteps) {
        BakedGhostMesh mesh = getOrBake(mc, config, rotationSteps);
        if (mesh == null) return;
        if (mesh.indexClobbered) {
            restoreFullIndex(mesh);
            mesh.indexClobbered = false;
        }
        drawVbo(mesh, cameraModelView, projection, camPos, anchor);
    }

    /** Draw ghost skipping placed blocks (under-construction footprint). */
    public static void drawGhostSkipped(Minecraft mc, Matrix4f cameraModelView, Matrix4f projection,
                                        Vec3 camPos, BlockPos anchor, BuildingConfig config, int rotationSteps) {
        BakedGhostMesh mesh = getOrBake(mc, config, rotationSteps);
        if (mesh == null) return;
        rebuildMaskedIndex(mc, mesh, anchor);
        drawVbo(mesh, cameraModelView, projection, camPos, anchor);
    }

    public static void closeAll() {
        synchronized (CACHE) {
            for (BakedGhostMesh[] buckets : CACHE.values()) {
                if (buckets == null) continue;
                for (BakedGhostMesh mesh : buckets) {
                    if (mesh != null) mesh.vbo.close();
                }
            }
            CACHE.clear();
        }
    }

    private static BakedGhostMesh getOrBake(Minecraft mc, BuildingConfig config, int rotationSteps) {
        if (config.pattern().isEmpty()) return null;
        int steps = rotationSteps & 3;
        BakedGhostMesh[] buckets = bucketsFor(config);
        BakedGhostMesh mesh = buckets[steps];
        if (mesh == null) {
            mesh = bake(mc, config, steps);
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

    private static BakedGhostMesh bake(Minecraft mc, BuildingConfig config, int steps) {
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

        int capacity = Math.max(n * 24 * 32, 1024 * 1024);
        ByteBufferBuilder vertBbb = new ByteBufferBuilder(capacity);
        BufferBuilder bb = new BufferBuilder(vertBbb, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        int[] vertexCount = new int[1];
        MultiBufferSource ghostSource = rt -> new AlphaCountingConsumer(bb, vertexCount);

        int[] quadStart = new int[n];
        int[] quadCount = new int[n];
        int totalQuads = 0;

        PoseStack pose = new PoseStack();

        // 不再对几何体施加全局旋转：旋转几何体会把每格体积相对构造偏移最多 1 格
        // （90°/270° 偏 1 格、180° 两方向各偏 1 格）。改为每格平移到旋转后的偏移
        // （rotatedOffsets）并用旋转后的 BlockState 渲染，与服务端构造逐格一致。
        for (int i = 0; i < n; i++) {
            BlockState state = cellStates[i];
            // Skip animated blocks (chests, shulker boxes, signs, banners, ...). They
            // have no static block model — renderSingleBlock would tessellate them
            // through the item/BESR path into this BLOCK-format buffer, corrupting
            // vertex layout and sampling the wrong atlas (chest atlas vs block atlas).
            // They are drawn by a separate per-frame pass (BuildingGhostRenderer.renderGhostAnimated).
            if (state == null || state.getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED) {
                quadStart[i] = totalQuads;
                quadCount[i] = 0;
                continue;
            }
            BlockOffset rotated = rotatedOffsets[i];
            int before = vertexCount[0];

            pose.pushPose();
            pose.translate(rotated.x(), rotated.y(), rotated.z());

            mc.getBlockRenderer().renderSingleBlock(
                    state, pose, ghostSource, FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    ModelData.EMPTY, RenderType.translucent());

            pose.popPose();

            quadStart[i] = totalQuads;
            quadCount[i] = (vertexCount[0] - before) / 4;
            totalQuads += quadCount[i];
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

        return new BakedGhostMesh(vbo, indexType, quadStart, quadCount, rotatedOffsets, cellBlocks, fullIndex);
    }

    private static void restoreFullIndex(BakedGhostMesh mesh) {
        long dest = INDEX_BBB.reserve(mesh.fullIndex.capacity());
        MemoryUtil.memCopy(MemoryUtil.memAddress(mesh.fullIndex), dest, mesh.fullIndex.capacity());
        uploadIndex(mesh);
    }

    private static void rebuildMaskedIndex(Minecraft mc, BakedGhostMesh mesh, BlockPos anchor) {
        long dest = INDEX_BBB.reserve(mesh.fullIndex.capacity());
        int n = mesh.rotatedOffsets.length;
        long p = dest;
        for (int c = 0; c < n; c++) {
            boolean skip = mesh.cellBlocks[c] != null
                    && mc.level.getBlockState(anchor.offset(
                            mesh.rotatedOffsets[c].x(), mesh.rotatedOffsets[c].y(), mesh.rotatedOffsets[c].z()))
                            .getBlock() == mesh.cellBlocks[c];
            int start = mesh.quadStart[c];
            int count = mesh.quadCount[c];
            for (int q = 0; q < count; q++) {
                int base = (start + q) * 4;
                if (skip) {
                    writeQuadIndex(p, mesh.indexType, base, base, base, base, base, base);
                } else {
                    writeQuadIndex(p, mesh.indexType, base, base + 1, base + 2, base + 2, base + 3, base);
                }
                p += 6L * mesh.indexType.bytes;
            }
        }
        uploadIndex(mesh);
        mesh.indexClobbered = true;
    }

    private static void uploadIndex(BakedGhostMesh mesh) {
        ByteBufferBuilder.Result result = INDEX_BBB.build();
        if (result == null) return;
        mesh.vbo.bind();
        mesh.vbo.uploadIndexBuffer(result);
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

    private static void drawVbo(BakedGhostMesh mesh, Matrix4f cameraModelView, Matrix4f projection,
                                Vec3 camPos, BlockPos anchor) {
        // Construct final ModelView matrix: camera view matrix multiplied by anchor relative translation
        Matrix4f modelView = new Matrix4f(cameraModelView).translate(
                (float) (anchor.getX() - camPos.x),
                (float) (anchor.getY() - camPos.y),
                (float) (anchor.getZ() - camPos.z));

        RenderType rt = RenderType.translucent();
        rt.setupRenderState();

        mesh.vbo.bind();
        mesh.vbo.drawWithShader(modelView, projection, GameRenderer.getRendertypeTranslucentShader());
        VertexBuffer.unbind();

        rt.clearRenderState();
    }

    private static final class BakedGhostMesh {
        final VertexBuffer vbo;
        final VertexFormat.IndexType indexType;
        final int[] quadStart;
        final int[] quadCount;
        final BlockOffset[] rotatedOffsets;
        final Block[] cellBlocks;
        final ByteBuffer fullIndex;
        boolean indexClobbered;

        BakedGhostMesh(VertexBuffer vbo, VertexFormat.IndexType indexType,
                       int[] quadStart, int[] quadCount,
                       BlockOffset[] rotatedOffsets, Block[] cellBlocks,
                       ByteBuffer fullIndex) {
            this.vbo = vbo;
            this.indexType = indexType;
            this.quadStart = quadStart;
            this.quadCount = quadCount;
            this.rotatedOffsets = rotatedOffsets;
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
