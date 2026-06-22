package com.wsteam.wandscape.road.client;

import java.util.List;
import java.util.UUID;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.road.PathPoint;
import com.wsteam.wandscape.core.road.RoadEdge;
import com.wsteam.wandscape.core.road.RoadNetwork;
import com.wsteam.wandscape.core.road.RoadNode;
import com.wsteam.wandscape.road.network.RoadEdgeRemovePacket;
import com.wsteam.wandscape.road.network.RoadEdgePlanPacket;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * World-space rendering of the road network for the V1 road editor.
 *
 * <p>Draws edges as wide translucent quads on the road surface (color-coded by
 * status) and nodes as small wireframe boxes.  Performs crosshair-to-edge
 * hover detection on the client tick.
 *
 * <p><b>Coordinate conventions:</b> The pose stack is pushed and translated by
 * negative camera position so world-space vertex coordinates become camera-relative
 * — matching the vanilla entity / block-entity pattern.
 */
public final class RoadEditorRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Ray→segment distance threshold for edge hover (road half-width + margin). */
    private static final double HOVER_THRESHOLD = 2.8;
    /** Ray→point distance threshold for node hover. */
    private static final double NODE_HOVER_THRESHOLD = 3.0;
    /** Half-size of node wireframe boxes. */
    private static final float NODE_BOX_HALF = 0.25f;
    /** Road half-width in blocks (default 3-wide → 1.5 half). */
    private static final float ROAD_HALF_WIDTH = 1.5f;
    /** Y offset above the road surface block (block top + epsilon to avoid z-fighting). */
    private static final float ROAD_FACE_Y_OFFSET = 1.02f;
    /** Y offset for node boxes above the node position. */
    private static final float NODE_Y_OFFSET = 0.5f;

    /** Alpha values for road face rendering (0-255). */
    private static final int ALPHA_PLANNED = 100;
    private static final int ALPHA_BUILDING = 120;
    private static final int ALPHA_COMPLETE = 80;
    private static final int ALPHA_HOVERED = 180;

    // GLFW raw mouse state for click detection (consumeClick is already drained by
    // Minecraft's main tick by the time ClientTickEvent.Post fires).
    private static boolean wasLeftDown = false;
    private static boolean wasRightDown = false;

    private static boolean firstRenderLogged = false;
    private static int frameCounter = 0;

    private RoadEditorRenderer() {}

    // ── Registration ──

    public static void register() {
        LOGGER.info("[RoadEditor] register() — hooking RenderLevelStageEvent + ClientTickEvent(Pre+Post)");
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS
                .addListener(RenderLevelStageEvent.class, RoadEditorRenderer::onRenderLevelStage);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS
                .addListener(ClientTickEvent.Pre.class, RoadEditorRenderer::onClientTickPre);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS
                .addListener(ClientTickEvent.Post.class, RoadEditorRenderer::onClientTickPost);
        LOGGER.info("[RoadEditor] register() — done");
    }

    // ── World rendering ──

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        frameCounter++;
        boolean editing = RoadEditorClientState.isEditing();
        boolean isTripwire = event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS;

        if (frameCounter <= 5) {
            LOGGER.info("[RoadEditor] onRenderLevelStage frame={} stage={} editing={} isTripwire={}",
                    frameCounter, event.getStage(), editing, isTripwire);
        }
        if (editing && isTripwire && frameCounter % 200 == 0) {
            LOGGER.info("[RoadEditor] render heartbeat frame={} nodes={} edges={}",
                    frameCounter,
                    RoadEditorClientState.getCachedNetwork().nodeCount(),
                    RoadEditorClientState.getCachedNetwork().edgeCount());
        }

        if (!editing) return;
        if (!isTripwire) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        // ── Camera-relative setup ──
        // The GPU modelView at this stage has camera rotation but NOT translation.
        // We push the pose stack and translate by -cameraPos so world-space
        // vertices become camera-relative.
        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        PoseStack.Pose poseEntry = poseStack.last();

        RoadNetwork network = RoadEditorClientState.getCachedNetwork();
        UUID hoveredId = RoadEditorClientState.getHoveredEdgeId();

        if (!firstRenderLogged) {
            firstRenderLogged = true;
            LOGGER.info("[RoadEditor] RENDER START — nodes={} edges={} camPos={}",
                    network.nodeCount(), network.edgeCount(), camPos);
        }

        // ── Draw edge faces (wide quads on road surface) ──
        VertexConsumer faceVc = bufferSource.getBuffer(RenderType.debugQuads());

        for (RoadEdge edge : network.getEdges().values()) {
            boolean hovered = edge.getEdgeId().equals(hoveredId);
            int r, g, b, a;

            if (hovered) {
                r = 255; g = 40; b = 40; a = ALPHA_HOVERED;         // red highlight
            } else {
                switch (edge.getStatus()) {
                    case COMPLETE -> { r = 30;  g = 200; b = 50;  a = ALPHA_COMPLETE;  } // green
                    case BUILDING -> { r = 220; g = 180; b = 30;  a = ALPHA_BUILDING; } // amber
                    default        -> { r = 60;  g = 100; b = 240; a = ALPHA_PLANNED;  } // blue
                }
            }

            List<PathPoint> path = edge.getPath();
            int n = path.size();
            if (n < 2) {
                // Single-point edge — draw just a corner square
                PathPoint p = path.get(0);
                drawCornerSquare(faceVc, poseEntry,
                        p.x() + 0.5f, p.y() + ROAD_FACE_Y_OFFSET, p.z() + 0.5f,
                        ROAD_HALF_WIDTH, r, g, b, a);
                continue;
            }

            int prevPerpDx = 0, prevPerpDz = 1;

            for (int i = 0; i < n - 1; i++) {
                PathPoint p1 = path.get(i);
                PathPoint p2 = path.get(i + 1);

                float ax = p1.x() + 0.5f;
                float ay = p1.y() + ROAD_FACE_Y_OFFSET;
                float az = p1.z() + 0.5f;
                float bx = p2.x() + 0.5f;
                float by = p2.y() + ROAD_FACE_Y_OFFSET;
                float bz = p2.z() + 0.5f;

                // Compute perpendicular direction (matches RoadBuilder logic)
                int perpDx = 0, perpDz = 0;
                boolean moveX = p1.x() != p2.x();
                boolean moveZ = p1.z() != p2.z();
                if (moveX && !moveZ) {
                    perpDz = 1;
                } else if (moveZ && !moveX) {
                    perpDx = 1;
                } else {
                    // Diagonal or no movement — carry forward previous perpendicular
                    perpDx = prevPerpDx;
                    perpDz = prevPerpDz;
                }

                float hw = ROAD_HALF_WIDTH;
                // Quad vertices (QUADS order around the face, counter-clockwise from above)
                faceVc.addVertex(poseEntry, ax - perpDx * hw, ay, az - perpDz * hw).setColor(r, g, b, a);
                faceVc.addVertex(poseEntry, ax + perpDx * hw, ay, az + perpDz * hw).setColor(r, g, b, a);
                faceVc.addVertex(poseEntry, bx + perpDx * hw, by, bz + perpDz * hw).setColor(r, g, b, a);
                faceVc.addVertex(poseEntry, bx - perpDx * hw, by, bz - perpDz * hw).setColor(r, g, b, a);

                prevPerpDx = perpDx;
                prevPerpDz = perpDz;
            }

            // Draw corner squares at every path point to fill gaps at turns
            for (PathPoint p : path) {
                drawCornerSquare(faceVc, poseEntry,
                        p.x() + 0.5f, p.y() + ROAD_FACE_Y_OFFSET, p.z() + 0.5f,
                        ROAD_HALF_WIDTH, r, g, b, a);
            }
        }

        bufferSource.endBatch(RenderType.debugQuads());

        // ── Draw nodes (wireframe boxes) ──
        VertexConsumer lineVc = bufferSource.getBuffer(RenderType.lines());

        for (RoadNode node : network.getNodes().values()) {
            float cr, cg, cb;
            switch (node.type()) {
                case BUILDING     -> { cr = 1.0f; cg = 1.0f; cb = 1.0f; }
                case INTERSECTION -> { cr = 0.6f; cg = 0.1f; cb = 1.0f; }
                default           -> { cr = 0.5f; cg = 0.5f; cb = 0.5f; }
            }
            drawNodeBox(lineVc, poseEntry,
                    node.pos().x() + 0.5f, node.pos().y() + NODE_Y_OFFSET, node.pos().z() + 0.5f,
                    NODE_BOX_HALF, (int) (cr * 255), (int) (cg * 255), (int) (cb * 255));
        }

        bufferSource.endBatch(RenderType.lines());
        poseStack.popPose();
    }

    /** Draw a flat square on the road surface at a corner point. */
    private static void drawCornerSquare(VertexConsumer vc, PoseStack.Pose poseEntry,
                                         float cx, float cy, float cz,
                                         float half, int r, int g, int b, int a) {
        vc.addVertex(poseEntry, cx - half, cy, cz - half).setColor(r, g, b, a);
        vc.addVertex(poseEntry, cx + half, cy, cz - half).setColor(r, g, b, a);
        vc.addVertex(poseEntry, cx + half, cy, cz + half).setColor(r, g, b, a);
        vc.addVertex(poseEntry, cx - half, cy, cz + half).setColor(r, g, b, a);
    }

    /** Draw a small wireframe box for a node. */
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

    // ── Client tick: hover + input ──

    /** Pre-tick: consume MC key mappings when hovering an edge so the vanilla
     *  attack / use handling doesn't also fire. */
    static void onClientTickPre(ClientTickEvent.Pre event) {
        if (!RoadEditorClientState.isEditing()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null) return;

        UUID hovered = RoadEditorClientState.getHoveredEdgeId();
        if (hovered != null) {
            // Drain the attack key so MC doesn't break blocks / swing arm
            while (mc.options.keyAttack.consumeClick()) {
                // consumed — prevents vanilla processing
            }
        }
    }

    static void onClientTickPost(ClientTickEvent.Post event) {
        if (!RoadEditorClientState.isEditing()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null) return;

        if (frameCounter == 0) {
            LOGGER.info("[RoadEditor] FIRST TICK — editing={} network nodes={} edges={}",
                    RoadEditorClientState.isEditing(),
                    RoadEditorClientState.getCachedNetwork().nodeCount(),
                    RoadEditorClientState.getCachedNetwork().edgeCount());
        }

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        Vec3 lookVec = new Vec3(camera.getLookVector().x(),
                camera.getLookVector().y(),
                camera.getLookVector().z());

        RoadNetwork network = RoadEditorClientState.getCachedNetwork();

        // ── Hover detection on edges ──
        UUID bestEdgeId = null;
        double bestDist = HOVER_THRESHOLD;

        for (RoadEdge edge : network.getEdges().values()) {
            List<PathPoint> path = edge.getPath();
            for (int i = 0; i < path.size() - 1; i++) {
                PathPoint p1 = path.get(i);
                PathPoint p2 = path.get(i + 1);
                Vec3 a = new Vec3(p1.x() + 0.5, p1.y() + 0.5, p1.z() + 0.5);
                Vec3 b = new Vec3(p2.x() + 0.5, p2.y() + 0.5, p2.z() + 0.5);
                double dist = rayToSegmentDist(camPos, lookVec, a, b);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestEdgeId = edge.getEdgeId();
                }
            }
        }
        RoadEditorClientState.setHoveredEdgeId(bestEdgeId);

        // ── Raw mouse button rising-edge detection ──
        // can't use mc.options.keyAttack.consumeClick() here — by ClientTickEvent.Post
        // Minecraft has already drained the click.  Read GLFW state directly.
        long window = mc.getWindow().getWindow();
        boolean leftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean rightDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        boolean leftClicked = leftDown && !wasLeftDown;
        boolean rightClicked = rightDown && !wasRightDown;
        wasLeftDown = leftDown;
        wasRightDown = rightDown;

        // ── Left-click: remove hovered edge ──
        if (bestEdgeId != null && leftClicked) {
            PacketDistributor.sendToServer(new RoadEdgeRemovePacket(bestEdgeId));
            RoadEditorClientState.setHoveredEdgeId(null);
        }

        // ── Right-click: path planning ──
        if (rightClicked) {
            RoadNode nearest = findNearestNodeToCrosshair(network, camPos, lookVec);
            if (nearest != null) {
                UUID selected = RoadEditorClientState.getSelectedFromNodeId();
                if (selected == null) {
                    RoadEditorClientState.setSelectedFromNodeId(nearest.nodeId());
                    mc.player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(
                                    "§7[RoadEditor] §eSelected node §f"
                                            + nearest.nodeId().toString().substring(0, 8)
                                            + " §e— right-click another node to plan path"),
                            true);
                } else if (!selected.equals(nearest.nodeId())) {
                    PacketDistributor.sendToServer(
                            new RoadEdgePlanPacket(selected, nearest.nodeId()));
                    mc.player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(
                                    "§7[RoadEditor] §ePath planning requested between selected nodes"),
                            true);
                    RoadEditorClientState.clearSelection();
                } else {
                    RoadEditorClientState.clearSelection();
                }
            }
        }
    }

    // ── Raycasting ──

    private static double rayToSegmentDist(Vec3 rayOrigin, Vec3 rayDir,
                                            Vec3 segA, Vec3 segB) {
        Vec3 segVec = segB.subtract(segA);
        double segLenSq = segVec.lengthSqr();
        if (segLenSq < 1e-9) {
            return rayToPointDist(rayOrigin, rayDir, segA);
        }

        Vec3 w0 = rayOrigin.subtract(segA);
        double a = rayDir.dot(rayDir);
        double b = rayDir.dot(segVec);
        double c = segVec.dot(segVec);
        double d = rayDir.dot(w0);
        double e = segVec.dot(w0);

        double denom = a * c - b * b;
        if (Math.abs(denom) < 1e-9) {
            double tSeg = Math.max(0, Math.min(1, -e / c));
            Vec3 segPt = segA.add(segVec.scale(tSeg));
            return rayToPointDist(rayOrigin, rayDir, segPt);
        }

        double t = Math.max(0, (b * e - c * d) / denom);
        double u = Math.max(0, Math.min(1, (a * e - b * d) / denom));

        Vec3 rayPt = rayOrigin.add(rayDir.scale(t));
        Vec3 segPt = segA.add(segVec.scale(u));
        return rayPt.distanceTo(segPt);
    }

    private static double rayToPointDist(Vec3 rayOrigin, Vec3 rayDir, Vec3 point) {
        Vec3 toPoint = point.subtract(rayOrigin);
        double t = toPoint.dot(rayDir);
        if (t <= 0) return rayOrigin.distanceTo(point);
        return rayOrigin.add(rayDir.scale(t)).distanceTo(point);
    }

    private static RoadNode findNearestNodeToCrosshair(RoadNetwork network,
                                                        Vec3 camPos, Vec3 lookVec) {
        RoadNode best = null;
        double bestDist = NODE_HOVER_THRESHOLD;
        for (RoadNode node : network.getNodes().values()) {
            Vec3 np = new Vec3(node.pos().x() + 0.5, node.pos().y() + 0.5, node.pos().z() + 0.5);
            double dist = rayToPointDist(camPos, lookVec, np);
            if (dist < bestDist) {
                bestDist = dist;
                best = node;
            }
        }
        return best;
    }
}
