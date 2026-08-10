package com.wsteam.wandscape.road.client;

import java.util.List;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.wsteam.wandscape.road.core.CurveSample;
import com.wsteam.wandscape.road.core.RoadTemplate;
import com.wsteam.wandscape.road.core.SplineModel;
import com.wsteam.wandscape.road.core.SplinePoint;
import com.wsteam.wandscape.road.core.SplineVec3;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Renders the spline curves, control points, connection lines, and Gizmo handles.
 * Uses X-Ray shaders so they are visible through solid blocks.
 */
public final class SplineEditorRenderer {
    private static final String TAG = "SplineEditorRenderer";
    private static boolean registered = false;

    // Gizmo constants
    private static final float SHAFT_LEN = 1.5f;
    private static final float SHAFT_THICKNESS = 0.05f;
    private static final float HEAD_LEN = 0.3f;
    private static final float HEAD_THICKNESS = 0.12f;

    // Color definitions
    private static final int[] COL_X = {255, 60, 60, 200};
    private static final int[] COL_Y = {60, 255, 60, 200};
    private static final int[] COL_Z = {60, 100, 255, 200};
    private static final int[] COL_XN = {160, 40, 40, 160};
    private static final int[] COL_YN = {40, 160, 40, 160};
    private static final int[] COL_ZN = {40, 60, 160, 160};

    private SplineEditorRenderer() {}

