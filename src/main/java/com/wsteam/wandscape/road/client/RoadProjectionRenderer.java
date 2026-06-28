package com.wsteam.wandscape.road.client;

import java.util.List;
import java.util.UUID;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wsteam.wandscape.core.road.PathGenerator;
import com.wsteam.wandscape.core.road.PathPoint;
import com.wsteam.wandscape.core.road.RoadEdge;
import com.wsteam.wandscape.core.road.RoadNetwork;
import com.wsteam.wandscape.core.road.RoadNode;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import com.wsteam.wandscape.shared.log.Log;

/**
 * World-space rendering for road projection mode.
 *
 * <p>Renders:
 * <ul>
 *   <li>Existing road network edges — color-coded by status (blue=PLANNED, orange=BUILDING, green=COMPLETE)</li>
 *   <li>Network nodes — wireframe boxes</li>
 *   <li>Body anchor beam — translucent purple pillar (same as building projection)</li>
 *   <li>Active start point marker — green square</li>
 *   <li>Preview line from active start to crosshair target — cyan road-face quads</li>
 *   <li>Queued/pending road segments — yellow/orange road-face quads</li>
 * </ul>
 */
public final class RoadProjectionRenderer {

    private static final String TAG = "RoadProjectionRenderer";

    /** Road half-width constants. */
    private static final float ROAD_HALF_WIDTH_BASE = 1.5f;
    private static final float PREVIEW_Y_OFFSET = 1.05f;
    private static final float ROAD_FACE_Y_OFFSET = 1.02f;
    private static final float NODE_Y_OFFSET = 0.5f;
    private static final float NODE_BOX_HALF = 0.25f;

    /** Alpha values for road face rendering (0-255). */
    private static final int ALPHA_PLANNED = 100;
    private static final int ALPHA_BUILDING = 120;
    private static final int ALPHA_COMPLETE = 80;

    /** Beam constants. */
    private static final float BEAM_HALF = 0.25f;
    private static final float BEAM_HEIGHT = 3.0f;
    private static final int BEAM_ALPHA_BASE = 80;

    /** Pending segment preview color: warm orange. */
    private static final int PENDING_R = 255, PENDING_G = 160, PENDING_B = 40, PENDING_A = 140;

    /** Active preview line color: cyan. */
    private static final int PREVIEW_R = 0, PREVIEW_G = 210, PREVIEW_B = 230, PREVIEW_A = 140;

    private static boolean registered = false;

    private RoadProjectionRenderer() {}

    // ── Registration ──

    public static void register() {
        if (registered) return;
        registered = true;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS
                .addListener(RenderLevelStageEvent.class, RoadProjectionRenderer::onRenderLevelStage);
        Log.info(TAG, "[RoadProjection] Renderer registered");
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Render handler ──
    // ═══════════════════════════════════════════════════════════════

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!RoadProjectionClientState.isProjecting()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        PoseStack.Pose poseEntry = poseStack.last();

        RoadNetwork network = RoadProjectionClientState.getCachedNetwork();

        // ── 1. Existing network edges ──
        renderNetworkEdges(bufferSource, poseEntry, network);

        // ── 2. Network nodes ──
        renderNodes(bufferSource, poseEntry, network);

        // ── 3. Pending/queued road segments ──
        renderPendingSegments(bufferSource, poseEntry);

        // ── 4. Active preview line ──
        renderActivePreview(bufferSource, poseEntry, mc);

        // ── 5. Body anchor beam ──
        renderBodyBeam(bufferSource, poseEntry);

        poseStack.popPose();
    }

    // ── Network edges ──

    private static void renderNetworkEdges(MultiBufferSource.BufferSource bufferSource,
                                           PoseStack.Pose poseEntry, RoadNetwork network) {
        VertexConsumer faceVc = bufferSource.getBuffer(RenderType.debugQuads());

        for (RoadEdge edge : network.getEdges().values()) {
            int r, g, b, a;
            switch (edge.getStatus()) {
                case COMPLETE -> { r = 30;  g = 200; b = 50;  a = ALPHA_COMPLETE;  }
                case BUILDING -> { r = 220; g = 180; b = 30;  a = ALPHA_BUILDING; }
                default        -> { r = 60;  g = 100; b = 240; a = ALPHA_PLANNED;  }
            }

            List<PathPoint> path = edge.getPath();
            renderPathAsRoadFace(faceVc, poseEntry, path,
                    ROAD_HALF_WIDTH_BASE, ROAD_FACE_Y_OFFSET, r, g, b, a);
        }

        bufferSource.endBatch(RenderType.debugQuads());
    }

    // ── Nodes ──

