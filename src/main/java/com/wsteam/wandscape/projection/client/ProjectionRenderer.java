package com.wsteam.wandscape.projection.client;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.projection.BuildingRotation;
import com.wsteam.wandscape.projection.data.BuildingSlot;
import com.wsteam.wandscape.shared.client.render.BuildingGhostRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import com.wsteam.wandscape.shared.log.Log;

/**
 * World-space rendering for soul projection mode.
 */
public final class ProjectionRenderer {

    private static final String TAG = "ProjectionRenderer";
    private static boolean registered = false;

    private ProjectionRenderer() {}

    public static void register() {
        if (registered) return;
        registered = true;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS
                .addListener(RenderLevelStageEvent.class, ProjectionRenderer::onRenderLevelStage);
        Log.info(TAG, "[Projection] Renderer registered");
    }

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!ProjectionClientState.isProjecting()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        renderGhostPreview(mc, bufferSource, poseStack, event);

        // Boundary wireframe uses camera-space translated poseStack
        BlockPos ghostPos = ProjectionClientState.getGhostPos();
        BuildingSlot slot = getSelectedSlot();
        BuildingConfig config = (slot != null) ? BuildingConfigLoader.getInstance().get(slot.id()) : null;

        if (ghostPos != null && config != null && config.boundary() != null) {
            boolean overlap = ProjectionClientState.isOverlapDetected();
            boolean pinned = ProjectionClientState.isPinned();

            BuildingConfig.BoundaryBox boundary =
                    BuildingRotation.rotateBoundary(config.boundary(), ProjectionClientState.getRotationSteps());

            poseStack.pushPose();
            poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

            if (pinned && !overlap) {
                VertexConsumer lineVc = bufferSource.getBuffer(RenderType.lines());
                drawAABBOutline(lineVc, poseStack.last(), ghostPos,
                        boundary.min(), boundary.max(), 255, 255, 255);
                bufferSource.endBatch(RenderType.lines());
            } else if (overlap) {
                VertexConsumer lineVc = bufferSource.getBuffer(RenderType.lines());
                drawAABBOutline(lineVc, poseStack.last(), ghostPos,
                        boundary.min(), boundary.max(), 255, 40, 40);
                bufferSource.endBatch(RenderType.lines());
            }

            poseStack.popPose();
        }
    }

    private static void renderGhostPreview(Minecraft mc, MultiBufferSource.BufferSource bufferSource,
                                           PoseStack poseStack, RenderLevelStageEvent event) {
        BlockPos ghostPos = ProjectionClientState.getGhostPos();
        if (ghostPos == null) return;

        BuildingSlot slot = getSelectedSlot();
        BuildingConfig config = (slot != null) ? BuildingConfigLoader.getInstance().get(slot.id()) : null;
        if (config == null) return;

        BuildingGhostRenderer.renderGhostVbo(mc, poseStack, event.getProjectionMatrix(),
                ghostPos, config, ProjectionClientState.getRotationSteps());
    }

    private static BuildingSlot getSelectedSlot() {
        List<BuildingSlot> slots = ProjectionClientState.getBuildingSlots();
        int index = ProjectionClientState.getSelectedSlotIndex();
        if (slots.isEmpty() || index < 0 || index >= slots.size()) return null;
        return slots.get(index);
    }

    private static void drawAABBOutline(VertexConsumer vc, PoseStack.Pose poseEntry,
                                         BlockPos anchor, BlockOffset min, BlockOffset max,
                                         int r, int g, int b) {
        float x0 = anchor.getX() + min.x() + 0.5f;
        float y0 = anchor.getY() + min.y() + 0.5f;
        float z0 = anchor.getZ() + min.z() + 0.5f;
        float x1 = anchor.getX() + max.x() + 0.5f;
        float y1 = anchor.getY() + max.y() + 0.5f;
        float z1 = anchor.getZ() + max.z() + 0.5f;

        seg(vc, poseEntry, x0, y0, z0, x1, y0, z0, r, g, b);
        seg(vc, poseEntry, x1, y0, z0, x1, y0, z1, r, g, b);
        seg(vc, poseEntry, x1, y0, z1, x0, y0, z1, r, g, b);
        seg(vc, poseEntry, x0, y0, z1, x0, y0, z0, r, g, b);
        seg(vc, poseEntry, x0, y1, z0, x1, y1, z0, r, g, b);
        seg(vc, poseEntry, x1, y1, z0, x1, y1, z1, r, g, b);
        seg(vc, poseEntry, x1, y1, z1, x0, y1, z1, r, g, b);
        seg(vc, poseEntry, x0, y1, z1, x0, y1, z0, r, g, b);
        seg(vc, poseEntry, x0, y0, z0, x0, y1, z0, r, g, b);
        seg(vc, poseEntry, x1, y0, z0, x1, y1, z0, r, g, b);
        seg(vc, poseEntry, x1, y0, z1, x1, y1, z1, r, g, b);
        seg(vc, poseEntry, x0, y0, z1, x0, y1, z1, r, g, b);
    }

    private static void seg(VertexConsumer vc, PoseStack.Pose poseEntry,
                            float x1, float y1, float z1, float x2, float y2, float z2,
                            int r, int g, int b) {
        vc.addVertex(poseEntry, x1, y1, z1).setColor(r, g, b, 255).setNormal(poseEntry, 0, 1, 0);
        vc.addVertex(poseEntry, x2, y2, z2).setColor(r, g, b, 255).setNormal(poseEntry, 0, 1, 0);
    }
}
