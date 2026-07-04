package com.wsteam.wandscape.road.client;

import java.util.List;
import java.util.UUID;

import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wsteam.wandscape.road.algorithm.PathGenerator;
import com.wsteam.wandscape.road.core.PathPoint;
import com.wsteam.wandscape.road.core.RoadEdge;
import com.wsteam.wandscape.road.core.RoadNetwork;
import com.wsteam.wandscape.road.core.RoadNode;
import com.wsteam.wandscape.road.network.RoadEdgeRemovePacket;
import com.wsteam.wandscape.road.network.RoadEdgePlanPacket;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import com.wsteam.wandscape.shared.log.Log;

/**
 * World-space rendering of the road network for the V1 road editor.
 *
 * <p>Draws edges as wide translucent quads on the road surface (color-coded by
 * status), nodes as wireframe boxes, and path-planning preview markers.
 * Performs crosshair-to-edge hover detection and path-planning input handling.
 *
 * <p><b>Controls (in edit mode):</b>
 * <pre>
 *   Left-click  — remove hovered edge
 *   Right-click — path planning: select start node, add waypoints, or select end node
 *   Backspace   — remove last waypoint
 *   Escape      — cancel path planning
 * </pre>
 *
 * <p><b>Path planning flow:</b>
 * Right-click node → start. Right-click ground → waypoint.
 * Right-click another node → send plan packet. Right-click same node → cancel.
 */
public final class RoadEditorRenderer {

    private static final String TAG = "RoadEditorRenderer";

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
    /** Y offset for waypoint markers and preview line. */
    private static final float PREVIEW_Y_OFFSET = 1.05f;

    /** Alpha values for road face rendering (0-255). */
    private static final int ALPHA_PLANNED = 100;
    private static final int ALPHA_BUILDING = 120;
    private static final int ALPHA_COMPLETE = 80;
    private static final int ALPHA_HOVERED = 180;

    // ── Raw input state ──

    // Mouse (GLFW raw — consumeClick is already drained by Post)
    private static boolean wasLeftDown = false;
    private static boolean wasRightDown = false;

    // Keyboard (for Backspace / Escape / Enter)
    private static boolean wasBackspaceDown = false;
    private static boolean wasEscapeDown = false;
    private static boolean wasEnterDown = false;

    private static boolean firstRenderLogged = false;
    private static int frameCounter = 0;

    private RoadEditorRenderer() {}

    // ── Registration ──

    public static void register() {
        Log.info(TAG, "[RoadEditor] register() — hooking RenderLevelStageEvent + ClientTickEvent(Pre+Post) + MouseScrolling");
        var bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        bus.addListener(RenderLevelStageEvent.class, RoadEditorRenderer::onRenderLevelStage);
        bus.addListener(ClientTickEvent.Pre.class, RoadEditorRenderer::onClientTickPre);
        bus.addListener(ClientTickEvent.Post.class, RoadEditorRenderer::onClientTickPost);
        bus.addListener(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent.class,
                RoadEditorRenderer::onMouseScroll);
        Log.info(TAG, "[RoadEditor] register() — done");
    }

    // ═══════════════════════════════════════════════════════════════
    // ── World rendering ──
    // ═══════════════════════════════════════════════════════════════

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        frameCounter++;
        boolean editing = RoadEditorClientState.isEditing();
        boolean isTripwire = event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS;

        if (frameCounter <= 5) {
            Log.info(TAG, "[RoadEditor] onRenderLevelStage frame={} stage={} editing={} isTripwire={}",
                    frameCounter, event.getStage(), editing, isTripwire);
        }
        if (editing && isTripwire && frameCounter % 200 == 0) {
            Log.info(TAG, "[RoadEditor] render heartbeat frame={} nodes={} edges={}",
                    frameCounter,
                    RoadEditorClientState.getCachedNetwork().nodeCount(),
                    RoadEditorClientState.getCachedNetwork().edgeCount());
        }

