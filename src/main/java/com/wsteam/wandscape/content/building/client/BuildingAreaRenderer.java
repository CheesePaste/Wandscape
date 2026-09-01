package com.wsteam.wandscape.content.building.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.data.BlockOffset;
import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.content.building.projection.BuildingRotation;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.content.building.network.BuildingAreaSyncPacket;
import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * World-space renderer that visualizes building boundaries and indoor interaction
 * spots when the Wandscape panel is open.
 *
 * <p>Interaction spots = {@code interact_spots} from building config (anchor + 旋转偏移),
 * rendered as semi-transparent orange boxes. These are the spots where tourists
 * navigate indoors to interact with the building.
 *
 * <p>Building boundary = green wireframe from {@code boundary}.
 *
* <p>Activated when {@link WandscapePanelState#isPanelOpen()} and B key toggles
 * {@link WandscapePanelState#isShowBuildingAreas()}.
 */
public final class BuildingAreaRenderer {

    private static final String TAG = "BuildingAreaRenderer";

    // Interaction zone: semi-transparent orange
    private static final int ZONE_FACE_R = 255;
    private static final int ZONE_FACE_G = 140;
    private static final int ZONE_FACE_B = 0;
    private static final int ZONE_FACE_A = 35;

    private static final int ZONE_LINE_R = 255;
    private static final int ZONE_LINE_G = 100;
    private static final int ZONE_LINE_B = 0;
    private static final int ZONE_LINE_A = 200;

    // Building boundary (inner) reference lines: subtle green
    private static final int BOUND_LINE_R = 0;
    private static final int BOUND_LINE_G = 200;
    private static final int BOUND_LINE_B = 80;
    private static final int BOUND_LINE_A = 120;

    private static boolean registered = false;

    private BuildingAreaRenderer() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, BuildingAreaRenderer::onRenderLevelStage);
        Log.info(TAG, "[Renderer] Registered");
    }

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        // Only render when panel is open AND B key overlay is active
        if (!WandscapePanelState.isPanelOpen()) return;
        if (!WandscapePanelState.isShowBuildingAreas()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        var buildings = BuildingAreaSyncPacket.getCached();
        if (buildings.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        PoseStack.Pose pose = poseStack.last();

        for (var entry : buildings) {
            if (!entry.hasBoundary()) continue;

            BlockPos anchor = entry.anchor();
            int rotationSteps = entry.rotationSteps();

            // Pre-rotated boundary from entry — no rotation needed
            float bx0 = anchor.getX() + entry.bMinX();
            float by0 = anchor.getY() + entry.bMinY();
            float bz0 = anchor.getZ() + entry.bMinZ();
            float bx1 = anchor.getX() + entry.bMaxX() + 1f;
            float by1 = anchor.getY() + entry.bMaxY() + 1f;
            float bz1 = anchor.getZ() + entry.bMaxZ() + 1f;

            // Render interact_spots (orange) — rotate each spot with rotationSteps
            BuildingConfig config = BuildingConfigLoader.getInstance().get(entry.buildingTypeId());
            if (config != null) {
                for (BuildingConfig.InteractSpot spot : config.interactSpots()) {
                    BlockOffset rotated = rotationSteps != 0
                            ? BuildingRotation.rotateOffset(spot.pos(), rotationSteps)
                            : spot.pos();
                    float zx0 = anchor.getX() + rotated.x();
                    float zy0 = anchor.getY() + rotated.y();
                    float zz0 = anchor.getZ() + rotated.z();
                    renderZone(buf, pose, zx0, zy0, zz0, zx0 + 1f, zy0 + 1f, zz0 + 1f);
                }
            }

            // Render building boundary reference (subtle green)
            renderBoundary(buf, pose, bx0, by0, bz0, bx1, by1, bz1);
        }

        poseStack.popPose();
    }

    private static void renderZone(MultiBufferSource.BufferSource buf, PoseStack.Pose pose,
                                    float x0, float y0, float z0, float x1, float y1, float z1) {
        // Semi-transparent faces
        VertexConsumer fvc = buf.getBuffer(RenderType.debugQuads());
        int r = ZONE_FACE_R, g = ZONE_FACE_G, b = ZONE_FACE_B, a = ZONE_FACE_A;
        quad(fvc, pose, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a); // bottom
        quad(fvc, pose, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b, a); // top
        quad(fvc, pose, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, a); // back
        quad(fvc, pose, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b, a); // right
        quad(fvc, pose, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, a); // front
        quad(fvc, pose, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, a); // left
        buf.endBatch(RenderType.debugQuads());

        // Edges
        VertexConsumer lvc = buf.getBuffer(RenderType.lines());
        r = ZONE_LINE_R; g = ZONE_LINE_G; b = ZONE_LINE_B; a = ZONE_LINE_A;
        boxEdges(lvc, pose, x0, y0, z0, x1, y1, z1, r, g, b, a);
        buf.endBatch(RenderType.lines());
    }

    private static void renderBoundary(MultiBufferSource.BufferSource buf, PoseStack.Pose pose,
                                        float x0, float y0, float z0, float x1, float y1, float z1) {
        VertexConsumer lvc = buf.getBuffer(RenderType.lines());
        int r = BOUND_LINE_R, g = BOUND_LINE_G, b = BOUND_LINE_B, a = BOUND_LINE_A;
        boxEdges(lvc, pose, x0, y0, z0, x1, y1, z1, r, g, b, a);
        buf.endBatch(RenderType.lines());
    }

    // ── Drawing helpers ──

    private static void boxEdges(VertexConsumer vc, PoseStack.Pose pose,
                                  float x0, float y0, float z0, float x1, float y1, float z1,
                                  int r, int g, int b, int a) {
        line(vc, pose, x0,y0,z0, x1,y0,z0, r,g,b,a); line(vc, pose, x1,y0,z0, x1,y0,z1, r,g,b,a);
        line(vc, pose, x1,y0,z1, x0,y0,z1, r,g,b,a); line(vc, pose, x0,y0,z1, x0,y0,z0, r,g,b,a);
        line(vc, pose, x0,y1,z0, x1,y1,z0, r,g,b,a); line(vc, pose, x1,y1,z0, x1,y1,z1, r,g,b,a);
        line(vc, pose, x1,y1,z1, x0,y1,z1, r,g,b,a); line(vc, pose, x0,y1,z1, x0,y1,z0, r,g,b,a);
        line(vc, pose, x0,y0,z0, x0,y1,z0, r,g,b,a); line(vc, pose, x1,y0,z0, x1,y1,z0, r,g,b,a);
        line(vc, pose, x1,y0,z1, x1,y1,z1, r,g,b,a); line(vc, pose, x0,y0,z1, x0,y1,z1, r,g,b,a);
    }

    private static void line(VertexConsumer vc, PoseStack.Pose pose,
                              float x1, float y1, float z1, float x2, float y2, float z2,
                              int r, int g, int b, int a) {
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, a).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x2, y2, z2).setColor(r, g, b, a).setNormal(pose, 0, 1, 0);
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose,
                              float x1, float y1, float z1, float x2, float y2, float z2,
                              float x3, float y3, float z3, float x4, float y4, float z4,
                              int r, int g, int b, int a) {
        vc.addVertex(pose, x1,y1,z1).setColor(r,g,b,a);
        vc.addVertex(pose, x2,y2,z2).setColor(r,g,b,a);
        vc.addVertex(pose, x3,y3,z3).setColor(r,g,b,a);
        vc.addVertex(pose, x4,y4,z4).setColor(r,g,b,a);
    }
}
