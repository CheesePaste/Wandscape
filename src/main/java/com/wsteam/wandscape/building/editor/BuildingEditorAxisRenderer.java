package com.wsteam.wandscape.building.editor;

import org.slf4j.Logger;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Renders 3D coordinate axes (red +X, green +Y, blue +Z) as drag handles
 * at the AABB anchor and max corners in world space.
 *
 * <p>Each axis is a colored arrow: shaft line + arrowhead (4 lines).
 * The axis at anchor points outward (expand direction).
 * The axis at max points inward (shrink direction).
 *
 * <p>Uses Minecraft's {@code BufferBuilder} + {@code RenderType.lines()}
 * with JOML vectors for geometry.
 */
public final class BuildingEditorAxisRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final float ARROW_LEN = 1.5f;
    private static final float HEAD_LEN = 0.35f;
    private static final float HEAD_W = 0.15f;

    // Bright axis colors (positive direction)
    private static final int[] COL_X = {255, 50, 50, 230};   // red
    private static final int[] COL_Y = {50, 220, 50, 230};   // green
    private static final int[] COL_Z = {50, 100, 255, 230};  // blue
    // Dim axis colors (negative direction)
    private static final int[] COL_XN = {120, 30, 30, 160};
    private static final int[] COL_YN = {30, 120, 30, 160};
    private static final int[] COL_ZN = {30, 50, 120, 160};

    // Hovered axis highlight multiplier
    private static final float HOVER_BRIGHT = 0.4f;

    private static boolean registered = false;

    private BuildingEditorAxisRenderer() {}

    public static void register() {
        if (registered) return;
        registered = true;
        var bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        bus.addListener(RenderLevelStageEvent.class, BuildingEditorAxisRenderer::onRenderLevelStage);
        LOGGER.info("[BuildEditor] Axis renderer registered");
    }

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!BuildingEditorClientState.isEditing()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        BlockPos worldAnchor = BuildingEditorClientState.getWorldAnchor();
        if (worldAnchor == null) return;

        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        VertexConsumer vc = buf.getBuffer(RenderType.lines());

        // At the first-set corner (worldAnchor adjusted by editMin):
        // draw positive-direction arrows
        BlockPos corner = BuildingEditorClientState.getWorldMin();
        if (corner != null) {
            Vec3 hovered = BuildingEditorClientState.getHoveredAxisWorld();
            BuildingEditorClientState.AxisDrag hovering = BuildingEditorClientState.getHoveredAxis();
            drawArrow(vc, poseStack, corner, 1, 0, 0, COL_X, hovering, BuildingEditorClientState.AxisDrag.X_POS, hovered);
            drawArrow(vc, poseStack, corner, 0, 1, 0, COL_Y, hovering, BuildingEditorClientState.AxisDrag.Y_POS, hovered);
            drawArrow(vc, poseStack, corner, 0, 0, 1, COL_Z, hovering, BuildingEditorClientState.AxisDrag.Z_POS, hovered);
        }

        // At the max corner: draw negative-direction arrows (shrink handles)
        BlockPos maxCorner = BuildingEditorClientState.getWorldMax();
        if (maxCorner != null) {
            // Offset to the max face — draw arrows pointing IN from the next block
            Vec3 hovered = BuildingEditorClientState.getHoveredAxisWorld();
            BuildingEditorClientState.AxisDrag hovering = BuildingEditorClientState.getHoveredAxis();
            drawArrow(vc, poseStack, maxCorner, -1, 0, 0, COL_XN, hovering, BuildingEditorClientState.AxisDrag.X_NEG, hovered);
            drawArrow(vc, poseStack, maxCorner, 0, -1, 0, COL_YN, hovering, BuildingEditorClientState.AxisDrag.Y_NEG, hovered);
            drawArrow(vc, poseStack, maxCorner, 0, 0, -1, COL_ZN, hovering, BuildingEditorClientState.AxisDrag.Z_NEG, hovered);
        }

        buf.endBatch(RenderType.lines());
        poseStack.popPose();
    }

    // ── Arrow drawing ──

    private static void drawArrow(VertexConsumer vc, PoseStack poseStack,
                                   BlockPos base, int dx, int dy, int dz,
                                   int[] col, BuildingEditorClientState.AxisDrag hovering, BuildingEditorClientState.AxisDrag myAxis, Vec3 hoveredPos) {
        float bx = base.getX() + 0.5f;
        float by = base.getY() + 0.5f;
        float bz = base.getZ() + 0.5f;

        float tipX = bx + dx * ARROW_LEN;
        float tipY = by + dy * ARROW_LEN;
        float tipZ = bz + dz * ARROW_LEN;

        boolean hovered = (hovering == myAxis);
        float bright = hovered ? 1.0f + HOVER_BRIGHT : 1.0f;
        int r = Math.min(255, (int)(col[0] * bright));
        int g = Math.min(255, (int)(col[1] * bright));
        int b = Math.min(255, (int)(col[2] * bright));
        int a = hovered ? 255 : col[3];

        PoseStack.Pose pose = poseStack.last();

        // Shaft
        line(vc, pose, bx, by, bz, tipX, tipY, tipZ, r, g, b, a);

        // Arrowhead: 4 lines from tip back and out perpendicular
        // Compute two perpendicular vectors to the shaft direction
        float perp1X, perp1Y, perp1Z;
        float perp2X, perp2Y, perp2Z;

        if (dx != 0) {
            // Shaft along X → perp1 = Y, perp2 = Z
            perp1X = 0; perp1Y = 1; perp1Z = 0;
            perp2X = 0; perp2Y = 0; perp2Z = 1;
        } else if (dy != 0) {
            // Shaft along Y → perp1 = X, perp2 = Z
            perp1X = 1; perp1Y = 0; perp1Z = 0;
            perp2X = 0; perp2Y = 0; perp2Z = 1;
        } else {
            // Shaft along Z → perp1 = X, perp2 = Y
            perp1X = 1; perp1Y = 0; perp1Z = 0;
            perp2X = 0; perp2Y = 1; perp2Z = 0;
        }

        float hx = tipX - dx * HEAD_LEN;
        float hy = tipY - dy * HEAD_LEN;
        float hz = tipZ - dz * HEAD_LEN;

        // Four arrowhead lines
        float hw = HEAD_W;
        line(vc, pose, tipX, tipY, tipZ, hx + perp1X * hw, hy + perp1Y * hw, hz + perp1Z * hw, r, g, b, a);
        line(vc, pose, tipX, tipY, tipZ, hx - perp1X * hw, hy - perp1Y * hw, hz - perp1Z * hw, r, g, b, a);
        line(vc, pose, tipX, tipY, tipZ, hx + perp2X * hw, hy + perp2Y * hw, hz + perp2Z * hw, r, g, b, a);
        line(vc, pose, tipX, tipY, tipZ, hx - perp2X * hw, hy - perp2Y * hw, hz - perp2Z * hw, r, g, b, a);
    }

    // ── Hit-testing: which axis arrow is closest to the camera ray ──

    public static BuildingEditorClientState.AxisDrag hitTestAxis(Vec3 rayOrigin, Vec3 rayDir) {
        BlockPos worldMin = BuildingEditorClientState.getWorldMin();
        if (worldMin == null) return null;

        BuildingEditorClientState.AxisDrag best = null;
        double bestDist = Double.MAX_VALUE;

        // Test positive arrows from min corner
        for (var entry : AXIS_DIRS.entrySet()) {
            BuildingEditorClientState.AxisDrag axis = entry.getKey();
            int[] dir = entry.getValue();
            BlockPos base = worldMin;
            double dist = rayToArrowDist(rayOrigin, rayDir, base,
                    dir[0], dir[1], dir[2]);
            if (dist < 0.6 && dist < bestDist) { bestDist = dist; best = axis; }
        }

        // Test negative arrows from max corner
        BlockPos worldMax = BuildingEditorClientState.getWorldMax();
        if (worldMax != null) {
            for (var entry : NEG_AXIS_DIRS.entrySet()) {
                BuildingEditorClientState.AxisDrag axis = entry.getKey();
                int[] dir = entry.getValue();
                BlockPos base = worldMax;
                double dist = rayToArrowDist(rayOrigin, rayDir, base,
                        dir[0], dir[1], dir[2]);
                if (dist < 0.6 && dist < bestDist) { bestDist = dist; best = axis; }
            }
        }

        return best;
    }

    private static double rayToArrowDist(Vec3 rayOrigin, Vec3 rayDir,
                                          BlockPos base, int dx, int dy, int dz) {
        float bx = base.getX() + 0.5f;
        float by = base.getY() + 0.5f;
        float bz = base.getZ() + 0.5f;
        float tx = bx + dx * ARROW_LEN;
        float ty = by + dy * ARROW_LEN;
        float tz = bz + dz * ARROW_LEN;

        return rayToSegmentDist(rayOrigin, rayDir,
                new Vec3(bx, by, bz), new Vec3(tx, ty, tz));
    }

    /** Compute shortest distance from ray to line segment AB. */
    public static double rayToSegmentDist(Vec3 rayO, Vec3 rayD, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double abLenSq = ab.lengthSqr();
        if (abLenSq < 1e-12) return rayToPointDist(rayO, rayD, a);

        Vec3 ao = rayO.subtract(a);
        double raydotray = rayD.dot(rayD);
        double raydotab = rayD.dot(ab);
        double abdotab = ab.dot(ab);
        double raydotao = rayD.dot(ao);
        double abdotao = ab.dot(ao);

        double denom = raydotray * abdotab - raydotab * raydotab;
        if (Math.abs(denom) < 1e-12) {
            double tSeg = Math.max(0, Math.min(1, -abdotao / abdotab));
            Vec3 segPt = a.add(ab.scale(tSeg));
            return rayToPointDist(rayO, rayD, segPt);
        }

        double t = Math.max(0, (raydotab * abdotao - abdotab * raydotao) / denom);
        double u = Math.max(0, Math.min(1, (raydotray * abdotao - raydotab * raydotao) / denom));
        Vec3 rayPt = rayO.add(rayD.scale(t));
        Vec3 segPt = a.add(ab.scale(u));
        return rayPt.distanceTo(segPt);
    }

    private static double rayToPointDist(Vec3 rayO, Vec3 rayD, Vec3 pt) {
        Vec3 toPt = pt.subtract(rayO);
        double t = toPt.dot(rayD);
        if (t <= 0) return rayO.distanceTo(pt);
        return rayO.add(rayD.scale(t)).distanceTo(pt);
    }

    // ── Axis direction lookup ──

    private static final java.util.Map<BuildingEditorClientState.AxisDrag, int[]> AXIS_DIRS = java.util.Map.of(
            BuildingEditorClientState.AxisDrag.X_POS, new int[]{1, 0, 0},
            BuildingEditorClientState.AxisDrag.Y_POS, new int[]{0, 1, 0},
            BuildingEditorClientState.AxisDrag.Z_POS, new int[]{0, 0, 1}
    );

    private static final java.util.Map<BuildingEditorClientState.AxisDrag, int[]> NEG_AXIS_DIRS = java.util.Map.of(
            BuildingEditorClientState.AxisDrag.X_NEG, new int[]{-1, 0, 0},
            BuildingEditorClientState.AxisDrag.Y_NEG, new int[]{0, -1, 0},
            BuildingEditorClientState.AxisDrag.Z_NEG, new int[]{0, 0, -1}
    );

    // ── Utility ──

    private static void line(VertexConsumer vc, com.mojang.blaze3d.vertex.PoseStack.Pose pose,
                              float x1, float y1, float z1, float x2, float y2, float z2,
                              int r, int g, int b, int a) {
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, a).setNormal(pose, 0, 1, 0);
        vc.addVertex(pose, x2, y2, z2).setColor(r, g, b, a).setNormal(pose, 0, 1, 0);
    }
}
