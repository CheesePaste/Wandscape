package com.wsteam.wandscape.building.scanner.client.gizmo;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.scanner.CreativeScannerBlockEntity;
import com.wsteam.wandscape.shared.log.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;

/**
 * 3D World Renderer for the Building Scanner Gizmo Visual Adjuster.
 * Uses dedicated X-Ray depth-disabled render passes and distance-compensated scaling
 * to ensure the Gizmo is always drawn strictly ON TOP of solid blocks, entities, and the bounding box.
 */
public final class ScannerGizmoRenderer {
    private static final String TAG = "ScannerGizmoRenderer";
    private static boolean registered = false;

    // Base Gizmo arrow dimensions (multiplied by dynamic distance scale)
    public static final float BASE_SHAFT_LEN = 1.5f;
    public static final float BASE_SHAFT_THICKNESS = 0.06f;
    public static final float BASE_HEAD_LEN = 0.38f;
    public static final float BASE_HEAD_THICKNESS = 0.14f;

    // Colors
    private static final int[] COL_X = {255, 60, 60, 240};
    private static final int[] COL_XN = {180, 40, 40, 180};
    private static final int[] COL_Y = {60, 255, 60, 240};
    private static final int[] COL_YN = {40, 180, 40, 180};
    private static final int[] COL_Z = {60, 130, 255, 240};
    private static final int[] COL_ZN = {40, 70, 180, 180};

