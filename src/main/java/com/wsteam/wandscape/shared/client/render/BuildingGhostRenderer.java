package com.wsteam.wandscape.shared.client.render;

import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.projection.BuildingRotation;
import com.wsteam.wandscape.shared.ui.util.BuildingPreviewRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * World-space semi-transparent building ghost renderer.
 *
 * <p>Renders the full textured block model of a {@link BuildingConfig} at an
 * anchor position with alpha applied uniformly, so the target footprint reads
 * as a "ghost". Shared by the projection placement preview and the
 * under-construction building footprint overlay.
 */
public final class BuildingGhostRenderer {

    /** Alpha factor for ghost blocks (0.0-1.0). Applied via setColor interception. */
    private static final float GHOST_ALPHA = 0.40f;
    /** Full brightness: block=15, sky=15 (LightTexture.pack(15,15)). */
    private static final int FULL_BRIGHT = 0xF000F0;

    private BuildingGhostRenderer() {}

    /**
     * Render {@code config}'s pattern blocks as a semi-transparent ghost at {@code anchor}.
     *
     * @param rotationSteps number of 90° CCW rotations (0-3)
     * @param hideBuiltBlocks when true, cells already containing a block of the
     *                        expected type are skipped so a real block takes
     *                        priority over the ghost (construction footprint)
     */
    public static void renderGhostBlocks(Minecraft mc, MultiBufferSource.BufferSource bufferSource,
                                          PoseStack poseStack,
                                          BlockPos anchor, BuildingConfig config, int rotationSteps,
                                          boolean hideBuiltBlocks) {
        Map<BlockOffset, BlockState> blockStates = BuildingPreviewRenderer.resolveBlockStates(config);
        if (blockStates.isEmpty()) return;

        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();

        // GhostBufferSource: wraps every VertexConsumer returned by bufferSource
        // to multiply alpha on setColor. All default methods (putBulkData, addVertex
        // with color/light/overlay) transitively call setColor, so alpha is applied
        // uniformly to all rendering paths.
        MultiBufferSource ghostSource = renderType -> {
            VertexConsumer real = bufferSource.getBuffer(renderType);
            return new VertexConsumer() {
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
            };
        };

        for (var entry : blockStates.entrySet()) {
            BlockOffset originalOffset = entry.getKey();
            BlockState originalState = entry.getValue();

            BlockOffset rotatedOffset = BuildingRotation.rotateOffset(originalOffset, rotationSteps);
            BlockState rotatedState = originalState;
            for (int i = 0; i < rotationSteps; i++) {
                rotatedState = rotatedState.rotate(Rotation.CLOCKWISE_90);
            }

            // A real block wins over the ghost: once the intended block is placed
            // at this cell, stop rendering its ghost (otherwise the ghost blends
            // over the block and the cell looks unbuilt).
            if (hideBuiltBlocks) {
                BlockPos worldPos = anchor.offset(
                        rotatedOffset.x(), rotatedOffset.y(), rotatedOffset.z());
                if (mc.level.getBlockState(worldPos).getBlock() == rotatedState.getBlock()) {
                    continue;
                }
            }

            poseStack.pushPose();
            poseStack.translate(
                    anchor.getX() + rotatedOffset.x(),
                    anchor.getY() + rotatedOffset.y(),
                    anchor.getZ() + rotatedOffset.z());

            blockRenderer.renderSingleBlock(
                    rotatedState, poseStack, ghostSource,
                    FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    ModelData.EMPTY, null);

            poseStack.popPose();
        }

        // Flush entity-block render types
        bufferSource.endBatch(Sheets.cutoutBlockSheet());
        bufferSource.endBatch(Sheets.translucentCullBlockSheet());
        bufferSource.endBatch(Sheets.translucentItemSheet());
    }
}
