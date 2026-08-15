package com.wsteam.wandscape.road.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Shared world-space helpers for rendering road blocks as translucent ghosts,
 * used by both the placement preview and the under-construction ghost.
 */
final class RoadGhostRenderUtil {

    private static final int FULL_BRIGHT = 0xF000F0;

    private RoadGhostRenderUtil() {}

    /** Wrap the real buffer source so every block's color alpha is scaled by {@code alpha}. */
    static MultiBufferSource ghostSource(MultiBufferSource.BufferSource bufferSource, float alpha) {
        return rt -> new GhostAlphaConsumer(bufferSource.getBuffer(rt), alpha);
    }

    /**
     * Render a single block as a translucent ghost at world position (x, y, z).
     *
     * <p>The ghost is lifted so the block's top clears the surface it will replace —
     * short blocks (e.g. dirt path) are otherwise hidden behind the existing terrain
     * in the depth buffer. Blocks without a renderable model (fluids such as water)
     * get a translucent placeholder cube instead.
     */
    static void renderGhostBlock(Level level, BlockState state, PoseStack poseStack, MultiBufferSource ghostSource,
                                 int x, int y, int z) {
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            renderGhostCube(poseStack, ghostSource, x, y, z);
            return;
        }

        float top = (float) state.getShape(level, new BlockPos(x, y, z)).max(Direction.Axis.Y);
        float lift = (top <= 0f ? 0f : (1f - top)) + 0.02f;

        poseStack.pushPose();
        poseStack.translate(x, y + lift, z);
        BlockRenderDispatcher blockRenderer = Minecraft.getInstance().getBlockRenderer();
        blockRenderer.renderSingleBlock(state, poseStack, ghostSource,
                FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.translucent());
        poseStack.popPose();
    }

    /** Translucent full-cube placeholder for blocks without a renderable model (e.g. water). */
    private static void renderGhostCube(PoseStack poseStack, MultiBufferSource ghostSource, int x, int y, int z) {
        poseStack.pushPose();
        poseStack.translate(x, y + 0.02f, z);
        VertexConsumer vc = ghostSource.getBuffer(RenderType.translucent());
        var pose = poseStack.last();

        quad(vc, pose, 0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1); // bottom
        quad(vc, pose, 0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0); // top
        quad(vc, pose, 0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1); // -X
        quad(vc, pose, 1, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 0); // +X
        quad(vc, pose, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0); // -Z
        quad(vc, pose, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 0, 1); // +Z

        poseStack.popPose();
    }

    /** Adds a single quad (two triangles) at full-bright white through the ghost alpha wrapper. */
    private static void quad(VertexConsumer vc, PoseStack.Pose pose,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4) {
        vc.addVertex(pose, x1, y1, z1).setColor(255, 255, 255, 255).setUv(0, 0).setUv2(0xF0, 0xF0).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x2, y2, z2).setColor(255, 255, 255, 255).setUv(0, 0).setUv2(0xF0, 0xF0).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x3, y3, z3).setColor(255, 255, 255, 255).setUv(0, 0).setUv2(0xF0, 0xF0).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x3, y3, z3).setColor(255, 255, 255, 255).setUv(0, 0).setUv2(0xF0, 0xF0).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x4, y4, z4).setColor(255, 255, 255, 255).setUv(0, 0).setUv2(0xF0, 0xF0).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x1, y1, z1).setColor(255, 255, 255, 255).setUv(0, 0).setUv2(0xF0, 0xF0).setNormal(pose, 0, 1, 0);
    }

    /** Whether the world block at (x, y, z) already matches the target block (road placed). */
    static boolean isPlaced(Level level, int x, int y, int z, BlockState target) {
        return target != null && level.getBlockState(new BlockPos(x, y, z)).getBlock() == target.getBlock();
    }

    /** Scales color alpha so block models render as translucent ghosts. */
    private record GhostAlphaConsumer(VertexConsumer real, float alpha) implements VertexConsumer {

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            real.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            real.setColor(r, g, b, (int) (a * alpha));
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
