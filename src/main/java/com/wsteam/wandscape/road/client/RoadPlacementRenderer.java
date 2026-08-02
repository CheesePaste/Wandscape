package com.wsteam.wandscape.road.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import com.wsteam.wandscape.shared.log.Log;

/**
 * World-space rendering for road placement preview.
 *
 * <p>Renders:
 * <ul>
 *   <li>Green outline at start position</li>
 *   <li>Red outline at end position</li>
 *   <li>Translucent yellow fill + perimeter outline over the entire rectangle area</li>
 * </ul>
 *
 * <p>Surface height determined via {@link Heightmap.Types#MOTION_BLOCKING}
 * to match server-side placement.
 *
 * <p>Registered at {@link RenderLevelStageEvent.Stage#AFTER_TRIPWIRE_BLOCKS}.
 */
public final class RoadPlacementRenderer {

    private static final String TAG = "RoadPlacementRenderer";

    private static final float LINE_WIDTH = 2.0f;

    private static boolean registered = false;

    private RoadPlacementRenderer() {}

    // ── Registration ──

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, RoadPlacementRenderer::onRenderLevelStage);
        Log.info(TAG, "[RoadPlacement] Renderer registered");
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Render handler ──
    // ═══════════════════════════════════════════════════════════════

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!RoadPlacementState.isProjecting()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        // Start marker (green outline)
        BlockPos startPos = RoadPlacementState.getStartPos();
        if (startPos != null) {
            renderBlockOutline(bufferSource, poseStack, startPos, 0, 255, 80);
        }

        // End marker (red outline)
        BlockPos endPos = RoadPlacementState.getEndPos();
        if (endPos != null) {
            renderBlockOutline(bufferSource, poseStack, endPos, 255, 40, 40);
        }

        // Preview: FILL renders the full 3D cube; Replace/Destroy render the surface rectangle
        BlockPos ghostPos = RoadPlacementState.getGhostPos();
        BlockPos from = startPos;
        BlockPos to = (endPos != null) ? endPos : ghostPos;

        if (from != null && to != null) {
            if (RoadPlacementState.isSpline()) {
                renderPathPreview(mc.level, bufferSource, poseStack, from, to);
            } else {
                renderPathPreview(mc.level, bufferSource, poseStack, from, to);
            }
        }

        poseStack.popPose();
    }

    // ── Block outline ──

    private static void renderBlockOutline(MultiBufferSource bufferSource, PoseStack poseStack,
                                            BlockPos pos, int r, int g, int b) {
        VertexConsumer vc = bufferSource.getBuffer(RenderType.lines());
        var pose = poseStack.last();

        float x = pos.getX();
        float y = pos.getY();
        float z = pos.getZ();

        // Bottom face
        line(vc, pose, x, y, z, x + 1, y, z, r, g, b);
        line(vc, pose, x + 1, y, z, x + 1, y, z + 1, r, g, b);
        line(vc, pose, x + 1, y, z + 1, x, y, z + 1, r, g, b);
        line(vc, pose, x, y, z + 1, x, y, z, r, g, b);

        // Top face
        line(vc, pose, x, y + 1, z, x + 1, y + 1, z, r, g, b);
        line(vc, pose, x + 1, y + 1, z, x + 1, y + 1, z + 1, r, g, b);
        line(vc, pose, x + 1, y + 1, z + 1, x, y + 1, z + 1, r, g, b);
        line(vc, pose, x, y + 1, z + 1, x, y + 1, z, r, g, b);

        // Vertical edges
        line(vc, pose, x, y, z, x, y + 1, z, r, g, b);
        line(vc, pose, x + 1, y, z, x + 1, y + 1, z, r, g, b);
        line(vc, pose, x + 1, y, z + 1, x + 1, y + 1, z + 1, r, g, b);
        line(vc, pose, x, y, z + 1, x, y + 1, z + 1, r, g, b);
    }

    private static void line(VertexConsumer vc, PoseStack.Pose pose,
                              float x1, float y1, float z1, float x2, float y2, float z2,
                              int r, int g, int b) {
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, 255).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x2, y2, z2).setColor(r, g, b, 255).setNormal(pose, 0, 1, 0);
    }

    // ── 3D cube preview (Fill mode) ──

    /**
     * Renders the full 3D box between the two corner blocks: a bright yellow
     * wireframe (12 edges) plus translucent faces, matching the server-side
     * fill in {@code FillBoxPacket}.
     */
    private static void renderBoxPreview(MultiBufferSource bufferSource, PoseStack poseStack,
                                         BlockPos from, BlockPos to) {
        int minX = Math.min(from.getX(), to.getX());
        int maxX = Math.max(from.getX(), to.getX());
        int minY = Math.min(from.getY(), to.getY());
        int maxY = Math.max(from.getY(), to.getY());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxZ = Math.max(from.getZ(), to.getZ());

        float x1 = minX, y1 = minY, z1 = minZ;
        float x2 = maxX + 1f, y2 = maxY + 1f, z2 = maxZ + 1f;

        // Wireframe — 12 edges
        VertexConsumer lineVc = bufferSource.getBuffer(RenderType.lines());
        var pose = poseStack.last();
        // Bottom face (y1)
        line(lineVc, pose, x1, y1, z1, x2, y1, z1, 255, 255, 80);
        line(lineVc, pose, x2, y1, z1, x2, y1, z2, 255, 255, 80);
        line(lineVc, pose, x2, y1, z2, x1, y1, z2, 255, 255, 80);
        line(lineVc, pose, x1, y1, z2, x1, y1, z1, 255, 255, 80);
        // Top face (y2)
        line(lineVc, pose, x1, y2, z1, x2, y2, z1, 255, 255, 80);
        line(lineVc, pose, x2, y2, z1, x2, y2, z2, 255, 255, 80);
        line(lineVc, pose, x2, y2, z2, x1, y2, z2, 255, 255, 80);
        line(lineVc, pose, x1, y2, z2, x1, y2, z1, 255, 255, 80);
        // Vertical edges
        line(lineVc, pose, x1, y1, z1, x1, y2, z1, 255, 255, 80);
        line(lineVc, pose, x2, y1, z1, x2, y2, z1, 255, 255, 80);
        line(lineVc, pose, x2, y1, z2, x2, y2, z2, 255, 255, 80);
        line(lineVc, pose, x1, y1, z2, x1, y2, z2, 255, 255, 80);

        // Translucent faces — 6 quads
        VertexConsumer vc = bufferSource.getBuffer(RenderType.translucent());
        int light = 0xF000F0;
        int alpha = 40;
        quad(vc, pose, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, light, alpha); // bottom
        quad(vc, pose, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, light, alpha); // top
        quad(vc, pose, x1, y1, z1, x1, y2, z1, x1, y2, z2, x1, y1, z2, light, alpha); // -X
        quad(vc, pose, x2, y1, z1, x2, y1, z2, x2, y2, z2, x2, y2, z1, light, alpha); // +X
        quad(vc, pose, x1, y1, z1, x2, y1, z1, x2, y2, z1, x1, y2, z1, light, alpha); // -Z
        quad(vc, pose, x1, y1, z2, x1, y2, z2, x2, y2, z2, x2, y1, z2, light, alpha); // +Z
    }

    /** Adds a single translucent quad (two triangles, 6 vertices). */
    private static void quad(VertexConsumer vc, PoseStack.Pose pose,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4,
                             int light, int alpha) {
        vc.addVertex(pose, x1, y1, z1).setColor(255, 255, 80, alpha).setUv(0, 0).setLight(light).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x2, y2, z2).setColor(255, 255, 80, alpha).setUv(0, 0).setLight(light).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x3, y3, z3).setColor(255, 255, 80, alpha).setUv(0, 0).setLight(light).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x3, y3, z3).setColor(255, 255, 80, alpha).setUv(0, 0).setLight(light).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x4, y4, z4).setColor(255, 255, 80, alpha).setUv(0, 0).setLight(light).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x1, y1, z1).setColor(255, 255, 80, alpha).setUv(0, 0).setLight(light).setNormal(pose, 0, 1, 0);
    }

    // ── Rectangle area preview ──

    /**
     * Renders a translucent yellow fill at each surface block position within
     * the rectangle, plus a bright yellow perimeter outline.
     *
     * <p>Surface height is determined via {@link Heightmap.Types#MOTION_BLOCKING}
     * to stay in sync with server-side placement in {@code RoadPlacePacket}.
     */
    private static void renderPathPreview(Level level, MultiBufferSource bufferSource, PoseStack poseStack,
                                           BlockPos from, BlockPos to) {
        int minX = Math.min(from.getX(), to.getX());
        int maxX = Math.max(from.getX(), to.getX());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxZ = Math.max(from.getZ(), to.getZ());

        // Translucent fill at each surface block position
        renderSurfaceFill(bufferSource, poseStack, level, minX, minZ, maxX, maxZ);

        // Perimeter outline — sample surface height at each of the four corners
        // so the outline follows the terrain, avoiding buried segments on slopes.
        float yMinZMinX = surfaceHeight(level, minX, minZ) + 0.02f;
        float yMinZMaxX = surfaceHeight(level, maxX, minZ) + 0.02f;
        float yMaxZMinX = surfaceHeight(level, minX, maxZ) + 0.02f;
        float yMaxZMaxX = surfaceHeight(level, maxX, maxZ) + 0.02f;

        VertexConsumer vc = bufferSource.getBuffer(RenderType.lines());
        var pose = poseStack.last();
        // Edge along Z=minZ
        line(vc, pose, minX, yMinZMinX, minZ, maxX + 1, yMinZMaxX, minZ, 255, 255, 80);
        // Edge along X=maxX+1
        line(vc, pose, maxX + 1, yMinZMaxX, minZ, maxX + 1, yMaxZMaxX, maxZ + 1, 255, 255, 80);
        // Edge along Z=maxZ+1
        line(vc, pose, maxX + 1, yMaxZMaxX, maxZ + 1, minX, yMaxZMinX, maxZ + 1, 255, 255, 80);
        // Edge along X=minX
        line(vc, pose, minX, yMaxZMinX, maxZ + 1, minX, yMinZMinX, minZ, 255, 255, 80);
    }

    /**
     * Renders a translucent yellow quad at each surface block position within
     * the rectangle, showing exactly which blocks will be replaced.
     */
    private static void renderSurfaceFill(MultiBufferSource bufferSource, PoseStack poseStack,
                                           Level level, int minX, int minZ, int maxX, int maxZ) {
        VertexConsumer vc = bufferSource.getBuffer(RenderType.translucent());
        var pose = poseStack.last();
        int light = 0xF000F0;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                float y = surfaceHeight(level, x, z) + 0.02f;
                float x1 = x, x2 = x + 1f, z1 = z, z2 = z + 1f;

                vc.addVertex(pose, x1, y, z1).setColor(255, 255, 80, 48).setUv(0, 0).setLight(light).setNormal(pose, 0, 1, 0);
                vc.addVertex(pose, x1, y, z2).setColor(255, 255, 80, 48).setUv(0, 0).setLight(light).setNormal(pose, 0, 1, 0);
                vc.addVertex(pose, x2, y, z2).setColor(255, 255, 80, 48).setUv(0, 0).setLight(light).setNormal(pose, 0, 1, 0);
                vc.addVertex(pose, x2, y, z2).setColor(255, 255, 80, 48).setUv(0, 0).setLight(light).setNormal(pose, 0, 1, 0);
                vc.addVertex(pose, x2, y, z1).setColor(255, 255, 80, 48).setUv(0, 0).setLight(light).setNormal(pose, 0, 1, 0);
                vc.addVertex(pose, x1, y, z1).setColor(255, 255, 80, 48).setUv(0, 0).setLight(light).setNormal(pose, 0, 1, 0);
            }
        }
    }

    /** Sample the MOTION_BLOCKING surface height at (x, z). */
    private static float surfaceHeight(Level level, int x, int z) {
        return (float)level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
    }
}