    private static void renderNodes(MultiBufferSource.BufferSource bufferSource,
                                    PoseStack.Pose poseEntry, RoadNetwork network) {
        VertexConsumer lineVc = bufferSource.getBuffer(RenderType.lines());

        for (RoadNode node : network.getNodes().values()) {
            float cr, cg, cb;
            switch (node.type()) {
                case BUILDING     -> { cr = 1.0f; cg = 1.0f; cb = 1.0f; }
                case INTERSECTION -> { cr = 0.6f; cg = 0.1f; cb = 1.0f; }
                case PLAYER       -> { cr = 0.7f; cg = 0.3f; cb = 1.0f; }
                default           -> { cr = 0.5f; cg = 0.5f; cb = 0.5f; }
            }
            drawNodeBox(lineVc, poseEntry,
                    node.pos().x() + 0.5f, node.pos().y() + NODE_Y_OFFSET, node.pos().z() + 0.5f,
                    NODE_BOX_HALF, (int) (cr * 255), (int) (cg * 255), (int) (cb * 255));
        }

        bufferSource.endBatch(RenderType.lines());
    }

    // ── Pending segments (queued, not yet published) ──

    private static void renderPendingSegments(MultiBufferSource.BufferSource bufferSource,
                                              PoseStack.Pose poseEntry) {
        List<RoadProjectionClientState.PendingSegment> segments =
                RoadProjectionClientState.getPendingSegments();
        if (segments.isEmpty()) return;

        VertexConsumer vc = bufferSource.getBuffer(RenderType.debugQuads());
        int amplitude = RoadProjectionClientState.getCurrentWidth() * 2;

        for (RoadProjectionClientState.PendingSegment seg : segments) {
            PathPoint startPt = new PathPoint(
                    seg.start().getX(), seg.start().getY(), seg.start().getZ());
            PathPoint endPt = new PathPoint(
                    seg.end().getX(), seg.end().getY(), seg.end().getZ());

            List<PathPoint> previewPath = PathGenerator.lShape3D(startPt, endPt, amplitude);
            if (!previewPath.isEmpty()) {
                float halfW = (seg.width() - 1) / 2.0f + 0.5f; // convert width to half-width
                renderPathAsRoadFace(vc, poseEntry, previewPath,
                        halfW, PREVIEW_Y_OFFSET, PENDING_R, PENDING_G, PENDING_B, PENDING_A);
            }

            // Draw small markers at start and end
            drawCornerSquare(vc, poseEntry,
                    seg.start().getX() + 0.5f, seg.start().getY() + PREVIEW_Y_OFFSET, seg.start().getZ() + 0.5f,
                    0.4f, PENDING_R, PENDING_G, PENDING_B, 200);
            drawCornerSquare(vc, poseEntry,
                    seg.end().getX() + 0.5f, seg.end().getY() + PREVIEW_Y_OFFSET, seg.end().getZ() + 0.5f,
                    0.4f, PENDING_R, PENDING_G, PENDING_B, 200);
        }

        bufferSource.endBatch(RenderType.debugQuads());
    }

    // ── Active preview (start point → crosshair) ──

    private static void renderActivePreview(MultiBufferSource.BufferSource bufferSource,
                                            PoseStack.Pose poseEntry, Minecraft mc) {
        BlockPos startPos = RoadProjectionClientState.getActiveStartPos();
        if (startPos == null) return;

        BlockPos ghostPos = RoadProjectionClientState.getEffectiveGhostPos();
        if (ghostPos == null) return;

        int amplitude = RoadProjectionClientState.getCurrentWidth() * 2;
        PathPoint startPt = new PathPoint(
                startPos.getX(), startPos.getY(), startPos.getZ());
        PathPoint endPt = new PathPoint(
                ghostPos.getX(), ghostPos.getY(), ghostPos.getZ());

        List<PathPoint> previewPath = PathGenerator.lShape3D(startPt, endPt, amplitude);
        if (previewPath.isEmpty()) return;

        VertexConsumer vc = bufferSource.getBuffer(RenderType.debugQuads());
        int width = RoadProjectionClientState.getCurrentWidth();
        float halfW = (width - 1) / 2.0f + 0.5f;

        // Cyan preview line
        renderPathAsRoadFace(vc, poseEntry, previewPath,
                halfW, PREVIEW_Y_OFFSET, PREVIEW_R, PREVIEW_G, PREVIEW_B, PREVIEW_A);

        // Green marker at start point
        drawCornerSquare(vc, poseEntry,
                startPos.getX() + 0.5f, startPos.getY() + PREVIEW_Y_OFFSET, startPos.getZ() + 0.5f,
                0.5f, 50, 255, 50, 220);

        // Yellow marker at end/ghost point
        drawCornerSquare(vc, poseEntry,
                ghostPos.getX() + 0.5f, ghostPos.getY() + PREVIEW_Y_OFFSET, ghostPos.getZ() + 0.5f,
                0.5f, 255, 220, 50, 220);

        bufferSource.endBatch(RenderType.debugQuads());
    }

