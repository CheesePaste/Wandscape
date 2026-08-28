package com.wsteam.wandscape.shared.ui.util;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wsteam.wandscape.projection.client.BuildingDebugClientState;
import com.wsteam.wandscape.projection.network.BuildingDebugResponsePacket;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.network.BuildingAreaSyncPacket;
import com.wsteam.wandscape.shared.ui.panel.WandscapePanelState;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class WandscapeHighlightRenderer {

    private static final String TAG = "WandscapeHighlightRenderer";

    // Highlight color (e.g. bright blue/white for RTS selection)
    private static final int LINE_R = 255;
    private static final int LINE_G = 255;
    private static final int LINE_B = 255;
    private static final int LINE_A = 200;

    private static boolean registered = false;

    private WandscapeHighlightRenderer() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, WandscapeHighlightRenderer::onRenderLevelStage);
        Log.info(TAG, "Wandscape highlight renderer registered");
    }

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!WandscapePanelState.isPanelOpen() && !BuildingDebugClientState.isActive()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        PoseStack.Pose pose = poseStack.last();

        // 1. Building Outline (if looking at a building and response is cached).
        //    Uses the pre-rotated boundary from the building-area cache (same source
        //    as OverviewRenderer) so a rotated building's outline matches its true
        //    footprint — the debug packet only carries the anchor, not the rotation.
        BuildingDebugResponsePacket data = BuildingDebugClientState.getDisplayData();
        // 建筑外框高亮同口径：仅俯瞰(OVERVIEW)模式，跟随建筑信息顶栏的显示范围
        if (data != null && WandscapePanelState.isInspectContext()
                && data.anchor() != null && data.buildingTypeId() != null) {
            for (BuildingAreaSyncPacket.BuildingEntry entry : BuildingAreaSyncPacket.getCached()) {
                if (entry.hasBoundary()
                        && entry.anchor().equals(data.anchor())
                        && entry.buildingTypeId().equals(data.buildingTypeId())) {
                    renderBoundingBox(buf, pose, entry, LINE_R, LINE_G, LINE_B, LINE_A);
                    break;
                }
            }
        }

        // 2. NPC Outline (if looking at a Wandscape NPC or Tourist)
        Entity entity = mc.crosshairPickEntity;
        if (entity != null && entity.isAlive()
                && (entity instanceof com.wsteam.wandscape.npc.entity.WandscapeNpc
                || entity instanceof com.wsteam.wandscape.tourist.entity.TouristEntity)) {
            renderEntityBox(buf, pose, entity.getBoundingBox(), LINE_R, LINE_G, LINE_B, LINE_A);
        }

        poseStack.popPose();
    }

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

    private static void renderBoundingBox(MultiBufferSource.BufferSource buf, PoseStack.Pose pose,
                                           BuildingAreaSyncPacket.BuildingEntry entry,
                                           int r, int g, int b, int a) {
        BlockPos anchor = entry.anchor();
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

    private static void line(VertexConsumer vc, PoseStack.Pose pose,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             int r, int g, int b, int a) {
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, a).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x2, y2, z2).setColor(r, g, b, a).setNormal(pose, 0, 1, 0);
    }
}
