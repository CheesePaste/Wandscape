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
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import com.wsteam.wandscape.shared.log.Log;

/**
 * World-space rendering for soul projection mode.
 *
 * <p>Renders:
 * <ul>
 *   <li>Ghost building preview — actual textured block models rendered
 *       semi-transparently at the targeted position via
 *       {@link BuildingGhostRenderer}.</li>
 *   <li>Body anchor meditation beam — translucent purple pillar at the
 *       position where the player left their body.</li>
 *   <li>Red wireframe boundary when overlapping an existing building.</li>
 * </ul>
 */
public final class ProjectionRenderer {

    private static final String TAG = "ProjectionRenderer";

    private static boolean registered = false;

    private ProjectionRenderer() {}

    // ── Registration ──

    public static void register() {
        if (registered) return;
        registered = true;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS
                .addListener(RenderLevelStageEvent.class, ProjectionRenderer::onRenderLevelStage);
        Log.info(TAG, "[Projection] Renderer registered");
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Render handler ──
    // ═══════════════════════════════════════════════════════════════

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!ProjectionClientState.isProjecting()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        renderFaceHighlight(bufferSource, poseStack);

        // Hold Left Alt to hide the ghost preview and inspect the origin point
        long window = mc.getWindow().getWindow();
        boolean hidePreview = window != 0L
                && org.lwjgl.glfw.GLFW.glfwGetKey(window,
                        org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        if (!hidePreview) {
            renderGhostPreview(mc, bufferSource, poseStack);
        }

        poseStack.popPose();
    }

    // ── Ghost preview (real block rendering) ──

    private static void renderGhostPreview(Minecraft mc, MultiBufferSource.BufferSource bufferSource,
                                           PoseStack poseStack) {
        BlockPos ghostPos = ProjectionClientState.getGhostPos();
        if (ghostPos == null) return;

        BuildingSlot slot = getSelectedSlot();
        BuildingConfig config = (slot != null) ? BuildingConfigLoader.getInstance().get(slot.id()) : null;
        if (config == null) return;

        boolean overlap = ProjectionClientState.isOverlapDetected();
        boolean pinned = ProjectionClientState.isPinned();

        BuildingGhostRenderer.renderGhostBlocks(mc, bufferSource, poseStack,
                ghostPos, config, ProjectionClientState.getRotationSteps(), false);

        // Boundary is rotated so the outline matches the ghost's current rotation —
        // same source as the white "target building" highlight (WandscapeHighlightRenderer).
        if (config.boundary() != null) {
            BuildingConfig.BoundaryBox boundary =
                    BuildingRotation.rotateBoundary(config.boundary(), ProjectionClientState.getRotationSteps());

            // Pinned (non-overlap): white wireframe — ghost is fixed, player can walk around to review
            if (pinned && !overlap) {
                VertexConsumer lineVc = bufferSource.getBuffer(RenderType.lines());
                drawAABBOutline(lineVc, poseStack.last(), ghostPos,
                        boundary.min(), boundary.max(), 255, 255, 255);
                bufferSource.endBatch(RenderType.lines());
            } else if (overlap) {
                // Overlap = red wireframe boundary
                VertexConsumer lineVc = bufferSource.getBuffer(RenderType.lines());
                drawAABBOutline(lineVc, poseStack.last(), ghostPos,
                        boundary.min(), boundary.max(), 255, 40, 40);
                bufferSource.endBatch(RenderType.lines());
            }
        }
    }

    private static BuildingSlot getSelectedSlot() {
        List<BuildingSlot> slots = ProjectionClientState.getBuildingSlots();
        int index = ProjectionClientState.getSelectedSlotIndex();
        if (slots.isEmpty() || index < 0 || index >= slots.size()) return null;
        return slots.get(index);
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Face highlight ──
    // ═══════════════════════════════════════════════════════════════

    /** Gold semi-transparent overlay for the currently selected block face. */
    private static final int FACE_HIGHLIGHT_R = 200;
    private static final int FACE_HIGHLIGHT_G = 170;
    private static final int FACE_HIGHLIGHT_B = 60;
    private static final int FACE_HIGHLIGHT_A = 80;
    /** Slight offset from the block face to avoid z-fighting. */
    private static final float FACE_EPSILON = 0.005f;

    /**
     * Render a gold semi-transparent quad on the currently selected face
     * of the hit block, so the player can see which face is the origin base.
     */
    private static void renderFaceHighlight(MultiBufferSource.BufferSource bufferSource,
                                            PoseStack poseStack) {
        BlockPos hitBlock = ProjectionClientState.getHitBlock();
        Direction face = ProjectionClientState.getSelectedFace();
        if (hitBlock == null || face == null) return;
        // Only show face highlight when projecting and ghost is valid
        if (!ProjectionClientState.isProjecting()) return;

        PoseStack.Pose pose = poseStack.last();
        VertexConsumer vc = bufferSource.getBuffer(RenderType.debugQuads());

        float bx = hitBlock.getX();
        float by = hitBlock.getY();
        float bz = hitBlock.getZ();
        float ep = FACE_EPSILON;
        float one = 1.0f;
        int r = FACE_HIGHLIGHT_R, g = FACE_HIGHLIGHT_G, b = FACE_HIGHLIGHT_B, a = FACE_HIGHLIGHT_A;

        switch (face) {
            case UP -> quad(vc, pose,
                    bx,      by + one + ep, bz,
                    bx,      by + one + ep, bz + one,
                    bx + one, by + one + ep, bz + one,
                    bx + one, by + one + ep, bz,      r, g, b, a);
            case DOWN -> quad(vc, pose,
                    bx,      by - ep, bz,
                    bx + one, by - ep, bz,
                    bx + one, by - ep, bz + one,
                    bx,      by - ep, bz + one,        r, g, b, a);
            case NORTH -> quad(vc, pose,
                    bx,      by,      bz - ep,
                    bx + one, by,      bz - ep,
                    bx + one, by + one, bz - ep,
                    bx,      by + one, bz - ep,         r, g, b, a);
            case SOUTH -> quad(vc, pose,
                    bx + one, by,      bz + one + ep,
                    bx,      by,      bz + one + ep,
                    bx,      by + one, bz + one + ep,
                    bx + one, by + one, bz + one + ep,  r, g, b, a);
            case WEST -> quad(vc, pose,
                    bx - ep, by,      bz,
                    bx - ep, by,      bz + one,
                    bx - ep, by + one, bz + one,
                    bx - ep, by + one, bz,              r, g, b, a);
            case EAST -> quad(vc, pose,
                    bx + one + ep, by,      bz + one,
                    bx + one + ep, by,      bz,
                    bx + one + ep, by + one, bz,
                    bx + one + ep, by + one, bz + one,  r, g, b, a);
        }

        bufferSource.endBatch(RenderType.debugQuads());
    }

    /** Emit a single quad with the given corner vertices. */
    private static void quad(VertexConsumer vc, PoseStack.Pose pose,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float x4, float y4, float z4,
                             int r, int g, int b, int a) {
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(pose, x2, y2, z2).setColor(r, g, b, a);
        vc.addVertex(pose, x3, y3, z3).setColor(r, g, b, a);
        vc.addVertex(pose, x4, y4, z4).setColor(r, g, b, a);
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Boundary wireframe ──
    // ═══════════════════════════════════════════════════════════════

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
