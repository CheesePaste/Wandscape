package com.wsteam.wandscape.overview.client;

import java.util.UUID;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.network.BuildingAreaSyncPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * World-space renderer for overview mode.
 *
 * <p>When the crosshair targets a building, renders a white wireframe
 * around the full bounding box (all 12 edges) to highlight the building.</p>
 */
public final class OverviewRenderer {

    private static final String TAG = "OverviewRenderer";

    // White (#FFFFFFFF) — no color semantics
    private static final int LINE_R = 0xFF;
    private static final int LINE_G = 0xFF;
    private static final int LINE_B = 0xFF;
    private static final int LINE_A = 255;

    private static boolean registered = false;

    private OverviewRenderer() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, OverviewRenderer::onRenderLevelStage);
        Log.info(TAG, "Overview renderer registered");
    }

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!OverviewClientState.isActive()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        BlockPos targetPos = OverviewClientState.getTargetBlockPos();
        int entityId = OverviewClientState.getTargetEntityId();
        if (targetPos == null && entityId < 0) return;

        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        PoseStack.Pose pose = poseStack.last();

        // ── Building outline ──
        if (targetPos != null) {
            var buildings = BuildingAreaSyncPacket.getCached();
            for (var entry : buildings) {
                if (!entry.hasBoundary()) continue;
                BlockPos anchor = entry.anchor();
                if (!isInsideBoundary(targetPos, anchor, entry)) continue;
                renderBoundingBox(buf, pose, anchor, entry, LINE_R, LINE_G, LINE_B, LINE_A);
                break;
            }
        }

        // ── Entity outline ──
        if (entityId >= 0) {
            Entity entity = mc.level.getEntity(entityId);
            if (entity != null && entity.isAlive()
                    && (entity instanceof com.wsteam.wandscape.npc.entity.WandscapeNpc
                    || entity instanceof com.wsteam.wandscape.tourist.entity.TouristEntity)) {
                renderEntityBox(buf, pose, entity.getBoundingBox(), LINE_R, LINE_G, LINE_B, LINE_A);
            }
        }

        poseStack.popPose();
    }

    /**
     * Render all 12 edges of an entity's AABB as a white wireframe.
     */
    private static void renderEntityBox(MultiBufferSource.BufferSource buf, PoseStack.Pose pose,
                                        AABB bb, int r, int g, int b, int a) {
        float x0 = (float) bb.minX;
        float y0 = (float) bb.minY;
        float z0 = (float) bb.minZ;
        float x1 = (float) bb.maxX;
        float y1 = (float) bb.maxY;
        float z1 = (float) bb.maxZ;

        VertexConsumer vc = buf.getBuffer(RenderType.lines());

        // Bottom face (y0)
        line(vc, pose, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(vc, pose, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(vc, pose, x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(vc, pose, x0, y0, z1, x0, y0, z0, r, g, b, a);

        // Top face (y1)
        line(vc, pose, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(vc, pose, x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(vc, pose, x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(vc, pose, x0, y1, z1, x0, y1, z0, r, g, b, a);

        // 4 vertical edges connecting bottom ↔ top
        line(vc, pose, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(vc, pose, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(vc, pose, x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(vc, pose, x0, y0, z1, x0, y1, z1, r, g, b, a);

        buf.endBatch(RenderType.lines());
    }

    /**
     * Render all 12 edges of the full bounding box as a white wireframe.
     */
    private static void renderBoundingBox(MultiBufferSource.BufferSource buf, PoseStack.Pose pose,
                                           BlockPos anchor, BuildingAreaSyncPacket.BuildingEntry entry,
                                           int r, int g, int b, int a) {
        float x0 = anchor.getX() + entry.bMinX();
        float y0 = anchor.getY() + entry.bMinY();
        float z0 = anchor.getZ() + entry.bMinZ();
        float x1 = anchor.getX() + entry.bMaxX() + 1f;
        float y1 = anchor.getY() + entry.bMaxY() + 1f;
        float z1 = anchor.getZ() + entry.bMaxZ() + 1f;

        VertexConsumer vc = buf.getBuffer(RenderType.lines());

        // Bottom face (y0)
        line(vc, pose, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(vc, pose, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(vc, pose, x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(vc, pose, x0, y0, z1, x0, y0, z0, r, g, b, a);

        // Top face (y1)
        line(vc, pose, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(vc, pose, x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(vc, pose, x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(vc, pose, x0, y1, z1, x0, y1, z0, r, g, b, a);

        // 4 vertical edges connecting bottom ↔ top
        line(vc, pose, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(vc, pose, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(vc, pose, x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(vc, pose, x0, y0, z1, x0, y1, z1, r, g, b, a);

        buf.endBatch(RenderType.lines());
    }

    // ── Helpers ──

    private static boolean isInsideBoundary(BlockPos pos, BlockPos anchor,
                                            BuildingAreaSyncPacket.BuildingEntry entry) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        int ax = anchor.getX(), ay = anchor.getY(), az = anchor.getZ();
        return x >= ax + entry.bMinX() && x <= ax + entry.bMaxX()
                && y >= ay + entry.bMinY() && y <= ay + entry.bMaxY()
                && z >= az + entry.bMinZ() && z <= az + entry.bMaxZ();
    }

    private static void line(VertexConsumer vc, PoseStack.Pose pose,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             int r, int g, int b, int a) {
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, a).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x2, y2, z2).setColor(r, g, b, a).setNormal(pose, 0, 1, 0);
    }
}
