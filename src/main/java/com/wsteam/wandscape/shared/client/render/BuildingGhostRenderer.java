package com.wsteam.wandscape.shared.client.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.projection.BuildingRotation;
import com.wsteam.wandscape.shared.ui.util.BuildingPreviewRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * World-space semi-transparent building ghost renderer facade.
 */
public final class BuildingGhostRenderer {

    private static final float GHOST_ALPHA = 0.40f;
    private static final int FULL_BRIGHT = 0xF000F0;

    private BuildingGhostRenderer() {}

    /** Render full building ghost via GPU VBO static cache with camera ModelView (120 FPS). */
    public static void renderGhostVbo(Minecraft mc, Matrix4f cameraModelView, Matrix4f projection,
                                      Vec3 camPos, BlockPos anchor, BuildingConfig config, int rotationSteps) {
        BuildingGhostVboCache.drawGhost(mc, cameraModelView, projection, camPos, anchor, config, rotationSteps);
    }

    /** Render under-construction footprint ghost skipping placed blocks via GPU VBO. */
    public static void renderGhostVboSkipped(Minecraft mc, Matrix4f cameraModelView, Matrix4f projection,
                                             Vec3 camPos, BlockPos anchor, BuildingConfig config, int rotationSteps) {
        BuildingGhostVboCache.drawGhostSkipped(mc, cameraModelView, projection, camPos, anchor, config, rotationSteps);
    }

    public record RotatedBlockEntry(int rx, int ry, int rz, BlockState state) {}

    public static final class RotatedGhostCache {
        public final List<RotatedBlockEntry> fullEntries;
        public final List<RotatedBlockEntry> lodEntries;

        public RotatedGhostCache(BuildingConfig config, int rotationSteps) {
            Map<BlockOffset, BlockState> blockStates = BuildingPreviewRenderer.resolveBlockStates(config);
            if (blockStates.isEmpty()) {
                this.fullEntries = Collections.emptyList();
                this.lodEntries = Collections.emptyList();
                return;
            }

            List<RotatedBlockEntry> entries = new ArrayList<>(blockStates.size());
            Set<BlockOffset> occupiedRotated = new HashSet<>(blockStates.size());

            for (var entry : blockStates.entrySet()) {
                BlockOffset originalOffset = entry.getKey();
                BlockState originalState = entry.getValue();

                BlockOffset rotatedOffset = BuildingRotation.rotateOffset(originalOffset, rotationSteps);
                BlockState rotatedState = originalState;
                for (int i = 0; i < rotationSteps; i++) {
                    rotatedState = rotatedState.rotate(Rotation.CLOCKWISE_90);
                }

                entries.add(new RotatedBlockEntry(
                        rotatedOffset.x(), rotatedOffset.y(), rotatedOffset.z(), rotatedState));
                occupiedRotated.add(rotatedOffset);
            }

            this.fullEntries = Collections.unmodifiableList(entries);
            this.lodEntries = this.fullEntries;
        }
    }

    private record CacheKey(BuildingConfig config, int rotationSteps) {}

    private static final Map<CacheKey, RotatedGhostCache> ROTATED_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static RotatedGhostCache getRotatedGhostCache(BuildingConfig config, int rotationSteps) {
        CacheKey key = new CacheKey(config, rotationSteps);
        return ROTATED_CACHE.computeIfAbsent(key, k -> new RotatedGhostCache(k.config(), k.rotationSteps()));
    }

    private static final GhostVertexConsumer GHOST_CONSUMER = new GhostVertexConsumer();
    private static final GhostBufferSource GHOST_BUFFER_SOURCE = new GhostBufferSource(GHOST_CONSUMER);

    private static final class GhostBufferSource implements MultiBufferSource {
        private MultiBufferSource delegate;
        private final GhostVertexConsumer consumer;

        public GhostBufferSource(GhostVertexConsumer consumer) {
            this.consumer = consumer;
        }

        public void setDelegate(MultiBufferSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            consumer.setDelegate(delegate.getBuffer(renderType));
            return consumer;
        }
    }

    private static final class GhostVertexConsumer implements VertexConsumer {
        private VertexConsumer real;

        public void setDelegate(VertexConsumer real) {
            this.real = real;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            real.addVertex(x, y, z);
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

    public static void renderGhostBlocks(Minecraft mc, MultiBufferSource.BufferSource bufferSource,
                                          PoseStack poseStack,
                                          BlockPos anchor, BuildingConfig config, int rotationSteps,
                                          boolean hideBuiltBlocks) {
        RotatedGhostCache cache = getRotatedGhostCache(config, rotationSteps);
        List<RotatedBlockEntry> entries = cache.lodEntries;
        if (entries.isEmpty()) return;

        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
        GHOST_BUFFER_SOURCE.setDelegate(bufferSource);

        for (int i = 0; i < entries.size(); i++) {
            RotatedBlockEntry entry = entries.get(i);

            if (hideBuiltBlocks) {
                BlockPos worldPos = anchor.offset(entry.rx(), entry.ry(), entry.rz());
                if (mc.level.getBlockState(worldPos).getBlock() == entry.state().getBlock()) {
                    continue;
                }
            }

            poseStack.pushPose();
            poseStack.translate(
                    anchor.getX() + entry.rx(),
                    anchor.getY() + entry.ry(),
                    anchor.getZ() + entry.rz());

            blockRenderer.renderSingleBlock(
                    entry.state(), poseStack, GHOST_BUFFER_SOURCE,
                    FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    ModelData.EMPTY, null);

            poseStack.popPose();
        }

        bufferSource.endBatch(Sheets.cutoutBlockSheet());
        bufferSource.endBatch(Sheets.translucentCullBlockSheet());
        bufferSource.endBatch(Sheets.translucentItemSheet());
    }
}
