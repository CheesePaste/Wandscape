package com.wsteam.wandscape.content.building.projection.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.wsteam.wandscape.content.building.projection.client.BuildGizmoController.AxisDrag;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Renders the 3D Axis Gizmo (X, Y, Z arrows) at the building ghost position in Build mode
 * when the position is PINNED (locked) and free mouse cursor is active.
 * Uses dynamic camera distance scaling for constant screen size (compensating perspective).
 */
public final class BuildGizmoRenderer {

    private static final String TAG = "BuildGizmoRenderer";

    private static final float SHAFT_LEN = 1.6f;
    private static final float SHAFT_THICKNESS = 0.08f;
    private static final float HEAD_LEN = 0.40f;
    private static final float HEAD_THICKNESS = 0.16f;

    private static final int[] COL_X  = {255,  60,  60, 230};
    private static final int[] COL_Y  = { 60, 255,  60, 230};
    private static final int[] COL_Z  = { 60, 100, 255, 230};
    private static final int[] COL_XN = {180,  40,  40, 180};
    private static final int[] COL_YN = { 40, 180,  40, 180};
    private static final int[] COL_ZN = { 40,  60, 180, 180};

    private static boolean registered = false;

    private BuildGizmoRenderer() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, BuildGizmoRenderer::onRenderLevelStage);
        Log.info(TAG, "[BuildGizmo] Renderer registered");
    }

    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!BuildGizmoController.isActive()) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) return;

        BlockPos ghostPos = ProjectionClientState.getGhostPos();
        if (ghostPos == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Vec3 camPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        PoseStack.Pose pose = poseStack.last();
        VertexConsumer vcQuads = buf.getBuffer(BuildGizmoRenderType.GIZMO_QUADS);

        drawAxisGizmo(vcQuads, pose, ghostPos, camPos);

        buf.endBatch(BuildGizmoRenderType.GIZMO_QUADS);
        poseStack.popPose();
    }

    private static void drawAxisGizmo(VertexConsumer vc, PoseStack.Pose pose, BlockPos pos, Vec3 camPos) {
        AxisDrag hovering = BuildGizmoController.getHoveredAxis();
        AxisDrag dragging = BuildGizmoController.getDraggingAxis();
        AxisDrag activeAxis = (dragging != AxisDrag.NONE) ? dragging : hovering;

        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;

        Vec3 origin = new Vec3(x, y, z);
        float scale = BuildGizmoController.getDistanceScale(camPos, origin);

        // X Axis (+ / -)
        drawGizmoArrow(vc, pose, x, y, z, AxisDrag.X_POS, 1, 0, 0, COL_X, activeAxis == AxisDrag.X_POS, scale);
        drawGizmoArrow(vc, pose, x, y, z, AxisDrag.X_NEG, -1, 0, 0, COL_XN, activeAxis == AxisDrag.X_NEG, scale);

        // Y Axis (+ / -)
        drawGizmoArrow(vc, pose, x, y, z, AxisDrag.Y_POS, 0, 1, 0, COL_Y, activeAxis == AxisDrag.Y_POS, scale);
        drawGizmoArrow(vc, pose, x, y, z, AxisDrag.Y_NEG, 0, -1, 0, COL_YN, activeAxis == AxisDrag.Y_NEG, scale);

        // Z Axis (+ / -)
        drawGizmoArrow(vc, pose, x, y, z, AxisDrag.Z_POS, 0, 0, 1, COL_Z, activeAxis == AxisDrag.Z_POS, scale);
        drawGizmoArrow(vc, pose, x, y, z, AxisDrag.Z_NEG, 0, 0, -1, COL_ZN, activeAxis == AxisDrag.Z_NEG, scale);
    }

    private static void drawGizmoArrow(VertexConsumer vc, PoseStack.Pose pose, double x, double y, double z,
                                        AxisDrag axis, int dx, int dy, int dz, int[] col, boolean highlight, float scale) {
        float bright = highlight ? 1.4f : 1.0f;
        int r = Math.min(255, (int)(col[0] * bright));
        int g = Math.min(255, (int)(col[1] * bright));
        int b = Math.min(255, (int)(col[2] * bright));
        int a = highlight ? 255 : col[3];

        float shaftLen = SHAFT_LEN * scale;
        float shaftThickness = SHAFT_THICKNESS * scale;
        float headLen = HEAD_LEN * scale;
        float headThickness = HEAD_THICKNESS * scale;

        AABB shaft = BuildGizmoController.getGizmoAxisAABB(x, y, z, axis, shaftLen, shaftThickness);
        fillAABB(vc, pose, shaft, r, g, b, a);

        double headStart = shaftLen;
        double headX = x + dx * headStart;
        double headY = y + dy * headStart;
        double headZ = z + dz * headStart;
        AABB head = BuildGizmoController.getGizmoAxisAABB(headX, headY, headZ, axis, headLen, headThickness);
        fillAABB(vc, pose, head, r, g, b, a);
    }

    private static void fillAABB(VertexConsumer vc, PoseStack.Pose pose, AABB b, int r, int g, int bCol, int a) {
        float x1 = (float) b.minX, y1 = (float) b.minY, z1 = (float) b.minZ;
        float x2 = (float) b.maxX, y2 = (float) b.maxY, z2 = (float) b.maxZ;

        // Down face (Y-)
        quad(vc, pose, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2, r, g, bCol, a);
        // Up face (Y+)
        quad(vc, pose, x1, y2, z2, x2, y2, z2, x2, y2, z1, x1, y2, z1, r, g, bCol, a);
        // North face (Z-)
        quad(vc, pose, x2, y2, z1, x2, y1, z1, x1, y1, z1, x1, y2, z1, r, g, bCol, a);
        // South face (Z+)
        quad(vc, pose, x1, y2, z2, x1, y1, z2, x2, y1, z2, x2, y2, z2, r, g, bCol, a);
        // West face (X-)
        quad(vc, pose, x1, y2, z1, x1, y1, z1, x1, y1, z2, x1, y2, z2, r, g, bCol, a);
        // East face (X+)
        quad(vc, pose, x2, y2, z2, x2, y1, z2, x2, y1, z1, x2, y2, z1, r, g, bCol, a);
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float x4, float y4, float z4,
                             int r, int g, int b, int a) {
        vc.addVertex(pose.pose(), x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(pose.pose(), x2, y2, z2).setColor(r, g, b, a);
        vc.addVertex(pose.pose(), x3, y3, z3).setColor(r, g, b, a);
        vc.addVertex(pose.pose(), x4, y4, z4).setColor(r, g, b, a);
    }

    private static abstract class BuildGizmoRenderType extends RenderType {
        private BuildGizmoRenderType(String name, VertexFormat format, VertexFormat.Mode mode, int bufSize, boolean affectsCrumbling, boolean sortOnUpload, Runnable setupState, Runnable clearState) {
            super(name, format, mode, bufSize, affectsCrumbling, sortOnUpload, setupState, clearState);
        }

        public static final RenderType GIZMO_QUADS = create(
                "build_gizmo_quads",
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