    // ── Body anchor beam (same pattern as ProjectionRenderer) ──

    private static void renderBodyBeam(MultiBufferSource.BufferSource bufferSource,
                                       PoseStack.Pose poseEntry) {
        BlockPos anchor = RoadProjectionClientState.getBodyAnchor();
        if (anchor == null) return;

        long timeMs = System.currentTimeMillis();
        float pulse = (float) Math.sin(timeMs * 0.005) * 0.4f + 0.6f;
        int alpha = (int) (BEAM_ALPHA_BASE * pulse);

        float cx = anchor.getX() + 0.5f;
        float cy = anchor.getY() + 0.1f;
        float cz = anchor.getZ() + 0.5f;
        float hw = BEAM_HALF;
        float top = cy + BEAM_HEIGHT;
        // Warm amber beam for road projection (vs purple for building projection)
        int r = 255, g = 180, b = 60;

        VertexConsumer vc = bufferSource.getBuffer(RenderType.debugQuads());

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
        // Top
        vc.addVertex(poseEntry, cx - hw, top, cz - hw).setColor(r, g, b, alpha);
        vc.addVertex(poseEntry, cx + hw, top, cz - hw).setColor(r, g, b, alpha);
        vc.addVertex(poseEntry, cx + hw, top, cz + hw).setColor(r, g, b, alpha);
        vc.addVertex(poseEntry, cx - hw, top, cz + hw).setColor(r, g, b, alpha);

        bufferSource.endBatch(RenderType.debugQuads());
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Drawing utilities (shared with RoadEditorRenderer patterns) ──
    // ═══════════════════════════════════════════════════════════════

    /** Render a list of PathPoints as wide road-face quads. */
    private static void renderPathAsRoadFace(VertexConsumer vc, PoseStack.Pose poseEntry,
                                              List<PathPoint> path, float halfWidth,
                                              float yOffset, int r, int g, int b, int a) {
        int n = path.size();
        if (n < 2) {
            if (n == 1) {
                PathPoint p = path.get(0);
                drawCornerSquare(vc, poseEntry,
                        p.x() + 0.5f, p.y() + yOffset, p.z() + 0.5f,
                        halfWidth, r, g, b, a);
            }
            return;
        }

        int prevPerpDx = 0, prevPerpDz = 1;
        for (int i = 0; i < n - 1; i++) {
            PathPoint p1 = path.get(i);
            PathPoint p2 = path.get(i + 1);

            float ax = p1.x() + 0.5f;
            float ay = p1.y() + yOffset;
            float az = p1.z() + 0.5f;
            float bx = p2.x() + 0.5f;
            float by = p2.y() + yOffset;
            float bz = p2.z() + 0.5f;

            int perpDx = 0, perpDz = 0;
            boolean moveX = p1.x() != p2.x();
            boolean moveZ = p1.z() != p2.z();
            if (moveX && !moveZ) {
                perpDz = 1;
            } else if (moveZ && !moveX) {
                perpDx = 1;
            } else {
                perpDx = prevPerpDx;
                perpDz = prevPerpDz;
            }

            float hw = halfWidth;
            vc.addVertex(poseEntry, ax - perpDx * hw, ay, az - perpDz * hw).setColor(r, g, b, a);
            vc.addVertex(poseEntry, ax + perpDx * hw, ay, az + perpDz * hw).setColor(r, g, b, a);
            vc.addVertex(poseEntry, bx + perpDx * hw, by, bz + perpDz * hw).setColor(r, g, b, a);
            vc.addVertex(poseEntry, bx - perpDx * hw, by, bz - perpDz * hw).setColor(r, g, b, a);

            prevPerpDx = perpDx;
            prevPerpDz = perpDz;
        }

        for (PathPoint p : path) {
            drawCornerSquare(vc, poseEntry,
                    p.x() + 0.5f, p.y() + yOffset, p.z() + 0.5f,
                    halfWidth, r, g, b, a);
        }
    }

    private static void drawCornerSquare(VertexConsumer vc, PoseStack.Pose poseEntry,
                                         float cx, float cy, float cz,
                                         float half, int r, int g, int b, int a) {
        vc.addVertex(poseEntry, cx - half, cy, cz - half).setColor(r, g, b, a);
        vc.addVertex(poseEntry, cx + half, cy, cz - half).setColor(r, g, b, a);
        vc.addVertex(poseEntry, cx + half, cy, cz + half).setColor(r, g, b, a);
        vc.addVertex(poseEntry, cx - half, cy, cz + half).setColor(r, g, b, a);
    }

    private static void drawNodeBox(VertexConsumer vc, PoseStack.Pose poseEntry,
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
}
