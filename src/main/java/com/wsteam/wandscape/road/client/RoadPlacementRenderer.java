package com.wsteam.wandscape.road.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wsteam.wandscape.road.core.RoadTemplate;
import com.wsteam.wandscape.road.core.SplineVec3;
import com.wsteam.wandscape.road.data.RoadPreset;
import com.wsteam.wandscape.shared.ui.util.BuildingPreviewRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
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
 *   <li>Replace/Destroy: the actual road blocks as translucent 3D ghosts over the
 *       rectangle area (flat fill fallback for very large selections)</li>
 *   <li>Fill: a 3D wireframe box with translucent faces</li>
 *   <li>Spline: a translucent yellow fill + perimeter outline</li>
 * </ul>
 *
 * <p>Surface height determined via {@link Heightmap.Types#MOTION_BLOCKING}
 * to match server-side placement.
 *
 * <p>Registered at {@link RenderLevelStageEvent.Stage#AFTER_TRIPWIRE_BLOCKS}.
 */
public final class RoadPlacementRenderer {

    private static final String TAG = "RoadPlacementRenderer";

    private static final float LINE_WIDTH = 5.0f;
    /** Ghost opacity factor for the translucent road block preview (255 * factor). */
    private static final float ROAD_GHOST_ALPHA = 0.55f;
    /** Above this many surface cells, fall back to the cheap flat fill. */
    private static final int ROAD_GHOST_MAX_CELLS = 1024;

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
        if (!RoadPlacementState.isProjecting() && !SplineEditorClientState.isEditing() && !com.wsteam.wandscape.road.client.studio.RoadStudioOverlay.isVisible()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        // 0. Base terrain grid overlay (semi-transparent gray 1x1 block grid around camera)
        renderTerrainGrid(mc.level, bufferSource, poseStack, camPos);

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

        // Preview: FILL renders the full 3D cube; Replace/Destroy render the actual
        // road blocks as ghost; Spline keeps the flat rectangle approximation.
        BlockPos ghostPos = RoadPlacementState.getGhostPos();
        BlockPos from = startPos;
        BlockPos to = (endPos != null) ? endPos : ghostPos;

        if (from != null && to != null) {
            if (RoadPlacementState.isFill()) {
                renderBoxPreview(bufferSource, poseStack, from, to);
            } else if (RoadPlacementState.isSpline()) {
                renderPathPreview(mc.level, bufferSource, poseStack, from, to);
            } else if (RoadPlacementState.isReplaceArray()) {
                if (SplineEditorClientState.isArrayPreview()) {
                    renderLinearArrayGhost(mc.level, bufferSource, poseStack, from, to);
                } else {
                    renderPathPreview(mc.level, bufferSource, poseStack, from, to);
                }
            } else {
                renderRoadGhost(mc.level, bufferSource, poseStack, from, to,
                        RoadPlacementState.getSelectedPreset());
            }
        }

        // Draw Start & End Gizmos when in Replace/Fill/DestroyFill mode and start/end are set
        if (RoadPlacementState.getActiveTool() != RoadPlacementState.ToolMode.SPLINE && startPos != null && endPos != null) {
            VertexConsumer vcQuads = bufferSource.getBuffer(SplineEditorRenderer.SplineRenderType.XRAY_QUADS);
            drawBoxCornerGizmos(vcQuads, poseStack.last(), startPos, endPos);
            bufferSource.endBatch(SplineEditorRenderer.SplineRenderType.XRAY_QUADS);
        }

        poseStack.popPose();

        // AFTER_TRIPWIRE_BLOCKS fires after the level renderer already flushed the
        // main buffer source (see LevelRenderer.renderLevel), so vertices added here
        // would otherwise never be drawn this frame. Flush explicitly.
        bufferSource.endBatch(RenderType.lines());
        bufferSource.endBatch(RenderType.translucent());
    }

    // ── Terrain Grid Overlay ──

    private static final int GRID_RADIUS = 28;
    private static final float GRID_RADIUS_SQ = GRID_RADIUS * GRID_RADIUS;

    /**
     * Renders a soft semi-transparent gray grid overlay following the terrain surface around the camera.
     */
    private static void renderTerrainGrid(Level level, MultiBufferSource bufferSource, PoseStack poseStack, Vec3 camPos) {
        int centerX = (int) Math.floor(camPos.x);
        int centerZ = (int) Math.floor(camPos.z);
        var pose = poseStack.last();
        int light = 0xF000F0;

        int minX = centerX - GRID_RADIUS;
        int maxX = centerX + GRID_RADIUS;
        int minZ = centerZ - GRID_RADIUS;
        int maxZ = centerZ + GRID_RADIUS;

        // Pass 1: Soft translucent gray fill quads
        VertexConsumer vcQuads = bufferSource.getBuffer(RenderType.translucent());
        for (int x = minX; x <= maxX; x++) {
            double dx = (x + 0.5) - camPos.x;
            for (int z = minZ; z <= maxZ; z++) {
                double dz = (z + 0.5) - camPos.z;
                double distSq = dx * dx + dz * dz;
                if (distSq > GRID_RADIUS_SQ) continue;

                float fade = (float) (1.0 - Math.sqrt(distSq) / GRID_RADIUS);
                fade = Math.max(0f, Math.min(1f, fade));
                fade = fade * fade;

                int fillAlpha = (int) (50 * fade);
                if (fillAlpha > 2) {
                    float y = surfaceHeight(level, x, z) + 0.015f;
                    float x0 = x, x1 = x + 1.0f;
                    float z0 = z, z1 = z + 1.0f;

                    vcQuads.addVertex(pose, x0, y, z0).setColor(160, 165, 175, fillAlpha).setUv(0, 0).setLight(light).setNormal(pose, 0, 1, 0);
                    vcQuads.addVertex(pose, x0, y, z1).setColor(160, 165, 175, fillAlpha).setUv(0, 0).setLight(light).setNormal(pose, 0, 1, 0);
                    vcQuads.addVertex(pose, x1, y, z1).setColor(160, 165, 175, fillAlpha).setUv(0, 0).setLight(light).setNormal(pose, 0, 1, 0);
                    vcQuads.addVertex(pose, x1, y, z0).setColor(160, 165, 175, fillAlpha).setUv(0, 0).setLight(light).setNormal(pose, 0, 1, 0);
                }
            }
        }

        // Pass 2: Subtle gray grid lines (Top & Left edges)
        VertexConsumer vcLines = bufferSource.getBuffer(RenderType.lines());
        for (int x = minX; x <= maxX; x++) {
            double dx = (x + 0.5) - camPos.x;
            for (int z = minZ; z <= maxZ; z++) {
                double dz = (z + 0.5) - camPos.z;
                double distSq = dx * dx + dz * dz;
                if (distSq > GRID_RADIUS_SQ) continue;

                float fade = (float) (1.0 - Math.sqrt(distSq) / GRID_RADIUS);
                fade = Math.max(0f, Math.min(1f, fade));
                fade = fade * fade;

                int lineAlpha = (int) (70 * fade);
                if (lineAlpha > 4) {
                    float y = surfaceHeight(level, x, z) + 0.015f;
                    float x0 = x, x1 = x + 1.0f;
                    float z0 = z, z1 = z + 1.0f;

                    // Edge along X
                    vcLines.addVertex(pose, x0, y, z0).setColor(190, 195, 205, lineAlpha).setNormal(pose, 0, 1, 0);
                    vcLines.addVertex(pose, x1, y, z0).setColor(190, 195, 205, lineAlpha).setNormal(pose, 0, 1, 0);

                    // Edge along Z
                    vcLines.addVertex(pose, x0, y, z0).setColor(190, 195, 205, lineAlpha).setNormal(pose, 0, 1, 0);
                    vcLines.addVertex(pose, x0, y, z1).setColor(190, 195, 205, lineAlpha).setNormal(pose, 0, 1, 0);
                }
            }
        }
    }

    // ── Gizmo Rendering (Start & End corner gizmos) ──

    private static final float GIZMO_SHAFT_LEN = 1.5f;
    private static final float GIZMO_SHAFT_THICKNESS = 0.05f;
    private static final float GIZMO_HEAD_LEN = 0.3f;
    private static final float GIZMO_HEAD_THICKNESS = 0.12f;

    private static final int[] GIZMO_COL_X = {255, 60, 60, 200};
    private static final int[] GIZMO_COL_Y = {60, 255, 60, 200};
    private static final int[] GIZMO_COL_Z = {60, 100, 255, 200};
    private static final int[] GIZMO_COL_XN = {160, 40, 40, 160};
    private static final int[] GIZMO_COL_YN = {40, 160, 40, 160};
    private static final int[] GIZMO_COL_ZN = {40, 60, 160, 160};

    private static void drawBoxCornerGizmos(VertexConsumer vc, PoseStack.Pose pose, BlockPos startPos, BlockPos endPos) {
        RoadPlacementState.GizmoTarget hoveredTarget = RoadPlacementState.getHoveredTarget();
        RoadPlacementState.AxisDrag hoveredAxis = RoadPlacementState.getHoveredAxis();
        RoadPlacementState.GizmoTarget draggingTarget = RoadPlacementState.getDraggingTarget();
        RoadPlacementState.AxisDrag draggingAxis = RoadPlacementState.getDraggingAxis();

        // 1. Start Gizmo (Green center marker)
        double sx = startPos.getX() + 0.5;
        double sy = startPos.getY() + 0.5;
        double sz = startPos.getZ() + 0.5;
        RoadPlacementState.AxisDrag activeStartAxis = (draggingTarget == RoadPlacementState.GizmoTarget.START) ? draggingAxis
                : ((hoveredTarget == RoadPlacementState.GizmoTarget.START) ? hoveredAxis : RoadPlacementState.AxisDrag.NONE);
        drawSingleGizmo(vc, pose, sx, sy, sz, activeStartAxis, true);

        // 2. End Gizmo (Red center marker)
        double ex = endPos.getX() + 0.5;
        double ey = endPos.getY() + 0.5;
        double ez = endPos.getZ() + 0.5;
        RoadPlacementState.AxisDrag activeEndAxis = (draggingTarget == RoadPlacementState.GizmoTarget.END) ? draggingAxis
                : ((hoveredTarget == RoadPlacementState.GizmoTarget.END) ? hoveredAxis : RoadPlacementState.AxisDrag.NONE);
        drawSingleGizmo(vc, pose, ex, ey, ez, activeEndAxis, false);
    }

    private static void drawSingleGizmo(VertexConsumer vc, PoseStack.Pose pose, double x, double y, double z,
                                        RoadPlacementState.AxisDrag activeAxis, boolean isStart) {
        // Center cube: Green for Start, Red for End
        int[] centerCol = isStart ? new int[]{0, 255, 100, 220} : new int[]{255, 80, 80, 220};
        net.minecraft.world.phys.AABB centerBox = new net.minecraft.world.phys.AABB(x - 0.12, y - 0.12, z - 0.12, x + 0.12, y + 0.12, z + 0.12);
        fillAABB(vc, pose, centerBox, centerCol[0], centerCol[1], centerCol[2], centerCol[3]);

        // 6 directional arrows
        drawGizmoArrow(vc, pose, x, y, z, RoadPlacementState.AxisDrag.X_POS, 1, 0, 0, GIZMO_COL_X, activeAxis == RoadPlacementState.AxisDrag.X_POS);
        drawGizmoArrow(vc, pose, x, y, z, RoadPlacementState.AxisDrag.X_NEG, -1, 0, 0, GIZMO_COL_XN, activeAxis == RoadPlacementState.AxisDrag.X_NEG);
        drawGizmoArrow(vc, pose, x, y, z, RoadPlacementState.AxisDrag.Y_POS, 0, 1, 0, GIZMO_COL_Y, activeAxis == RoadPlacementState.AxisDrag.Y_POS);
        drawGizmoArrow(vc, pose, x, y, z, RoadPlacementState.AxisDrag.Y_NEG, 0, -1, 0, GIZMO_COL_YN, activeAxis == RoadPlacementState.AxisDrag.Y_NEG);
        drawGizmoArrow(vc, pose, x, y, z, RoadPlacementState.AxisDrag.Z_POS, 0, 0, 1, GIZMO_COL_Z, activeAxis == RoadPlacementState.AxisDrag.Z_POS);
        drawGizmoArrow(vc, pose, x, y, z, RoadPlacementState.AxisDrag.Z_NEG, 0, 0, -1, GIZMO_COL_ZN, activeAxis == RoadPlacementState.AxisDrag.Z_NEG);
    }

    private static void drawGizmoArrow(VertexConsumer vc, PoseStack.Pose pose, double x, double y, double z,
                                        RoadPlacementState.AxisDrag axis, int dx, int dy, int dz, int[] col, boolean highlight) {
        float bright = highlight ? 1.4f : 1.0f;
        int r = Math.min(255, (int)(col[0] * bright));
        int g = Math.min(255, (int)(col[1] * bright));
        int b = Math.min(255, (int)(col[2] * bright));
        int a = highlight ? 255 : col[3];

        // 1. Shaft
        net.minecraft.world.phys.AABB shaft = getGizmoAxisAABB(x, y, z, axis, GIZMO_SHAFT_LEN, GIZMO_SHAFT_THICKNESS);
        fillAABB(vc, pose, shaft, r, g, b, a);

        // 2. Head
        double headStart = GIZMO_SHAFT_LEN;
        double headX = x + dx * headStart;
        double headY = y + dy * headStart;
        double headZ = z + dz * headStart;
        net.minecraft.world.phys.AABB head = getGizmoAxisAABB(headX, headY, headZ, axis, GIZMO_HEAD_LEN, GIZMO_HEAD_THICKNESS);
        fillAABB(vc, pose, head, r, g, b, a);
    }

    private static net.minecraft.world.phys.AABB getGizmoAxisAABB(double x, double y, double z, RoadPlacementState.AxisDrag axis, float length, float thickness) {
        double minX = x - thickness, minY = y - thickness, minZ = z - thickness;
        double maxX = x + thickness, maxY = y + thickness, maxZ = z + thickness;

        switch (axis) {
            case X_POS -> maxX = x + length;
            case X_NEG -> minX = x - length;
            case Y_POS -> maxY = y + length;
            case Y_NEG -> minY = y - length;
            case Z_POS -> maxZ = z + length;
            case Z_NEG -> minZ = z - length;
        }

        return new net.minecraft.world.phys.AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static void fillAABB(VertexConsumer vc, PoseStack.Pose pose, net.minecraft.world.phys.AABB box, int r, int g, int b, int a) {
        float x0 = (float)box.minX, y0 = (float)box.minY, z0 = (float)box.minZ;
        float x1 = (float)box.maxX, y1 = (float)box.maxY, z1 = (float)box.maxZ;
        
        quad(vc, pose, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a);
        quad(vc, pose, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b, a);
        quad(vc, pose, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, a);
        quad(vc, pose, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b, a);
        quad(vc, pose, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, a);
        quad(vc, pose, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, a);
    }

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
        renderPerimeterOutline(level, bufferSource, poseStack, minX, minZ, maxX, maxZ);
    }

    /**
     * Renders the actual road blocks as translucent 3D ghosts at each surface
     * position within the rectangle, matching the server-side tiles in
     * {@code RoadPlacePacket} (same MOTION_BLOCKING surface height and preset
     * block choice). Mirrors the building ghost so the player sees exactly
     * which blocks the road will place.
     *
     * <p>Large selections fall back to the cheap flat fill to keep per-frame
     * block-model cost bounded.
     */
    private static void renderRoadGhost(Level level, MultiBufferSource.BufferSource bufferSource, PoseStack poseStack,
                                        BlockPos from, BlockPos to, RoadPreset preset) {
        int minX = Math.min(from.getX(), to.getX());
        int maxX = Math.max(from.getX(), to.getX());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxZ = Math.max(from.getZ(), to.getZ());

        renderPerimeterOutline(level, bufferSource, poseStack, minX, minZ, maxX, maxZ);

        int area = (maxX - minX + 1) * (maxZ - minZ + 1);
        if (area > ROAD_GHOST_MAX_CELLS) {
            renderSurfaceFill(bufferSource, poseStack, level, minX, minZ, maxX, maxZ);
            return;
        }

        MultiBufferSource ghostSource = RoadGhostRenderUtil.ghostSource(bufferSource, ROAD_GHOST_ALPHA);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                BlockState state = BuildingPreviewRenderer.resolveBlockState(preset.pickBlock(x, z));
                if (state == null) continue;

                RoadGhostRenderUtil.renderGhostBlock(level, state, poseStack, ghostSource, x, y, z);
            }
        }
    }

    /** Draws the yellow perimeter outline of the placement rectangle, following the terrain. */
    private static void renderPerimeterOutline(Level level, MultiBufferSource bufferSource, PoseStack poseStack,
                                               int minX, int minZ, int maxX, int maxZ) {
        // Sample surface height at each of the four corners so the outline follows
        // the terrain, avoiding buried segments on slopes.
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

    /**
     * Renders a 3D linear blueprint array preview between {@code from} and {@code to},
     * matching the server-side tiles generated for Replace array mode.
     */
    private static void renderLinearArrayGhost(Level level, MultiBufferSource.BufferSource bufferSource,
                                               PoseStack poseStack, BlockPos from, BlockPos to) {
        RoadTemplate template = SplineEditorClientState.getActiveTemplate();
        if (template == null || template.getBlocks().isEmpty()) {
            SplineEditorClientState.rebuildDynamicTemplate();
            template = SplineEditorClientState.getActiveTemplate();
            if (template == null || template.getBlocks().isEmpty()) return;
        }

        double stepDistance = Math.max(0.2, SplineEditorClientState.getArrayStepDistance());
        boolean snapTerrain = RoadPlacementState.isSnapTerrain();

        double sx = from.getX() + 0.5, sy = from.getY() + 0.5, sz = from.getZ() + 0.5;
        double ex = to.getX() + 0.5, ey = to.getY() + 0.5, ez = to.getZ() + 0.5;

        double dist = snapTerrain ? Math.sqrt((ex - sx) * (ex - sx) + (ez - sz) * (ez - sz))
                                  : Math.sqrt((ex - sx) * (ex - sx) + (ey - sy) * (ey - sy) + (ez - sz) * (ez - sz));
        int steps = Math.max(1, (int) Math.ceil(dist / stepDistance));

        List<SplineVec3> samplePoints = new ArrayList<>();
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double px = sx + (ex - sx) * t;
            double pz = sz + (ez - sz) * t;
            double py;
            if (snapTerrain && level != null) {
                int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(px), (int) Math.floor(pz)) - 1;
                py = surfaceY + 0.5;
            } else {
                py = sy + (ey - sy) * t;
            }
            samplePoints.add(new SplineVec3(px, py, pz));
        }

        float roll = (float) Math.toRadians(SplineEditorClientState.getArrayOffsetRoll());
        float pitch = (float) Math.toRadians(SplineEditorClientState.getArrayOffsetPitch());
        float yaw = (float) Math.toRadians(SplineEditorClientState.getArrayOffsetYaw());

        Map<BlockPos, BlockState> uniqueBlocks = new HashMap<>();

        for (int i = 0; i < samplePoints.size(); i++) {
            SplineVec3 pos = samplePoints.get(i);
            SplineVec3 tan;
            if (samplePoints.size() <= 1) {
                tan = new SplineVec3(0, 0, 1);
            } else if (i < samplePoints.size() - 1) {
                tan = samplePoints.get(i + 1).subtract(pos);
            } else {
                tan = pos.subtract(samplePoints.get(i - 1));
            }

            org.joml.Vector3f forward = new org.joml.Vector3f((float) tan.x(), (float) tan.y(), (float) tan.z()).normalize();
            org.joml.Vector3f right = new org.joml.Vector3f(0, 1, 0).cross(forward);
            if (right.lengthSquared() < 0.0001f) {
                right.set(1, 0, 0).cross(forward);
            }
            right.normalize();
            org.joml.Vector3f up = new org.joml.Vector3f(forward).cross(right).normalize();

            org.joml.Matrix4f rot = new org.joml.Matrix4f(
                right.x, right.y, right.z, 0,
                up.x, up.y, up.z, 0,
                forward.x, forward.y, forward.z, 0,
                0, 0, 0, 1
            );

            org.joml.Matrix4f transform = new org.joml.Matrix4f()
                .translate((float) pos.x(), (float) pos.y(), (float) pos.z())
                .mul(rot)
                .rotateY(yaw)
                .rotateX(pitch)
                .rotateZ(roll);

            for (RoadTemplate.RoadTemplateBlock b : template.getBlocks()) {
                org.joml.Vector3f local = new org.joml.Vector3f(b.x(), b.y(), b.z());
                org.joml.Vector3f worldPos = transform.transformPosition(local);

                int bx = (int) Math.floor(worldPos.x);
                int by = (int) Math.floor(worldPos.y);
                int bz = (int) Math.floor(worldPos.z);

                BlockState state = BuildingPreviewRenderer.resolveBlockState(b.blockState());
                if (state != null) {
                    uniqueBlocks.put(new BlockPos(bx, by, bz), state);
                }
            }
        }

        var blockRenderer = Minecraft.getInstance().getBlockRenderer();
        int light = 0xF000F0;
        int overlay = net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY;

        for (var entry : uniqueBlocks.entrySet()) {
            BlockPos bp = entry.getKey();
            poseStack.pushPose();
            poseStack.translate(bp.getX(), bp.getY(), bp.getZ());
            blockRenderer.renderSingleBlock(entry.getValue(), poseStack, bufferSource, light, overlay,
                    net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null);
            poseStack.popPose();
        }

        bufferSource.endBatch(net.minecraft.client.renderer.Sheets.cutoutBlockSheet());
        bufferSource.endBatch(net.minecraft.client.renderer.Sheets.translucentCullBlockSheet());
        bufferSource.endBatch(net.minecraft.client.renderer.Sheets.translucentItemSheet());

        // Connect line preview (bright green)
        VertexConsumer vcLines = bufferSource.getBuffer(RenderType.lines());
        var pose = poseStack.last();
        for (int i = 0; i < samplePoints.size() - 1; i++) {
            SplineVec3 p0 = samplePoints.get(i);
            SplineVec3 p1 = samplePoints.get(i + 1);
            line(vcLines, pose, (float) p0.x(), (float) p0.y() + 0.1f, (float) p0.z(),
                                (float) p1.x(), (float) p1.y() + 0.1f, (float) p1.z(),
                                0, 255, 120);
        }
    }
}