        if (!editing) return;
        if (!isTripwire) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        // ── Camera-relative setup ──
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
            Log.info(TAG, "[RoadEditor] RENDER START — nodes={} edges={} camPos={}",
                    network.nodeCount(), network.edgeCount(), camPos);
        }

        // ── Draw edge faces (wide quads on road surface) ──
        VertexConsumer faceVc = bufferSource.getBuffer(RenderType.debugQuads());

        for (RoadEdge edge : network.getEdges().values()) {
            boolean hovered = edge.getEdgeId().equals(hoveredId);
            int r, g, b, a;

            if (hovered) {
                r = 255; g = 40; b = 40; a = ALPHA_HOVERED;
            } else {
                switch (edge.getStatus()) {
                    case COMPLETE -> { r = 30;  g = 200; b = 50;  a = ALPHA_COMPLETE;  }
                    case BUILDING -> { r = 220; g = 180; b = 30;  a = ALPHA_BUILDING; }
                    default        -> { r = 60;  g = 100; b = 240; a = ALPHA_PLANNED;  }
                }
            }

            List<PathPoint> path = edge.getPath();
            int n = path.size();
            if (n < 2) {
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

                float hw = ROAD_HALF_WIDTH;
                faceVc.addVertex(poseEntry, ax - perpDx * hw, ay, az - perpDz * hw).setColor(r, g, b, a);
                faceVc.addVertex(poseEntry, ax + perpDx * hw, ay, az + perpDz * hw).setColor(r, g, b, a);
                faceVc.addVertex(poseEntry, bx + perpDx * hw, by, bz + perpDz * hw).setColor(r, g, b, a);
                faceVc.addVertex(poseEntry, bx - perpDx * hw, by, bz - perpDz * hw).setColor(r, g, b, a);

                prevPerpDx = perpDx;
                prevPerpDz = perpDz;
            }

            for (PathPoint p : path) {
                drawCornerSquare(faceVc, poseEntry,
                        p.x() + 0.5f, p.y() + ROAD_FACE_Y_OFFSET, p.z() + 0.5f,
                        ROAD_HALF_WIDTH, r, g, b, a);
            }
        }

        bufferSource.endBatch(RenderType.debugQuads());

        // ── Preview: planned path rendering ──
        UUID startId = RoadEditorClientState.getStartNodeId();
        if (startId != null) {
            RoadNode startNode = network.getNode(startId);
            List<BlockPos> wps = RoadEditorClientState.getWaypoints();
            UUID endId = RoadEditorClientState.getEndNodeId();

            // Build the full preview path: start → waypoints → (end node or player position)
            // Resolve start position: prefer network node, fall back to stored startNodePos
            PathPoint startPt = null;
            if (startNode != null) {
                startPt = new PathPoint(
                        startNode.pos().x(), startNode.pos().y(), startNode.pos().z());
            } else {
                BlockPos sp = RoadEditorClientState.getStartNodePos();
                if (sp != null) {
                    startPt = new PathPoint(sp.getX(), sp.getY(), sp.getZ());
                }
            }

            PathPoint target;
            if (endId != null) {
                RoadNode endNode = network.getNode(endId);
                if (endNode != null) {
                    target = new PathPoint(endNode.pos().x(), endNode.pos().y(), endNode.pos().z());
                } else {
                    // Dumb node might not be in cached network after sync
                    BlockPos ep = RoadEditorClientState.getEndNodePos();
                    if (ep != null) {
                        target = new PathPoint(ep.getX(), ep.getY(), ep.getZ());
                    } else {
                        target = null;
                    }
                }
            } else {
                // No end node yet — use player's feet position as preview target
                if (mc.player != null) {
                    target = new PathPoint(
                            (int) Math.floor(mc.player.getX()),
                            (int) Math.floor(mc.player.getY()),
                            (int) Math.floor(mc.player.getZ()));
                } else {
                    target = null;
                }
            }

            if (startPt != null && target != null) {
                List<PathPoint> previewPath = new java.util.ArrayList<>();
                int amplitude = RoadEditorClientState.getCurrentWidth() * 2;

                PathPoint cursor = startPt;
                for (BlockPos wp : wps) {
                    PathPoint wpPt = new PathPoint(wp.getX(), wp.getY(), wp.getZ());
                    previewPath.addAll(PathGenerator.lShape3D(cursor, wpPt, amplitude));
                    cursor = wpPt;
                }
                previewPath.addAll(PathGenerator.lShape3D(cursor, target, amplitude));

                if (!previewPath.isEmpty()) {
                    VertexConsumer previewFc = bufferSource.getBuffer(RenderType.debugQuads());
                    // Preview color: cyan (0,200,220) with alpha 130
                    renderPathAsRoadFace(previewFc, poseEntry, previewPath,
                            0, 200, 220, 130);
                    bufferSource.endBatch(RenderType.debugQuads());
                }
            }

            // Waypoint markers: yellow 1×1 squares
            VertexConsumer markerVc = bufferSource.getBuffer(RenderType.debugQuads());
            for (BlockPos wp : wps) {
                drawMarkerSquare(markerVc, poseEntry,
                        wp.getX() + 0.5f, wp.getY() + PREVIEW_Y_OFFSET, wp.getZ() + 0.5f,
                        0.5f, 255, 220, 50, 200);
            }
            // Start node marker: green 1×1 square
            BlockPos sPos = RoadEditorClientState.getStartNodePos();
            float sx, sy, sz;
            if (startNode != null) {
                sx = startNode.pos().x() + 0.5f;
                sy = startNode.pos().y() + PREVIEW_Y_OFFSET;
                sz = startNode.pos().z() + 0.5f;
            } else if (sPos != null) {
                sx = sPos.getX() + 0.5f;
                sy = sPos.getY() + PREVIEW_Y_OFFSET;
                sz = sPos.getZ() + 0.5f;
            } else {
                sx = sy = sz = 0; // unreachable
            }
            drawMarkerSquare(markerVc, poseEntry, sx, sy, sz,
                    0.5f, 50, 255, 50, 200);
            if (endId != null) {
                RoadNode endNode = network.getNode(endId);
                BlockPos ePos = RoadEditorClientState.getEndNodePos();
                float ex, ey, ez;
                if (endNode != null) {
                    ex = endNode.pos().x() + 0.5f;
                    ey = endNode.pos().y() + PREVIEW_Y_OFFSET;
                    ez = endNode.pos().z() + 0.5f;
                } else if (ePos != null) {
                    ex = ePos.getX() + 0.5f;
                    ey = ePos.getY() + PREVIEW_Y_OFFSET;
                    ez = ePos.getZ() + 0.5f;
                } else {
                    ex = ey = ez = 0; // unreachable
                }
                drawMarkerSquare(markerVc, poseEntry, ex, ey, ez,
                        0.5f, 255, 50, 50, 200); // red marker for end node
            }
            bufferSource.endBatch(RenderType.debugQuads());
        }

        // ── Draw nodes (wireframe boxes) ──
        VertexConsumer lineVc = bufferSource.getBuffer(RenderType.lines());

        for (RoadNode node : network.getNodes().values()) {
            float cr, cg, cb;
            boolean isStart = node.nodeId().equals(startId);
            if (isStart) {
                cr = 0.2f; cg = 1.0f; cb = 0.2f; // bright green for selected start
            } else {
                switch (node.type()) {
                    case BUILDING     -> { cr = 1.0f; cg = 1.0f; cb = 1.0f; }
                    case INTERSECTION -> { cr = 0.6f; cg = 0.1f; cb = 1.0f; }
                    case PLAYER       -> { cr = 0.7f; cg = 0.3f; cb = 1.0f; } // purple
                    default           -> { cr = 0.5f; cg = 0.5f; cb = 0.5f; }
                }
            }
            drawNodeBox(lineVc, poseEntry,
                    node.pos().x() + 0.5f, node.pos().y() + NODE_Y_OFFSET, node.pos().z() + 0.5f,
                    NODE_BOX_HALF, (int) (cr * 255), (int) (cg * 255), (int) (cb * 255));
        }

        bufferSource.endBatch(RenderType.lines());
        poseStack.popPose();
    }

    /** Draw a 1×1 marker square for waypoints and start node. */
    private static void drawMarkerSquare(VertexConsumer vc, PoseStack.Pose poseEntry,
                                         float cx, float cy, float cz,
                                         float half, int r, int g, int b, int a) {
        vc.addVertex(poseEntry, cx - half, cy, cz - half).setColor(r, g, b, a);
        vc.addVertex(poseEntry, cx + half, cy, cz - half).setColor(r, g, b, a);
        vc.addVertex(poseEntry, cx + half, cy, cz + half).setColor(r, g, b, a);
        vc.addVertex(poseEntry, cx - half, cy, cz + half).setColor(r, g, b, a);
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

    // ═══════════════════════════════════════════════════════════════
    // ── Client tick: hover + input ──
    // ═══════════════════════════════════════════════════════════════

    /** Mouse scroll in edit mode: adjust road width. */
    static void onMouseScroll(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent event) {
        if (!RoadEditorClientState.isEditing()) return;
        event.setCanceled(true);
        int delta = event.getScrollDeltaY() > 0 ? 1 : -1;
        RoadEditorClientState.adjustWidth(delta);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                            "§7[RoadEditor] Width: §f" + RoadEditorClientState.getCurrentWidth()),
                    true);
        }
    }

    /** Pre-tick: consume MC key mappings when hovering an edge / in path-planning mode
     *  so the vanilla attack / use handling doesn't also fire. */
    static void onClientTickPre(ClientTickEvent.Pre event) {
        if (!RoadEditorClientState.isEditing()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null) return;

        UUID hovered = RoadEditorClientState.getHoveredEdgeId();
        if (hovered != null) {
            while (mc.options.keyAttack.consumeClick()) { /* prevent vanilla attack */ }
        }

        // Also drain use key when in path-planning mode (prevents vanilla interact)
        if (RoadEditorClientState.getStartNodeId() != null) {
            while (mc.options.keyUse.consumeClick()) { /* prevent vanilla interact */ }
        }
    }

    static void onClientTickPost(ClientTickEvent.Post event) {
        if (!RoadEditorClientState.isEditing()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null) return;

        if (frameCounter == 0) {
            Log.info(TAG, "[RoadEditor] FIRST TICK — editing={} network nodes={} edges={}",
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

        // ── Raw input: mouse + keyboard ──
        long window = mc.getWindow().getWindow();
        boolean leftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean rightDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        boolean backspaceDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_BACKSPACE) == GLFW.GLFW_PRESS;
        boolean escapeDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        boolean enterDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ENTER) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_KP_ENTER) == GLFW.GLFW_PRESS;

        boolean leftClicked = leftDown && !wasLeftDown;
        boolean rightClicked = rightDown && !wasRightDown;
        boolean backspaceClicked = backspaceDown && !wasBackspaceDown;
        boolean escapeClicked = escapeDown && !wasEscapeDown;
        boolean enterClicked = enterDown && !wasEnterDown;

        wasLeftDown = leftDown;
        wasRightDown = rightDown;
        wasBackspaceDown = backspaceDown;
        wasEscapeDown = escapeDown;
        wasEnterDown = enterDown;

        // ── Left-click: remove hovered edge ──
        if (bestEdgeId != null && leftClicked) {
            PacketDistributor.sendToServer(new RoadEdgeRemovePacket(bestEdgeId));
            RoadEditorClientState.setHoveredEdgeId(null);
        }

        // ── Right-click: start node, waypoint, or pend end node ──
        if (rightClicked) {
            onRightClick(mc, network, camPos, lookVec);
        }

        // ── Enter: confirm path plan ──
        if (enterClicked) {
            UUID sId = RoadEditorClientState.getStartNodeId();
            UUID eId = RoadEditorClientState.getEndNodeId();
            BlockPos sPos = RoadEditorClientState.getStartNodePos();
            BlockPos ePos = RoadEditorClientState.getEndNodePos();
            if (sId != null && eId != null && sPos != null && ePos != null) {
                List<BlockPos> wps = RoadEditorClientState.getWaypoints();
                int width = RoadEditorClientState.getCurrentWidth();
                PacketDistributor.sendToServer(
                        new RoadEdgePlanPacket(sId, sPos, eId, ePos, wps, true, width));
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(
                                    "§7[RoadEditor] §aPath confirmed — §f" +
                                            wps.size() + " §awaypoints, from §f" +
                                            sId.toString().substring(0, 8) +
                                            " §ato §f" + eId.toString().substring(0, 8)),
                            true);
                }
                RoadEditorClientState.clearSelection();
            }
        }

        // ── Backspace: undo (end node → last waypoint → start node) ──
        if (backspaceClicked) {
            UUID sId = RoadEditorClientState.getStartNodeId();
            if (sId != null) {
                if (RoadEditorClientState.getEndNodeId() != null) {
                    // Undo end node selection first
                    RoadEditorClientState.setEndNodeId(null);
                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(
                                        "§7[RoadEditor] §eEnd node deselected — select another node or press Enter"),
                                true);
                    }
                } else if (RoadEditorClientState.waypointCount() > 0) {
                    RoadEditorClientState.removeLastWaypoint();
                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(
                                        "§7[RoadEditor] §eWaypoint removed — §f" +
                                                RoadEditorClientState.waypointCount() + " §eremaining"),
                                true);
                    }
                } else {
                    // No waypoints and no end node — cancel entire selection
                    RoadEditorClientState.clearSelection();
                    if (mc.player != null) {
                        mc.player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(
                                        "§7[RoadEditor] §ePath planning cancelled"),
                                true);
                    }
                }
            }
        }

        // ── Escape: cancel everything ──
        if (escapeClicked && RoadEditorClientState.getStartNodeId() != null) {
            RoadEditorClientState.clearSelection();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "§7[RoadEditor] §ePath planning cancelled"),
                        true);
            }
        }
    }

    /** Handle right-click: start node (real or dumb), waypoint, or end node. */
    private static void onRightClick(Minecraft mc, RoadNetwork network,
                                      Vec3 camPos, Vec3 lookVec) {
        RoadNode nearestNode = findNearestNodeToCrosshair(network, camPos, lookVec);
        UUID startId = RoadEditorClientState.getStartNodeId();
        UUID endId = RoadEditorClientState.getEndNodeId();

        if (nearestNode != null) {
            // ── Hit a real network node ──
            if (startId == null) {
                // First selection: set as start
                RoadEditorClientState.setStartNodeId(nearestNode.nodeId());
                mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "§7[RoadEditor] §aStart node §f"
                                        + nearestNode.nodeId().toString().substring(0, 8)
                                        + " §aselected — right-click ground for waypoints, another node/ground to finish"),
                        true);
            } else if (startId.equals(nearestNode.nodeId())) {
                // Same node → cancel
                RoadEditorClientState.clearSelection();
                mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "§7[RoadEditor] §eSelection cancelled"),
                        true);
            } else {
                // Different node → pend as end node
                RoadEditorClientState.setEndNodeId(nearestNode.nodeId());
                int wpCount = RoadEditorClientState.waypointCount();
                mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "§7[RoadEditor] §6End node §f"
                                        + nearestNode.nodeId().toString().substring(0, 8)
                                        + " §6pending — §f" + wpCount + " §6waypoints"
                                        + " §a[Enter] §6to confirm, §c[Esc] §6to cancel"),
                        true);
            }
        } else {
            // ── Hit ground (not a real node) ──
            HitResult hit = mc.hitResult;
            BlockPos hitPos = null;
            if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                hitPos = ((BlockHitResult) hit).getBlockPos();
            }

            if (startId == null) {
                // No start yet → create dumb PLAYER node at clicked ground
                if (hitPos != null) {
                    UUID id = RoadEditorClientState.setStartNodeAtPos(hitPos);
                    mc.player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(
                                    "§7[RoadEditor] §aDumb start node §f"
                                            + id.toString().substring(0, 8)
                                            + " §aat §f(" + hitPos.getX() + ", " + hitPos.getY() + ", " + hitPos.getZ() + ")"
                                            + " §a— right-click for waypoints, right-click another spot to finish"),
                            true);
                }
            } else if (endId != null) {
                // Already have start + end → clear end and add waypoint
                RoadEditorClientState.setEndNodeId(null);
                RoadEditorClientState.setEndNodePos(null);
                if (hitPos != null) {
                    RoadEditorClientState.addWaypoint(hitPos);
                    mc.player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(
                                    "§7[RoadEditor] §eWaypoint §f#" +
                                            RoadEditorClientState.waypointCount() +
                                            " §eat §f(" + hitPos.getX() + ", " + hitPos.getY() + ", " + hitPos.getZ() + ")"),
                            true);
                }
            } else {
                // Have start, no end → create dumb PLAYER end node
                if (hitPos != null) {
                    UUID id = RoadEditorClientState.setEndNodeAtPos(hitPos);
                    int wpCount = RoadEditorClientState.waypointCount();
                    mc.player.displayClientMessage(
                            net.minecraft.network.chat.Component.literal(
                                    "§7[RoadEditor] §6Dumb end node §f"
                                            + id.toString().substring(0, 8)
                                            + " §6at §f(" + hitPos.getX() + ", " + hitPos.getY() + ", " + hitPos.getZ() + ")"
                                            + " §6— §f" + wpCount + " §6waypoints"
                                            + " §a[Enter] §6to confirm, §c[Esc] §6to cancel"),
                            true);
                }
            }
        }
    }

    /** Render a list of PathPoints as wide road-face quads (same pattern as edge rendering). */
    private static void renderPathAsRoadFace(VertexConsumer vc, PoseStack.Pose poseEntry,
                                              List<PathPoint> path,
                                              int r, int g, int b, int a) {
        int n = path.size();
        if (n < 2) {
            if (n == 1) {
                PathPoint p = path.get(0);
                drawCornerSquare(vc, poseEntry,
                        p.x() + 0.5f, p.y() + PREVIEW_Y_OFFSET, p.z() + 0.5f,
                        ROAD_HALF_WIDTH, r, g, b, a);
            }
            return;
        }

        int prevPerpDx = 0, prevPerpDz = 1;
        for (int i = 0; i < n - 1; i++) {
            PathPoint p1 = path.get(i);
            PathPoint p2 = path.get(i + 1);

            float ax = p1.x() + 0.5f;
            float ay = p1.y() + PREVIEW_Y_OFFSET;
            float az = p1.z() + 0.5f;
            float bx = p2.x() + 0.5f;
            float by = p2.y() + PREVIEW_Y_OFFSET;
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

            float hw = ROAD_HALF_WIDTH;
            vc.addVertex(poseEntry, ax - perpDx * hw, ay, az - perpDz * hw).setColor(r, g, b, a);
            vc.addVertex(poseEntry, ax + perpDx * hw, ay, az + perpDz * hw).setColor(r, g, b, a);
            vc.addVertex(poseEntry, bx + perpDx * hw, by, bz + perpDz * hw).setColor(r, g, b, a);
            vc.addVertex(poseEntry, bx - perpDx * hw, by, bz - perpDz * hw).setColor(r, g, b, a);

            prevPerpDx = perpDx;
            prevPerpDz = perpDz;
        }

        for (PathPoint p : path) {
            drawCornerSquare(vc, poseEntry,
                    p.x() + 0.5f, p.y() + PREVIEW_Y_OFFSET, p.z() + 0.5f,
                    ROAD_HALF_WIDTH, r, g, b, a);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Raycasting ──
    // ═══════════════════════════════════════════════════════════════

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