    public static void register() {
        if (registered) return;
        registered = true;
        var bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        bus.addListener(RenderLevelStageEvent.class, SplineEditorRenderer::onRenderLevelStage);
        Log.info(TAG, "[SplineEditor] Renderer registered");
    }

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!SplineEditorClientState.isEditing()) return;
        if (RoadPlacementState.getActiveTool() != RoadPlacementState.ToolMode.SPLINE) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        PoseStack.Pose pose = poseStack.last();
        SplineModel model = SplineEditorClientState.getModel();

        // Pass 1: Render all solid QUADS
        VertexConsumer vcQuads = buf.getBuffer(SplineRenderType.XRAY_QUADS);
        drawSplinePointsQuads(vcQuads, pose, model);
        drawAxisGizmo(vcQuads, pose, model);
        buf.endBatch(SplineRenderType.XRAY_QUADS);

        // Pass 2: Array Generation preview (Actual Block Models)
        drawArrayPreview(buf, poseStack, model);

        // Pass 3: Render all debug LINES
        VertexConsumer vcLines = buf.getBuffer(SplineRenderType.XRAY_LINES);
        drawSplineCurve(vcLines, pose, model);
        drawSplinePointsLines(vcLines, pose, model);
        buf.endBatch(SplineRenderType.XRAY_LINES);
        poseStack.popPose();
    }

    private static void drawSplineCurve(VertexConsumer vc, PoseStack.Pose pose, SplineModel model) {
        if (model.getSegmentsCount() == 0) return;

        // Tessellate curves with a fine resolution (0.3 blocks step)
        List<CurveSample> samples = model.tessellate(0.3);
        if (samples.size() < 2) return;

        // Draw spline as a bright green path
        int r = 0, g = 255, b = 120, a = 255;

        for (int i = 0; i < samples.size() - 1; i++) {
            SplineVec3 p0 = samples.get(i).position();
            SplineVec3 p1 = samples.get(i + 1).position();
            
            vc.addVertex(pose, (float)p0.x(), (float)p0.y(), (float)p0.z()).setColor(r, g, b, a);
            vc.addVertex(pose, (float)p1.x(), (float)p1.y(), (float)p1.z()).setColor(r, g, b, a);
        }

        // Draw connecting segment if closed
        if (model.isClosed() && samples.size() > 2) {
            SplineVec3 p0 = samples.get(samples.size() - 1).position();
            SplineVec3 p1 = samples.get(0).position();
            vc.addVertex(pose, (float)p0.x(), (float)p0.y(), (float)p0.z()).setColor(r, g, b, a);
            vc.addVertex(pose, (float)p1.x(), (float)p1.y(), (float)p1.z()).setColor(r, g, b, a);
        }
    }

    private static void drawSplinePointsQuads(VertexConsumer vcQuads, PoseStack.Pose pose, SplineModel model) {
        int selectedIdx = SplineEditorClientState.getSelectedPointIndex();
        SplineEditorClientState.SelectionType selectedType = SplineEditorClientState.getSelectedType();
        boolean isEditMode = SplineEditorClientState.getEditMode() == SplineEditorClientState.EditMode.EDIT;

        for (int i = 0; i < model.getPoints().size(); i++) {
            SplinePoint pt = model.getPoints().get(i);

            // 1. Draw Anchor Box (Blue, changes to yellow if selected)
            boolean anchorSelected = (i == selectedIdx && selectedType == SplineEditorClientState.SelectionType.ANCHOR);
            int[] anchorCol = anchorSelected ? new int[]{255, 180, 0, 255} : new int[]{50, 100, 255, 180};
            AABB anchorBox = getPointBox(pt.getAnchor(), 0.15);
            fillAABB(vcQuads, pose, anchorBox, anchorCol[0], anchorCol[1], anchorCol[2], anchorCol[3]);

            if (isEditMode) {
                // 2. Draw Prev Handle Box (Cyan, changes to yellow if selected)
                boolean prevSelected = (i == selectedIdx && selectedType == SplineEditorClientState.SelectionType.CONTROL_PREV);
                int[] prevCol = prevSelected ? new int[]{255, 180, 0, 255} : new int[]{0, 220, 220, 160};
                AABB prevBox = getPointBox(pt.getControlPrev(), 0.08);
                fillAABB(vcQuads, pose, prevBox, prevCol[0], prevCol[1], prevCol[2], prevCol[3]);

                // 3. Draw Next Handle Box (Cyan, changes to yellow if selected)
                boolean nextSelected = (i == selectedIdx && selectedType == SplineEditorClientState.SelectionType.CONTROL_NEXT);
                int[] nextCol = nextSelected ? new int[]{255, 180, 0, 255} : new int[]{0, 220, 220, 160};
                AABB nextBox = getPointBox(pt.getControlNext(), 0.08);
                fillAABB(vcQuads, pose, nextBox, nextCol[0], nextCol[1], nextCol[2], nextCol[3]);
            }
        }
    }

    private static void drawArrayPreview(MultiBufferSource buf, PoseStack poseStack, SplineModel model) {
        if (!SplineEditorClientState.isArrayPreview()) return;

        double stepDist = SplineEditorClientState.getArrayStepDistance();
        List<CurveSample> samples = model.tessellate(stepDist);
        if (samples.isEmpty()) return;

        RoadTemplate template = SplineEditorClientState.getActiveTemplate();
        if (template == null || template.getBlocks().isEmpty()) return;

        float roll = (float) Math.toRadians(SplineEditorClientState.getArrayOffsetRoll());
        float pitch = (float) Math.toRadians(SplineEditorClientState.getArrayOffsetPitch());
        float yaw = (float) Math.toRadians(SplineEditorClientState.getArrayOffsetYaw());

        org.joml.Vector3f globalUp = new org.joml.Vector3f(0, 1, 0);

        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.renderer.block.BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
        int light = 0xF000F0; // FULL_BRIGHT
        int overlay = net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY;

        java.util.Map<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> uniqueBlocks = new java.util.HashMap<>();

        for (CurveSample sample : samples) {
            SplineVec3 pos = sample.position();
            SplineVec3 tan = sample.tangent();

            org.joml.Vector3f forward = new org.joml.Vector3f((float)tan.x(), (float)tan.y(), (float)tan.z()).normalize();
            org.joml.Vector3f right = new org.joml.Vector3f(globalUp).cross(forward);
            if (right.lengthSquared() < 0.0001f) {
                right.set(1, 0, 0).cross(forward);
            }
            right.normalize();
            org.joml.Vector3f up = new org.joml.Vector3f(forward).cross(right).normalize();

            // Build rotation matrix from basis vectors (column-major order)
            org.joml.Matrix4f rot = new org.joml.Matrix4f(
                right.x, right.y, right.z, 0,
                up.x, up.y, up.z, 0,
                forward.x, forward.y, forward.z, 0,
                0, 0, 0, 1
            );

            org.joml.Matrix4f transform = new org.joml.Matrix4f()
                .translate((float)pos.x(), (float)pos.y(), (float)pos.z())
                .mul(rot)
                .rotateY(yaw)
                .rotateX(pitch)
                .rotateZ(roll);

            for (RoadTemplate.RoadTemplateBlock block : template.getBlocks()) {
                org.joml.Vector3f local = new org.joml.Vector3f(block.x(), block.y(), block.z());
                org.joml.Vector3f worldPos = transform.transformPosition(local);

                int bx = (int) Math.floor(worldPos.x);
                int by = (int) Math.floor(worldPos.y);
                int bz = (int) Math.floor(worldPos.z);

                net.minecraft.world.level.block.state.BlockState state = 
                    com.wsteam.wandscape.shared.ui.util.BuildingPreviewRenderer.resolveBlockState(block.blockState());
                
                if (state != null) {
                    uniqueBlocks.put(new net.minecraft.core.BlockPos(bx, by, bz), state);
                }
            }
        }

        for (java.util.Map.Entry<net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.BlockState> entry : uniqueBlocks.entrySet()) {
            net.minecraft.core.BlockPos bp = entry.getKey();
            poseStack.pushPose();
            poseStack.translate(bp.getX(), bp.getY(), bp.getZ());
            blockRenderer.renderSingleBlock(entry.getValue(), poseStack, buf, light, overlay);
            poseStack.popPose();
        }
    }

    private static void drawSplinePointsLines(VertexConsumer vcLines, PoseStack.Pose pose, SplineModel model) {
        boolean isEditMode = SplineEditorClientState.getEditMode() == SplineEditorClientState.EditMode.EDIT;
        if (!isEditMode) return;

        for (int i = 0; i < model.getPoints().size(); i++) {
            SplinePoint pt = model.getPoints().get(i);
            // 4. Draw dashed-style connection lines between anchor and handles (White)
            drawControlLine(vcLines, pose, pt.getAnchor(), pt.getControlPrev());
            drawControlLine(vcLines, pose, pt.getAnchor(), pt.getControlNext());
        }
    }

    private static AABB getPointBox(SplineVec3 pos, double size) {
        return new AABB(pos.x() - size, pos.y() - size, pos.z() - size,
                        pos.x() + size, pos.y() + size, pos.z() + size);
    }

    private static void drawControlLine(VertexConsumer vc, PoseStack.Pose pose, SplineVec3 p0, SplineVec3 p1) {
        vc.addVertex(pose, (float)p0.x(), (float)p0.y(), (float)p0.z()).setColor(220, 220, 220, 160);
        vc.addVertex(pose, (float)p1.x(), (float)p1.y(), (float)p1.z()).setColor(220, 220, 220, 160);
    }

    private static void drawAxisGizmo(VertexConsumer vc, PoseStack.Pose pose, SplineModel model) {
        int selectedIdx = SplineEditorClientState.getSelectedPointIndex();
        SplineEditorClientState.SelectionType selectedType = SplineEditorClientState.getSelectedType();
        if (selectedIdx == -1 || selectedType == SplineEditorClientState.SelectionType.NONE) return;

        SplinePoint pt = model.getPoints().get(selectedIdx);
        SplineVec3 ptPos = switch (selectedType) {
            case ANCHOR -> pt.getAnchor();
            case CONTROL_PREV -> pt.getControlPrev();
            case CONTROL_NEXT -> pt.getControlNext();
            default -> null;
        };

        if (ptPos == null) return;

        SplineEditorClientState.AxisDrag hovering = SplineEditorClientState.getHoveredAxis();
        SplineEditorClientState.AxisDrag dragging = SplineEditorClientState.getDraggingAxis();
        
        SplineEditorClientState.AxisDrag activeAxis = (dragging != SplineEditorClientState.AxisDrag.NONE) ? dragging : hovering;

        double x = ptPos.x();
        double y = ptPos.y();
        double z = ptPos.z();

        // X Axis (+ / -)
        drawGizmoArrow(vc, pose, x, y, z, SplineEditorClientState.AxisDrag.X_POS, 1, 0, 0, COL_X, activeAxis == SplineEditorClientState.AxisDrag.X_POS);
        drawGizmoArrow(vc, pose, x, y, z, SplineEditorClientState.AxisDrag.X_NEG, -1, 0, 0, COL_XN, activeAxis == SplineEditorClientState.AxisDrag.X_NEG);

        // Y Axis (+ / -)
        drawGizmoArrow(vc, pose, x, y, z, SplineEditorClientState.AxisDrag.Y_POS, 0, 1, 0, COL_Y, activeAxis == SplineEditorClientState.AxisDrag.Y_POS);
        drawGizmoArrow(vc, pose, x, y, z, SplineEditorClientState.AxisDrag.Y_NEG, 0, -1, 0, COL_YN, activeAxis == SplineEditorClientState.AxisDrag.Y_NEG);

        // Z Axis (+ / -)
        drawGizmoArrow(vc, pose, x, y, z, SplineEditorClientState.AxisDrag.Z_POS, 0, 0, 1, COL_Z, activeAxis == SplineEditorClientState.AxisDrag.Z_POS);
        drawGizmoArrow(vc, pose, x, y, z, SplineEditorClientState.AxisDrag.Z_NEG, 0, 0, -1, COL_ZN, activeAxis == SplineEditorClientState.AxisDrag.Z_NEG);
    }

    private static void drawGizmoArrow(VertexConsumer vc, PoseStack.Pose pose, double x, double y, double z,
                                        SplineEditorClientState.AxisDrag axis, int dx, int dy, int dz, int[] col, boolean highlight) {
        float bright = highlight ? 1.3f : 1.0f;
        int r = Math.min(255, (int)(col[0] * bright));
        int g = Math.min(255, (int)(col[1] * bright));
        int b = Math.min(255, (int)(col[2] * bright));
        int a = highlight ? 255 : col[3];

        // 1. Shaft
        AABB shaft = getGizmoAxisAABB(x, y, z, axis, SHAFT_LEN, SHAFT_THICKNESS);
        fillAABB(vc, pose, shaft, r, g, b, a);

        // 2. Head
        double headStart = SHAFT_LEN;
        double headX = x + dx * headStart;
        double headY = y + dy * headStart;
        double headZ = z + dz * headStart;
        AABB head = getGizmoAxisAABB(headX, headY, headZ, axis, HEAD_LEN, HEAD_THICKNESS);
        fillAABB(vc, pose, head, r, g, b, a);
    }

    private static AABB getGizmoAxisAABB(double x, double y, double z, SplineEditorClientState.AxisDrag axis, float length, float thickness) {
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

    // ── X-Ray custom shader definitions ──

    private static abstract class SplineRenderType extends RenderType {
        private SplineRenderType(String name, VertexFormat format, VertexFormat.Mode mode, int bufSize, boolean affectsCrumbling, boolean sortOnUpload, Runnable setupState, Runnable clearState) {
            super(name, format, mode, bufSize, affectsCrumbling, sortOnUpload, setupState, clearState);
        }

        public static final RenderType XRAY_QUADS = create(
                "spline_editor_quads",
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
                "spline_editor_lines",
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
