package com.wsteam.wandscape.building.scanner.client.gizmo;

import java.util.List;

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

/**
 * 3D World Renderer for the Building Scanner Gizmo Visual Adjuster.
 * Renders X-Ray translucent bounding box, glowing corner anchors, and 3D translation Gizmo arrows.
 */
public final class ScannerGizmoRenderer {
    private static final String TAG = "ScannerGizmoRenderer";
    private static boolean registered = false;

    // Gizmo arrow dimensions
    public static final float SHAFT_LEN = 1.4f;
    public static final float SHAFT_THICKNESS = 0.05f;
    public static final float HEAD_LEN = 0.35f;
    public static final float HEAD_THICKNESS = 0.12f;

    // Colors
    private static final int[] COL_X = {255, 60, 60, 220};
    private static final int[] COL_XN = {180, 40, 40, 160};
    private static final int[] COL_Y = {60, 255, 60, 220};
    private static final int[] COL_YN = {40, 180, 40, 160};
    private static final int[] COL_Z = {60, 120, 255, 220};
    private static final int[] COL_ZN = {40, 60, 180, 160};

    private ScannerGizmoRenderer() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, ScannerGizmoRenderer::onRenderLevelStage);
        Log.info(TAG, "ScannerGizmoRenderer registered");
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

        // ── PASS 1: XRAY QUADS (Faces & Anchor Cubes & Gizmo Arrows) ──
        VertexConsumer vcQuads = buf.getBuffer(GizmoRenderType.XRAY_QUADS);

        // Translucent bounding box fill
        fillAABB(vcQuads, pose, box, 255, 150, 50, 40);

        // Min anchor corner cube (Cyan)
        boolean minSelected = ScannerGizmoState.getSelectedAnchor() == ScannerGizmoState.Anchor.MIN;
        int minA = minSelected ? 255 : 160;
        Vec3 minAnchorPos = ScannerGizmoState.getWorldAnchorPos(ScannerGizmoState.Anchor.MIN);
        AABB minCube = new AABB(minAnchorPos.x - 0.2, minAnchorPos.y - 0.2, minAnchorPos.z - 0.2,
                minAnchorPos.x + 0.2, minAnchorPos.y + 0.2, minAnchorPos.z + 0.2);
        fillAABB(vcQuads, pose, minCube, 0, 229, 255, minA);

        // Max anchor corner cube (Gold)
        boolean maxSelected = ScannerGizmoState.getSelectedAnchor() == ScannerGizmoState.Anchor.MAX;
        int maxA = maxSelected ? 255 : 160;
        Vec3 maxAnchorPos = ScannerGizmoState.getWorldAnchorPos(ScannerGizmoState.Anchor.MAX);
        AABB maxCube = new AABB(maxAnchorPos.x - 0.2, maxAnchorPos.y - 0.2, maxAnchorPos.z - 0.2,
                maxAnchorPos.x + 0.2, maxAnchorPos.y + 0.2, maxAnchorPos.z + 0.2);
        fillAABB(vcQuads, pose, maxCube, 255, 215, 0, maxA);

        // Draw Gizmo Arrows at selected anchor
        Vec3 activePos = ScannerGizmoState.getWorldAnchorPos(ScannerGizmoState.getSelectedAnchor());
        drawGizmoArrows(vcQuads, pose, activePos);

        // Door indicators
        List<BlockOffset> doors = scanner.getDoorOffsets();
        for (BlockOffset d : doors) {
            double dx = bePos.getX() + d.x();
            double dy = bePos.getY() + d.y();
            double dz = bePos.getZ() + d.z();
            AABB doorBox = new AABB(dx, dy, dz, dx + 1.0, dy + 1.0, dz + 1.0);
            fillAABB(vcQuads, pose, doorBox, 255, 50, 50, 70);
        }

        buf.endBatch(GizmoRenderType.XRAY_QUADS);

        // ── PASS 2: XRAY LINES (Bounding Box Wireframe & Grid Edges) ──
        VertexConsumer vcLines = buf.getBuffer(GizmoRenderType.XRAY_LINES);

        // Outer box wireframe
        drawBoxLines(vcLines, pose, box, 255, 180, 50, 255);

        // Door box wireframes
        for (BlockOffset d : doors) {
            double dx = bePos.getX() + d.x();
            double dy = bePos.getY() + d.y();
            double dz = bePos.getZ() + d.z();
            AABB doorBox = new AABB(dx, dy, dz, dx + 1.0, dy + 1.0, dz + 1.0);
            drawBoxLines(vcLines, pose, doorBox, 255, 80, 80, 240);
        }

        buf.endBatch(GizmoRenderType.XRAY_LINES);
        poseStack.popPose();
    }

    private static void drawGizmoArrows(VertexConsumer vc, PoseStack.Pose pose, Vec3 pos) {
        ScannerGizmoState.AxisDrag hovering = ScannerGizmoState.getHoveredAxis();
        ScannerGizmoState.AxisDrag dragging = ScannerGizmoState.getDraggingAxis();
        ScannerGizmoState.AxisDrag activeAxis = (dragging != ScannerGizmoState.AxisDrag.NONE) ? dragging : hovering;

        double x = pos.x;
        double y = pos.y;
        double z = pos.z;

        // X Axis (+ / -)
        drawGizmoArrow(vc, pose, x, y, z, ScannerGizmoState.AxisDrag.X_POS, 1, 0, 0, COL_X, activeAxis == ScannerGizmoState.AxisDrag.X_POS);
        drawGizmoArrow(vc, pose, x, y, z, ScannerGizmoState.AxisDrag.X_NEG, -1, 0, 0, COL_XN, activeAxis == ScannerGizmoState.AxisDrag.X_NEG);

        // Y Axis (+ / -)
        drawGizmoArrow(vc, pose, x, y, z, ScannerGizmoState.AxisDrag.Y_POS, 0, 1, 0, COL_Y, activeAxis == ScannerGizmoState.AxisDrag.Y_POS);
        drawGizmoArrow(vc, pose, x, y, z, ScannerGizmoState.AxisDrag.Y_NEG, 0, -1, 0, COL_YN, activeAxis == ScannerGizmoState.AxisDrag.Y_NEG);

        // Z Axis (+ / -)
        drawGizmoArrow(vc, pose, x, y, z, ScannerGizmoState.AxisDrag.Z_POS, 0, 0, 1, COL_Z, activeAxis == ScannerGizmoState.AxisDrag.Z_POS);
        drawGizmoArrow(vc, pose, x, y, z, ScannerGizmoState.AxisDrag.Z_NEG, 0, 0, -1, COL_ZN, activeAxis == ScannerGizmoState.AxisDrag.Z_NEG);
    }

    private static void drawGizmoArrow(VertexConsumer vc, PoseStack.Pose pose, double x, double y, double z,
                                       ScannerGizmoState.AxisDrag axis, int dx, int dy, int dz, int[] col, boolean highlight) {
        float bright = highlight ? 1.5f : 1.0f;
        int r = highlight ? 255 : Math.min(255, (int)(col[0] * bright));
        int g = highlight ? 255 : Math.min(255, (int)(col[1] * bright));
        int b = highlight ? 60  : Math.min(255, (int)(col[2] * bright));
        int a = highlight ? 255 : col[3];

        // 1. Shaft
        AABB shaft = getGizmoAxisAABB(x, y, z, axis, SHAFT_LEN, SHAFT_THICKNESS);
        fillAABB(vc, pose, shaft, r, g, b, a);

        // 2. Head
        double headX = x + dx * SHAFT_LEN;
        double headY = y + dy * SHAFT_LEN;
        double headZ = z + dz * SHAFT_LEN;
        AABB head = getGizmoAxisAABB(headX, headY, headZ, axis, HEAD_LEN, HEAD_THICKNESS);
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

        public static final RenderType XRAY_QUADS = create(
                "scanner_gizmo_quads",
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

        public static final RenderType XRAY_LINES = create(
                "scanner_gizmo_lines",
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
                        .setLineState(new LineStateShard(java.util.OptionalDouble.of(3.0)))
                        .createCompositeState(false)
        );
    }
}