    private ScannerGizmoRenderer() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, ScannerGizmoRenderer::onRenderLevelStage);
        Log.info(TAG, "ScannerGizmoRenderer registered");
    }

    public static float getDistanceScale(Vec3 camPos, Vec3 anchorPos) {
        double dist = camPos.distanceTo(anchorPos);
        return (float) Math.max(0.6, dist * 0.09);
    }

    private static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!ScannerGizmoState.isActive()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        CreativeScannerBlockEntity scanner = ScannerGizmoState.getScanner();
        if (scanner == null || scanner.getLevel() == null) return;

        Minecraft mc = Minecraft.getInstance();
        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);
        PoseStack.Pose pose = poseStack.last();

        BlockPos bePos = scanner.getBlockPos();
        BlockOffset bMin = ScannerGizmoState.getCurrentMin();
        BlockOffset bMax = ScannerGizmoState.getCurrentMax();

        double minX = bePos.getX() + Math.min(bMin.x(), bMax.x());
        double minY = bePos.getY() + Math.min(bMin.y(), bMax.y());
        double minZ = bePos.getZ() + Math.min(bMin.z(), bMax.z());
        double maxX = bePos.getX() + Math.max(bMin.x(), bMax.x()) + 1.0;
        double maxY = bePos.getY() + Math.max(bMin.y(), bMax.y()) + 1.0;
        double maxZ = bePos.getZ() + Math.max(bMin.z(), bMax.z()) + 1.0;

        AABB box = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        List<BlockOffset> doors = scanner.getDoorOffsets();

        // ── PASS 1: Translucent Bounding Box Faces & Door markers ──
        VertexConsumer vcBoxQuads = buf.getBuffer(GizmoRenderType.XRAY_BOX_QUADS);
        fillAABB(vcBoxQuads, pose, box, 255, 150, 50, 35);

        for (BlockOffset d : doors) {
            double dx = bePos.getX() + d.x();
            double dy = bePos.getY() + d.y();
            double dz = bePos.getZ() + d.z();
            AABB doorBox = new AABB(dx, dy, dz, dx + 1.0, dy + 1.0, dz + 1.0);
            fillAABB(vcBoxQuads, pose, doorBox, 255, 50, 50, 60);
        }
        buf.endBatch(GizmoRenderType.XRAY_BOX_QUADS);

        // ── PASS 2: Bounding Box Wireframe Edges ──
        VertexConsumer vcBoxLines = buf.getBuffer(GizmoRenderType.XRAY_BOX_LINES);
        drawBoxLines(vcBoxLines, pose, box, 255, 180, 50, 230);

        for (BlockOffset d : doors) {
            double dx = bePos.getX() + d.x();
            double dy = bePos.getY() + d.y();
            double dz = bePos.getZ() + d.z();
            AABB doorBox = new AABB(dx, dy, dz, dx + 1.0, dy + 1.0, dz + 1.0);
            drawBoxLines(vcBoxLines, pose, doorBox, 255, 80, 80, 200);
        }
        buf.endBatch(GizmoRenderType.XRAY_BOX_LINES);

        // ── PASS 3: ANCHOR CORNER CUBES & 3D GIZMO ARROWS (Rendered LAST on top of everything) ──
        VertexConsumer vcGizmoQuads = buf.getBuffer(GizmoRenderType.XRAY_GIZMO_QUADS);

        // Min anchor corner cube (Cyan)
        boolean minSelected = ScannerGizmoState.getSelectedAnchor() == ScannerGizmoState.Anchor.MIN;
        Vec3 minAnchorPos = ScannerGizmoState.getWorldAnchorPos(ScannerGizmoState.Anchor.MIN);
        float minScale = getDistanceScale(camPos, minAnchorPos);
        double minCubeRadius = 0.22 * minScale;
        AABB minCube = new AABB(minAnchorPos.x - minCubeRadius, minAnchorPos.y - minCubeRadius, minAnchorPos.z - minCubeRadius,
                minAnchorPos.x + minCubeRadius, minAnchorPos.y + minCubeRadius, minAnchorPos.z + minCubeRadius);
        int minA = minSelected ? 255 : 180;
        fillAABB(vcGizmoQuads, pose, minCube, 0, 229, 255, minA);

        // Max anchor corner cube (Gold)
        boolean maxSelected = ScannerGizmoState.getSelectedAnchor() == ScannerGizmoState.Anchor.MAX;
        Vec3 maxAnchorPos = ScannerGizmoState.getWorldAnchorPos(ScannerGizmoState.Anchor.MAX);
        float maxScale = getDistanceScale(camPos, maxAnchorPos);
        double maxCubeRadius = 0.22 * maxScale;
        AABB maxCube = new AABB(maxAnchorPos.x - maxCubeRadius, maxAnchorPos.y - maxCubeRadius, maxAnchorPos.z - maxCubeRadius,
                maxAnchorPos.x + maxCubeRadius, maxAnchorPos.y + maxCubeRadius, maxAnchorPos.z + maxCubeRadius);
        int maxA = maxSelected ? 255 : 180;
        fillAABB(vcGizmoQuads, pose, maxCube, 255, 215, 0, maxA);

        // Active anchor 3D Translation Gizmo
        Vec3 activePos = ScannerGizmoState.getWorldAnchorPos(ScannerGizmoState.getSelectedAnchor());
        float activeScale = getDistanceScale(camPos, activePos);
        drawGizmoArrows(vcGizmoQuads, pose, activePos, activeScale);

        buf.endBatch(GizmoRenderType.XRAY_GIZMO_QUADS);
        poseStack.popPose();
    }

    private static void drawGizmoArrows(VertexConsumer vc, PoseStack.Pose pose, Vec3 pos, float scale) {
        ScannerGizmoState.AxisDrag hovering = ScannerGizmoState.getHoveredAxis();
        ScannerGizmoState.AxisDrag dragging = ScannerGizmoState.getDraggingAxis();
        ScannerGizmoState.AxisDrag activeAxis = (dragging != ScannerGizmoState.AxisDrag.NONE) ? dragging : hovering;

        double x = pos.x;
        double y = pos.y;
        double z = pos.z;

        // X Axis (+ / -)
        drawGizmoArrow(vc, pose, x, y, z, ScannerGizmoState.AxisDrag.X_POS, 1, 0, 0, COL_X, activeAxis == ScannerGizmoState.AxisDrag.X_POS, scale);
        drawGizmoArrow(vc, pose, x, y, z, ScannerGizmoState.AxisDrag.X_NEG, -1, 0, 0, COL_XN, activeAxis == ScannerGizmoState.AxisDrag.X_NEG, scale);

        // Y Axis (+ / -)
        drawGizmoArrow(vc, pose, x, y, z, ScannerGizmoState.AxisDrag.Y_POS, 0, 1, 0, COL_Y, activeAxis == ScannerGizmoState.AxisDrag.Y_POS, scale);
        drawGizmoArrow(vc, pose, x, y, z, ScannerGizmoState.AxisDrag.Y_NEG, 0, -1, 0, COL_YN, activeAxis == ScannerGizmoState.AxisDrag.Y_NEG, scale);

        // Z Axis (+ / -)
        drawGizmoArrow(vc, pose, x, y, z, ScannerGizmoState.AxisDrag.Z_POS, 0, 0, 1, COL_Z, activeAxis == ScannerGizmoState.AxisDrag.Z_POS, scale);
        drawGizmoArrow(vc, pose, x, y, z, ScannerGizmoState.AxisDrag.Z_NEG, 0, 0, -1, COL_ZN, activeAxis == ScannerGizmoState.AxisDrag.Z_NEG, scale);
    }

    private static void drawGizmoArrow(VertexConsumer vc, PoseStack.Pose pose, double x, double y, double z,
                                       ScannerGizmoState.AxisDrag axis, int dx, int dy, int dz, int[] col, boolean highlight, float scale) {
        float bright = highlight ? 1.4f : 1.0f;
        int r = highlight ? 255 : Math.min(255, (int)(col[0] * bright));
        int g = highlight ? 255 : Math.min(255, (int)(col[1] * bright));
        int b = highlight ? 60  : Math.min(255, (int)(col[2] * bright));
        int a = highlight ? 255 : col[3];

        float shaftLen = BASE_SHAFT_LEN * scale;
        float shaftThickness = BASE_SHAFT_THICKNESS * scale;
        float headLen = BASE_HEAD_LEN * scale;
        float headThickness = BASE_HEAD_THICKNESS * scale;

        // 1. Shaft
        AABB shaft = getGizmoAxisAABB(x, y, z, axis, shaftLen, shaftThickness);
        fillAABB(vc, pose, shaft, r, g, b, a);

        // 2. Head
        double headX = x + dx * shaftLen;
        double headY = y + dy * shaftLen;
        double headZ = z + dz * shaftLen;
        AABB head = getGizmoAxisAABB(headX, headY, headZ, axis, headLen, headThickness);
        fillAABB(vc, pose, head, r, g, b, a);
    }

    public static AABB getGizmoAxisAABB(double x, double y, double z, ScannerGizmoState.AxisDrag axis, float length, float thickness) {
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

        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static void fillAABB(VertexConsumer vc, PoseStack.Pose pose, AABB box, int r, int g, int b, int a) {
        float x0 = (float)box.minX, y0 = (float)box.minY, z0 = (float)box.minZ;
        float x1 = (float)box.maxX, y1 = (float)box.maxY, z1 = (float)box.maxZ;

        quad(vc, pose, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a);
        quad(vc, pose, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b, a);
        quad(vc, pose, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, a);
        quad(vc, pose, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b, a);
        quad(vc, pose, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, a);
        quad(vc, pose, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, a);
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float x4, float y4, float z4, int r, int g, int b, int a) {
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(pose, x2, y2, z2).setColor(r, g, b, a);
        vc.addVertex(pose, x3, y3, z3).setColor(r, g, b, a);
        vc.addVertex(pose, x4, y4, z4).setColor(r, g, b, a);
    }

    private static void drawBoxLines(VertexConsumer vc, PoseStack.Pose pose, AABB box, int r, int g, int b, int a) {
        float x0 = (float)box.minX, y0 = (float)box.minY, z0 = (float)box.minZ;
        float x1 = (float)box.maxX, y1 = (float)box.maxY, z1 = (float)box.maxZ;

        // Bottom
        line(vc, pose, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(vc, pose, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(vc, pose, x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(vc, pose, x0, y0, z1, x0, y0, z0, r, g, b, a);

        // Top
        line(vc, pose, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(vc, pose, x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(vc, pose, x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(vc, pose, x0, y1, z0, x0, y1, z1, r, g, b, a);

        // Vertical pillars
        line(vc, pose, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(vc, pose, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(vc, pose, x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(vc, pose, x0, y0, z1, x0, y1, z1, r, g, b, a);
    }

    private static void line(VertexConsumer vc, PoseStack.Pose pose, float x1, float y1, float z1, float x2, float y2, float z2, int r, int g, int b, int a) {
        vc.addVertex(pose, x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(pose, x2, y2, z2).setColor(r, g, b, a);
    }

    // ── X-Ray custom shader definitions ──

    static abstract class GizmoRenderType extends RenderType {
        private GizmoRenderType(String name, VertexFormat format, VertexFormat.Mode mode, int bufSize, boolean affectsCrumbling, boolean sortOnUpload, Runnable setupState, Runnable clearState) {
            super(name, format, mode, bufSize, affectsCrumbling, sortOnUpload, setupState, clearState);
        }

        public static final RenderType XRAY_BOX_QUADS = create(
                "scanner_gizmo_box_quads",
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS,
                256,
                false,
                false,
                CompositeState.builder()
                        .setShaderState(POSITION_COLOR_SHADER)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(NO_DEPTH_TEST)
                        .setCullState(NO_CULL)
                        .createCompositeState(false)
        );

        public static final RenderType XRAY_BOX_LINES = create(
                "scanner_gizmo_box_lines",
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.DEBUG_LINES,
                256,
                false,
                false,
                CompositeState.builder()
                        .setShaderState(POSITION_COLOR_SHADER)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(NO_DEPTH_TEST)
                        .setCullState(NO_CULL)
                        .setLineState(new LineStateShard(java.util.OptionalDouble.of(2.5)))
                        .createCompositeState(false)
        );

        public static final RenderType XRAY_GIZMO_QUADS = create(
                "scanner_gizmo_arrows_quads",
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS,
                256,
                false,
                false,
                CompositeState.builder()
                        .setShaderState(POSITION_COLOR_SHADER)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(NO_DEPTH_TEST)
                        .setCullState(NO_CULL)
                        .createCompositeState(false)
        );
    }
}