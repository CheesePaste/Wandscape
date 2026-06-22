package com.wsteam.wandscape.projection.client;

import java.util.List;

import org.slf4j.Logger;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.projection.data.BuildingSlot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * World-space rendering for soul projection mode.
 *
 * <p>Renders:
 * <ul>
 *   <li>Ghost building preview — wireframe outline + translucent top-face quads
 *       at the targeted block position, colored green (valid) or red (blocked).</li>
 *   <li>Body anchor meditation beam — translucent vertical pillar at the
 *       position where the player left their body.</li>
 * </ul>
 *
 * <p>Rendering patterns are reused from {@code RoadEditorRenderer}.
 */
public final class ProjectionRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Half-size of wireframe box per block. */
    private static final float BOX_HALF = 0.5f;
    /** Y offset above block surface for translucent face quads. */
    private static final float FACE_Y_OFFSET = 1.02f;
    /** Alpha values for ghost blocks (0–255). */
    private static final int GHOST_VALID_ALPHA = 100;
    private static final int GHOST_INVALID_ALPHA = 120;
    /** Beam dimensions. */
    private static final float BEAM_HALF = 0.25f;
    private static final float BEAM_HEIGHT = 3.0f;
    private static final int BEAM_ALPHA_BASE = 80;

    private static boolean registered = false;

    private ProjectionRenderer() {}

    // ── Registration ──

    public static void register() {
        if (registered) return;
        registered = true;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS
                .addListener(RenderLevelStageEvent.class, ProjectionRenderer::onRenderLevelStage);
        LOGGER.info("[Projection] Renderer registered");
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Render handler ──
    // ═══════════════════════════════════════════════════════════════

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!ProjectionClientState.isProjecting()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        // ── Camera-relative transform ──
        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        PoseStack.Pose poseEntry = poseStack.last();

        // 1. Ghost building preview
        renderGhostPreview(mc, bufferSource, poseEntry);

        // 2. Body anchor beam
        renderBodyBeam(bufferSource, poseEntry);

        poseStack.popPose();
    }

    // ── Ghost preview ──

    private static void renderGhostPreview(Minecraft mc, MultiBufferSource.BufferSource bufferSource,
                                           PoseStack.Pose poseEntry) {
        BlockPos ghostPos = ProjectionClientState.getGhostPos();
        if (ghostPos == null) return;

        List<BuildingSlot> slots = ProjectionClientState.getBuildingSlots();
        int index = ProjectionClientState.getSelectedSlotIndex();
        if (slots.isEmpty() || index < 0 || index >= slots.size()) return;

        BuildingSlot slot = slots.get(index);
        BuildingConfig config = BuildingConfigLoader.getInstance().get(slot.id());
        if (config == null) return;

        boolean overlap = ProjectionClientState.isOverlapDetected();
        int r, g, b;
        if (overlap) {
            r = 255; g = 60; b = 60; // red = invalid
        } else {
            r = 0; g = 255; b = 136; // green = valid
        }

        // 1a. Wireframe boxes for each pattern block
        VertexConsumer lineVc = bufferSource.getBuffer(RenderType.lines());
        for (BlockOffset offset : config.pattern()) {
            float cx = ghostPos.getX() + offset.x() + 0.5f;
            float cy = ghostPos.getY() + offset.y() + 0.5f;
            float cz = ghostPos.getZ() + offset.z() + 0.5f;
            drawWireframeBox(lineVc, poseEntry, cx, cy, cz, BOX_HALF, r, g, b);
        }
        bufferSource.endBatch(RenderType.lines());

        // 1b. Translucent top-face quads
        VertexConsumer quadVc = bufferSource.getBuffer(RenderType.debugQuads());
        int alpha = overlap ? GHOST_INVALID_ALPHA : GHOST_VALID_ALPHA;
        for (BlockOffset offset : config.pattern()) {
            float fx = ghostPos.getX() + offset.x() + 0.5f;
            float fy = ghostPos.getY() + offset.y() + FACE_Y_OFFSET;
            float fz = ghostPos.getZ() + offset.z() + 0.5f;
            drawMarkerSquare(quadVc, poseEntry, fx, fy, fz, BOX_HALF, r, g, b, alpha);
        }
        bufferSource.endBatch(RenderType.debugQuads());

        // 1c. Boundary box outline (if building has a boundary)
        var boundary = config.boundary();
        if (boundary != null) {
            VertexConsumer boundVc = bufferSource.getBuffer(RenderType.lines());
            drawAABBOutline(boundVc, poseEntry, ghostPos,
                    boundary.min(), boundary.max(),
                    255, 200, 100); // orange
            bufferSource.endBatch(RenderType.lines());
        }
    }

    // ── Body anchor beam ──

    private static void renderBodyBeam(MultiBufferSource.BufferSource bufferSource,
                                       PoseStack.Pose poseEntry) {
        BlockPos anchor = ProjectionClientState.getBodyAnchor();
        if (anchor == null) return;

        // Pulsing alpha: oscillate based on system time
        long timeMs = System.currentTimeMillis();
        float pulse = (float) Math.sin(timeMs * 0.005) * 0.4f + 0.6f;
        int alpha = (int) (BEAM_ALPHA_BASE * pulse);

        float cx = anchor.getX() + 0.5f;
        float cy = anchor.getY() + 0.1f;
        float cz = anchor.getZ() + 0.5f;
        float hw = BEAM_HALF;
        float top = cy + BEAM_HEIGHT;

        // Color: light purple (soul projection theme)
        int r = 170, g = 136, b = 255;

        VertexConsumer vc = bufferSource.getBuffer(RenderType.debugQuads());

        // 4 vertical faces
        // Front (+Z)
        vc.addVertex(poseEntry, cx - hw, cy, cz + hw).setColor(r, g, b, alpha);
        vc.addVertex(poseEntry, cx + hw, cy, cz + hw).setColor(r, g, b, alpha);
        vc.addVertex(poseEntry, cx + hw, top, cz + hw).setColor(r, g, b, alpha);
        vc.addVertex(poseEntry, cx - hw, top, cz + hw).setColor(r, g, b, alpha);
        // Back (-Z)
        vc.addVertex(poseEntry, cx + hw, cy, cz - hw).setColor(r, g, b, alpha);
        vc.addVertex(poseEntry, cx - hw, cy, cz - hw).setColor(r, g, b, alpha);
        vc.addVertex(poseEntry, cx - hw, top, cz - hw).setColor(r, g, b, alpha);
        vc.addVertex(poseEntry, cx + hw, top, cz - hw).setColor(r, g, b, alpha);
        // Left (-X)
        vc.addVertex(poseEntry, cx - hw, cy, cz - hw).setColor(r, g, b, alpha);
        vc.addVertex(poseEntry, cx - hw, cy, cz + hw).setColor(r, g, b, alpha);
        vc.addVertex(poseEntry, cx - hw, top, cz + hw).setColor(r, g, b, alpha);
        vc.addVertex(poseEntry, cx - hw, top, cz - hw).setColor(r, g, b, alpha);
        // Right (+X)
        vc.addVertex(poseEntry, cx + hw, cy, cz + hw).setColor(r, g, b, alpha);
        vc.addVertex(poseEntry, cx + hw, cy, cz - hw).setColor(r, g, b, alpha);
        vc.addVertex(poseEntry, cx + hw, top, cz - hw).setColor(r, g, b, alpha);
        vc.addVertex(poseEntry, cx + hw, top, cz + hw).setColor(r, g, b, alpha);

        // Top face
        vc.addVertex(poseEntry, cx - hw, top, cz - hw).setColor(r, g, b, alpha);
        vc.addVertex(poseEntry, cx + hw, top, cz - hw).setColor(r, g, b, alpha);
        vc.addVertex(poseEntry, cx + hw, top, cz + hw).setColor(r, g, b, alpha);
        vc.addVertex(poseEntry, cx - hw, top, cz + hw).setColor(r, g, b, alpha);

        bufferSource.endBatch(RenderType.debugQuads());
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Drawing helpers (reused from RoadEditorRenderer pattern) ──
    // ═══════════════════════════════════════════════════════════════

    /** Draw a wireframe box centered at (cx, cy, cz) with half-size. */
    private static void drawWireframeBox(VertexConsumer vc, PoseStack.Pose poseEntry,
                                          float cx, float cy, float cz,
                                          float half, int r, int g, int b) {
        float x0 = cx - half, x1 = cx + half;
        float y0 = cy - half, y1 = cy + half;
        float z0 = cz - half, z1 = cz + half;

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

    /** Draw a 1×1 square on the top face of a block. */
    private static void drawMarkerSquare(VertexConsumer vc, PoseStack.Pose poseEntry,
                                          float cx, float cy, float cz,
                                          float half, int r, int g, int b, int a) {
        vc.addVertex(poseEntry, cx - half, cy, cz - half).setColor(r, g, b, a);
        vc.addVertex(poseEntry, cx + half, cy, cz - half).setColor(r, g, b, a);
        vc.addVertex(poseEntry, cx + half, cy, cz + half).setColor(r, g, b, a);
        vc.addVertex(poseEntry, cx - half, cy, cz + half).setColor(r, g, b, a);
    }

    /** Draw a wireframe AABB outline around a boundary box relative to an anchor position. */
    private static void drawAABBOutline(VertexConsumer vc, PoseStack.Pose poseEntry,
                                         BlockPos anchor,
                                         BlockOffset min, BlockOffset max,
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
}
