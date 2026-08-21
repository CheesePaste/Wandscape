package com.wsteam.wandscape.worldreloader.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.worldreloader.network.TransformPreviewPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;

/**
 * World-space ghost renderer for terrain transformation preview.
 * Renders the full preview with increasing opacity (transparency decreasing / solidity increasing)
 * before actual block modifications commence.
 */
public final class TransformPreviewRenderer {

    private static final String TAG = "TransformPreviewRenderer";
    private static final int FULL_BRIGHT = 0xF000F0;
    private static boolean registered = false;

    private TransformPreviewRenderer() {}

    public static void register() {
        if (registered) return;
        registered = true;
        TransformPreviewClientState.init();
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, TransformPreviewRenderer::onRenderLevelStage);
        Log.info(TAG, "TransformPreviewRenderer registered to RenderLevelStageEvent");
    }

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!TransformPreviewClientState.isActive()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        BlockPos center = TransformPreviewClientState.getCenter();
        if (center == null) return;

        int radius = TransformPreviewClientState.getRadius();
        float alpha = TransformPreviewClientState.getAlpha();
        List<TransformPreviewPacket.PreviewBlock> blocks = TransformPreviewClientState.getBlocks();
        if (blocks.isEmpty()) return;

        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        // 1. Draw glowing boundary ring around the transform area
        renderBoundaryRing(mc.level, bufferSource, poseStack, center, radius, alpha);

        // 2. Draw 3D translucent ghost blocks
        MultiBufferSource ghostSource = rt -> new GhostAlphaConsumer(bufferSource.getBuffer(rt), alpha);
        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();

        for (TransformPreviewPacket.PreviewBlock pb : blocks) {
            int wx = center.getX() + pb.dx();
            int wy = center.getY() + pb.dy();
            int wz = center.getZ() + pb.dz();

            // Distance & frustum check for individual block cells
            double dx = (wx + 0.5) - camPos.x;
            double dy = (wy + 0.5) - camPos.y;
            double dz = (wz + 0.5) - camPos.z;
            if (dx * dx + dy * dy + dz * dz > 16384.0) { // 128 blocks max render distance
                continue;
            }

            BlockState state = pb.state();
            if (state == null || state.isAir()) continue;

            if (state.getRenderShape() == RenderShape.INVISIBLE) {
                renderGhostCube(poseStack, ghostSource, wx, wy, wz);
                continue;
            }

            poseStack.pushPose();
            poseStack.translate(wx, wy + 0.005f, wz);
            blockRenderer.renderSingleBlock(state, poseStack, ghostSource,
                    FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.translucent());
            poseStack.popPose();
        }

        poseStack.popPose();

        bufferSource.endBatch(RenderType.lines());
        bufferSource.endBatch(RenderType.translucent());
    }

    private static void renderBoundaryRing(Level level, MultiBufferSource bufferSource, PoseStack poseStack,
                                           BlockPos center, int radius, float alpha) {
        VertexConsumer vc = bufferSource.getBuffer(RenderType.lines());
        var pose = poseStack.last();
        int segments = Math.max(32, radius * 2);
        int r = (int) (100 + 155 * alpha);
        int g = (int) (200 + 55 * alpha);
        int b = 255;
        int a = (int) (120 + 135 * alpha);

        double prevX = center.getX() + 0.5 + radius;
        double prevZ = center.getZ() + 0.5;
        float ringY = (float) center.getY() + 0.05f;

        for (int i = 1; i <= segments; i++) {
            double angle = (2 * Math.PI * i) / segments;
            double currX = center.getX() + 0.5 + Math.cos(angle) * radius;
            double currZ = center.getZ() + 0.5 + Math.sin(angle) * radius;

            vc.addVertex(pose, (float) prevX, ringY, (float) prevZ).setColor(r, g, b, a).setNormal(pose, 0, 1, 0);
            vc.addVertex(pose, (float) currX, ringY, (float) currZ).setColor(r, g, b, a).setNormal(pose, 0, 1, 0);

            prevX = currX;
            prevZ = currZ;
        }
    }

    private static void renderGhostCube(PoseStack poseStack, MultiBufferSource ghostSource, int x, int y, int z) {
        poseStack.pushPose();
        poseStack.translate(x, y + 0.015f, z);
        VertexConsumer vc = ghostSource.getBuffer(RenderType.translucent());
        var pose = poseStack.last();

        quad(vc, pose, 0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1);
        quad(vc, pose, 0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0);
        quad(vc, pose, 0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1);
        quad(vc, pose, 1, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 0);
        quad(vc, pose, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0);
        quad(vc, pose, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 0, 1);

        poseStack.popPose();
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4) {
        vc.addVertex(pose, x1, y1, z1).setColor(200, 220, 255, 180).setUv(0, 0).setLight(FULL_BRIGHT).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x2, y2, z2).setColor(200, 220, 255, 180).setUv(0, 0).setLight(FULL_BRIGHT).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x3, y3, z3).setColor(200, 220, 255, 180).setUv(0, 0).setLight(FULL_BRIGHT).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x3, y3, z3).setColor(200, 220, 255, 180).setUv(0, 0).setLight(FULL_BRIGHT).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x4, y4, z4).setColor(200, 220, 255, 180).setUv(0, 0).setLight(FULL_BRIGHT).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x1, y1, z1).setColor(200, 220, 255, 180).setUv(0, 0).setLight(FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }

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
