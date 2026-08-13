package com.wsteam.wandscape.shared.client.render;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.projection.BuildingRotation;
import com.wsteam.wandscape.shared.ui.util.BuildingPreviewRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Pre-baked GPU vertex buffers for building ghosts.
 *
 * <p>Bakes a {@link BuildingConfig} at a given rotation into a static
 * {@link VertexBuffer} (config-local coordinates), replacing the old per-frame
 * per-block {@code renderSingleBlock} loop. For a large building that was
 * re-tessellating ~8.5k blocks every frame (the source of the projection FPS
 * drop and its GC churn), a frame is now one draw call.
 *
 * <p>Two draw modes share the same baked vertex data:
 * <ul>
 *   <li>{@link #drawGhost} — full building (projection placement preview).</li>
 *   <li>{@link #drawGhostSkipped} — construction footprint, hides cells that
 *       already contain the expected block. Skipped cells are written as
 *       degenerate triangles so {@code indexCount} stays constant and the whole
 *       buffer can be drawn with a single call.</li>
 * </ul>
 *
 * <p>GPU buffers are explicit {@link VertexBuffer#close()}'d via {@link #closeAll()}
 * on resource reload and world logout (config instances are recreated on reload,
 * so a WeakHashMap alone would leak VRAM).
 */
public final class BuildingGhostVboCache {

    /** Alpha factor for ghost blocks (0.0-1.0), baked into vertex colors. */
    private static final float GHOST_ALPHA = 0.40f;
    /** Full brightness: block=15, sky=15 (LightTexture.pack(15,15)). */
    private static final int FULL_BRIGHT = 0xF000F0;

    /** Cache: config → one baked mesh per rotation step (0-3). */
    private static final Map<BuildingConfig, BakedGhostMesh[]> CACHE = new HashMap<>();

    /**
     * Reusable native buffer for per-frame index rebuilds. Render thread only:
     * {@code build()} then {@code VertexBuffer.uploadIndexBuffer} closes the
     * result, which resets {@code writeOffset}, so one builder can be reused
     * every frame without growing or leaking.
     */
    private static final ByteBufferBuilder INDEX_BBB = new ByteBufferBuilder(4 * 1024 * 1024);

    private BuildingGhostVboCache() {}

    // ═══════════════════════════════════════════════════════════════
    // ── Public API ──
    // ═══════════════════════════════════════════════════════════════

    /** Draw the full ghost building (projection placement preview). */
    public static void drawGhost(Minecraft mc, PoseStack poseStack, Matrix4f projection,
                                 BlockPos anchor, BuildingConfig config, int rotationSteps) {
        BakedGhostMesh mesh = getOrBake(mc, config, rotationSteps);
        if (mesh == null) return;
        if (mesh.indexClobbered) {
            restoreFullIndex(mesh);
            mesh.indexClobbered = false;
        }
        drawVbo(mesh, poseStack, projection, anchor);
    }

    /**
     * Draw the ghost skipping cells that already contain the expected block
     * (under-construction footprint). The skip mask is re-sampled from the
     * world every call, so it tracks placed blocks live.
     */
    public static void drawGhostSkipped(Minecraft mc, PoseStack poseStack, Matrix4f projection,
                                        BlockPos anchor, BuildingConfig config, int rotationSteps) {
        BakedGhostMesh mesh = getOrBake(mc, config, rotationSteps);
        if (mesh == null) return;
        rebuildMaskedIndex(mc, mesh, anchor);
        drawVbo(mesh, poseStack, projection, anchor);
    }

    /** Close all cached GPU buffers (resource reload / world logout). Render thread. */
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

    // ═══════════════════════════════════════════════════════════════
    // ── Cache / bake ──
    // ═══════════════════════════════════════════════════════════════

    private static BakedGhostMesh getOrBake(Minecraft mc, BuildingConfig config, int rotationSteps) {
        if (config.pattern().isEmpty()) return null;
        int steps = rotationSteps & 3;
        BakedGhostMesh[] buckets = bucketsFor(config);
        BakedGhostMesh mesh = buckets[steps];
        if (mesh == null) {
            // Bake outside the lock: tesselation can take ~10ms for a large building.
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

    /**
     * Tessellate every cell once into a static vertex buffer in config-local
     * coordinates. Reuses the same {@code renderSingleBlock} path the old
     * per-frame renderer used, so visuals (AO shading, block tint, alpha 0.4)
     * are identical — just done once instead of every frame.
     */
    private static BakedGhostMesh bake(Minecraft mc, BuildingConfig config, int steps) {
        List<BlockOffset> pattern = config.pattern();
        int n = pattern.size();

        // Rotated per-cell offsets / states (same math as the old per-frame loop).
        BlockOffset[] cellOffsets = new BlockOffset[n];
        Block[] cellBlocks = new Block[n];
        BlockState[] cellStates = new BlockState[n];
        for (int i = 0; i < n; i++) {
            cellOffsets[i] = BuildingRotation.rotateOffset(pattern.get(i), steps);
            BlockState state = BuildingPreviewRenderer.resolveBlockState(config.blockIdAt(i));
            if (state != null) {
                for (int r = 0; r < steps; r++) {
                    state = state.rotate(Rotation.CLOCKWISE_90);
                }
            }
            cellStates[i] = state;
            cellBlocks[i] = state != null ? state.getBlock() : null;
        }

        // Bake geometry: up to 24 verts × 32 bytes per cell, growable.
        int capacity = Math.max(n * 24 * 32, 1024 * 1024);
        ByteBufferBuilder vertBbb = new ByteBufferBuilder(capacity);
        BufferBuilder bb = new BufferBuilder(vertBbb, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        int[] vertexCount = new int[1];
        MultiBufferSource ghostSource = rt -> new AlphaCountingConsumer(bb, vertexCount);

        int[] quadStart = new int[n];
        int[] quadCount = new int[n];
        int totalQuads = 0;
        PoseStack pose = new PoseStack();
        for (int i = 0; i < n; i++) {
            BlockState state = cellStates[i];
            if (state == null) {
                quadStart[i] = totalQuads;
                continue;
            }
            int before = vertexCount[0];
            pose.pushPose();
            pose.translate(cellOffsets[i].x(), cellOffsets[i].y(), cellOffsets[i].z());
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
        vbo.upload(mesh); // uploads vertices + sequential index, closes mesh
        VertexBuffer.unbind();

        // Cached full index bytes (all cells, no skips) — used to restore the
        // projection index after the footprint draw replaced it.
        ByteBuffer fullIndex = ByteBuffer.allocateDirect(totalQuads * 6 * indexType.bytes);
        long fp = MemoryUtil.memAddress(fullIndex);
        for (int q = 0; q < totalQuads; q++) {
            int base = q * 4;
            writeQuadIndex(fp, indexType, base, base + 1, base + 2, base + 2, base + 3, base);
            fp += 6L * indexType.bytes;
        }

        return new BakedGhostMesh(vbo, indexType, quadStart, quadCount, cellOffsets, cellBlocks, fullIndex);
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Index buffers ──
    // ═══════════════════════════════════════════════════════════════

    /** Re-upload the all-cells index (projection draw after a footprint draw replaced it). */
    private static void restoreFullIndex(BakedGhostMesh mesh) {
        long dest = INDEX_BBB.reserve(mesh.fullIndex.capacity());
        MemoryUtil.memCopy(MemoryUtil.memAddress(mesh.fullIndex), dest, mesh.fullIndex.capacity());
        uploadIndex(mesh);
    }

    /**
     * Rebuild the index buffer with degenerate triangles for cells already
     * holding the expected block, then upload and draw. indexCount stays at the
     * full count, so the degenerate approach needs a full-size buffer every time.
     */
    private static void rebuildMaskedIndex(Minecraft mc, BakedGhostMesh mesh, BlockPos anchor) {
        long dest = INDEX_BBB.reserve(mesh.fullIndex.capacity());
        int n = mesh.cellOffsets.length;
        long p = dest;
        for (int c = 0; c < n; c++) {
            boolean skip = mesh.cellBlocks[c] != null
                    && mc.level.getBlockState(anchor.offset(
                            mesh.cellOffsets[c].x(), mesh.cellOffsets[c].y(), mesh.cellOffsets[c].z()))
                            .getBlock() == mesh.cellBlocks[c];
            int start = mesh.quadStart[c];
            int count = mesh.quadCount[c];
            for (int q = 0; q < count; q++) {
                int base = (start + q) * 4;
                if (skip) {
                    writeQuadIndex(p, mesh.indexType, base, base, base, base, base, base); // degenerate
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
        mesh.vbo.uploadIndexBuffer(result); // closes the result (also resets INDEX_BBB for reuse)
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
    // ── Draw ──
    // ═══════════════════════════════════════════════════════════════

    private static void drawVbo(BakedGhostMesh mesh, PoseStack poseStack, Matrix4f projection, BlockPos anchor) {
        RenderType rt = RenderType.translucent();
        poseStack.pushPose();
        poseStack.translate(anchor.getX(), anchor.getY(), anchor.getZ());
        rt.setupRenderState();
        mesh.vbo.bind();
        mesh.vbo.drawWithShader(poseStack.last().pose(), projection, GameRenderer.getRendertypeTranslucentShader());
        VertexBuffer.unbind();
        rt.clearRenderState();
        poseStack.popPose();
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Baked mesh ──
    // ═══════════════════════════════════════════════════════════════

    private static final class BakedGhostMesh {
        final VertexBuffer vbo;
        final VertexFormat.IndexType indexType;
        final int[] quadStart;
        final int[] quadCount;
        final BlockOffset[] cellOffsets;
        final Block[] cellBlocks;
        final ByteBuffer fullIndex;
        /** True when the construction-footprint draw replaced the index buffer. */
        boolean indexClobbered;

        BakedGhostMesh(VertexBuffer vbo, VertexFormat.IndexType indexType,
                       int[] quadStart, int[] quadCount,
                       BlockOffset[] cellOffsets, Block[] cellBlocks,
                       ByteBuffer fullIndex) {
            this.vbo = vbo;
            this.indexType = indexType;
            this.quadStart = quadStart;
            this.quadCount = quadCount;
            this.cellOffsets = cellOffsets;
            this.cellBlocks = cellBlocks;
            this.fullIndex = fullIndex;
        }
    }

    // ── Vertex consumer: bake-time alpha + vertex counting ──

    /** Wraps the bake BufferBuilder: multiplies alpha by {@link #GHOST_ALPHA}
     * and counts vertices (4 per quad) so per-cell quad ranges are recorded. */
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
