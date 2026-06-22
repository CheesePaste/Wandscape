package com.wsteam.wandscape.road.client;

import java.util.List;
import java.util.UUID;

import org.joml.Matrix4f;
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
 * <p>Draws edges as colored line segments (status-coded) and nodes as
 * small colored boxes. Performs crosshair-to-edge hover detection.
 */
public final class RoadEditorRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double HOVER_THRESHOLD = 2.0;
    private static final double NODE_HOVER_THRESHOLD = 3.0;
    private static final float NODE_BOX_HALF = 0.2f;

    /** Set to true after first render call to suppress per-frame log spam. */
    private static boolean firstRenderLogged = false;
    private static int frameCounter = 0;

    private RoadEditorRenderer() {}

    // ── Registration ──

    public static void register() {
        LOGGER.info("[RoadEditor] register() — hooking RenderLevelStageEvent + ClientTickEvent");
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS
                .addListener(RenderLevelStageEvent.class, RoadEditorRenderer::onRenderLevelStage);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS
                .addListener(ClientTickEvent.Post.class, RoadEditorRenderer::onClientTick);
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
        // Heartbeat every 200 render calls (when editing and on right stage)
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

        PoseStack poseStack = event.getPoseStack();
        // Pose stack already has camera transform applied by LevelRenderer.
        // Do NOT push/translate camera here — vertices are in world space.

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer vc = bufferSource.getBuffer(RenderType.lines());

        poseStack.pushPose();

        Matrix4f poseMat = poseStack.last().pose();
        PoseStack.Pose poseEntry = poseStack.last();

        RoadNetwork network = RoadEditorClientState.getCachedNetwork();
        UUID hoveredId = RoadEditorClientState.getHoveredEdgeId();

        int nodeCount = network.nodeCount();
        int edgeCount = network.edgeCount();

        if (!firstRenderLogged) {
            firstRenderLogged = true;
            LOGGER.info("[RoadEditor] RENDER START — nodes={} edges={} camPos={}",
                    nodeCount, edgeCount, mc.gameRenderer.getMainCamera().getPosition());
        }

        // ── Draw edges ──
        for (RoadEdge edge : network.getEdges().values()) {
            float cr, cg, cb;
            if (edge.getEdgeId().equals(hoveredId)) {
                cr = 1.0f; cg = 0.2f; cb = 0.2f; // red highlight
            } else {
                switch (edge.getStatus()) {
                    case COMPLETE -> { cr = 0.1f; cg = 0.9f; cb = 0.2f; } // green
                    case BUILDING -> { cr = 1.0f; cg = 0.8f; cb = 0.1f; } // yellow
                    default        -> { cr = 0.2f; cg = 0.4f; cb = 1.0f; } // blue PLANNED
                }
            }
            int ri = (int)(cr * 255), gi = (int)(cg * 255), bi = (int)(cb * 255);

            List<PathPoint> path = edge.getPath();
            for (int i = 0; i < path.size() - 1; i++) {
                PathPoint p1 = path.get(i);
                PathPoint p2 = path.get(i + 1);
                float ax = p1.x() + 0.5f, ay = p1.y() + 0.55f, az = p1.z() + 0.5f;
                float bx = p2.x() + 0.5f, by = p2.y() + 0.55f, bz = p2.z() + 0.5f;
                vc.addVertex(poseMat, ax, ay, az).setColor(ri, gi, bi, 255).setNormal(poseEntry, 0, 1, 0);
                vc.addVertex(poseMat, bx, by, bz).setColor(ri, gi, bi, 255).setNormal(poseEntry, 0, 1, 0);
            }
        }

        // ── Draw nodes ──
        for (RoadNode node : network.getNodes().values()) {
            float cr, cg, cb;
            switch (node.type()) {
                case BUILDING     -> { cr = 1.0f; cg = 1.0f; cb = 1.0f; }
                case INTERSECTION -> { cr = 0.6f; cg = 0.1f; cb = 1.0f; }
                default           -> { cr = 0.5f; cg = 0.5f; cb = 0.5f; }
            }
            drawNodeBox(vc, poseMat, poseEntry,
                    node.pos().x() + 0.5f, node.pos().y() + 0.5f, node.pos().z() + 0.5f,
                    NODE_BOX_HALF, (int)(cr * 255), (int)(cg * 255), (int)(cb * 255));
        }

        poseStack.popPose();
        bufferSource.endBatch(RenderType.lines());
    }

    /** Draw a small wireframe box. */
    private static void drawNodeBox(VertexConsumer vc, Matrix4f poseMat, PoseStack.Pose poseEntry,
                                     float cx, float cy, float cz,
                                     float half, int r, int g, int b) {
        float x0 = cx - half, x1 = cx + half;
        float y0 = cy - half, y1 = cy + half;
        float z0 = cz - half, z1 = cz + half;

        seg(vc, poseMat, poseEntry, x0, y0, z0, x1, y0, z0, r, g, b); // bottom
        seg(vc, poseMat, poseEntry, x1, y0, z0, x1, y0, z1, r, g, b);
        seg(vc, poseMat, poseEntry, x1, y0, z1, x0, y0, z1, r, g, b);
        seg(vc, poseMat, poseEntry, x0, y0, z1, x0, y0, z0, r, g, b);
        seg(vc, poseMat, poseEntry, x0, y1, z0, x1, y1, z0, r, g, b); // top
        seg(vc, poseMat, poseEntry, x1, y1, z0, x1, y1, z1, r, g, b);
        seg(vc, poseMat, poseEntry, x1, y1, z1, x0, y1, z1, r, g, b);
        seg(vc, poseMat, poseEntry, x0, y1, z1, x0, y1, z0, r, g, b);
        seg(vc, poseMat, poseEntry, x0, y0, z0, x0, y1, z0, r, g, b); // verticals
        seg(vc, poseMat, poseEntry, x1, y0, z0, x1, y1, z0, r, g, b);
        seg(vc, poseMat, poseEntry, x1, y0, z1, x1, y1, z1, r, g, b);
        seg(vc, poseMat, poseEntry, x0, y0, z1, x0, y1, z1, r, g, b);
    }

    private static void seg(VertexConsumer vc, Matrix4f poseMat, PoseStack.Pose poseEntry,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             int r, int g, int b) {
        vc.addVertex(poseMat, x1, y1, z1).setColor(r, g, b, 255).setNormal(poseEntry, 0, 1, 0);
        vc.addVertex(poseMat, x2, y2, z2).setColor(r, g, b, 255).setNormal(poseEntry, 0, 1, 0);
    }

    // ── Client tick: hover + input ──

    static void onClientTick(ClientTickEvent.Post event) {
        if (!RoadEditorClientState.isEditing()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // Log first tick
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

        // ── Left-click: remove hovered edge ──
        if (bestEdgeId != null && mc.options.keyAttack.consumeClick()) {
            PacketDistributor.sendToServer(new RoadEdgeRemovePacket(bestEdgeId));
            RoadEditorClientState.setHoveredEdgeId(null);
        }

        // ── Right-click: path planning stub ──
        if (mc.options.keyUse.consumeClick()) {
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
                                    "§7[RoadEditor] §ePath planning requested §7(not yet implemented)"),
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
