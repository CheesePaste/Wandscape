package com.wsteam.wandscape.building.editor;

import org.slf4j.Logger;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.Optional;

public final class BuildingEditorAxisRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    // 调整为实体方块的尺寸
    private static final float SHAFT_LEN = 3.5f;
    private static final float SHAFT_THICKNESS = 0.15f; // 杆的粗细
    private static final float HEAD_LEN = 0.6f;
    private static final float HEAD_THICKNESS = 0.35f;  // 箭头的粗细

    // 明亮的轴颜色
    private static final int[] COL_X = {255, 50, 50, 200};
    private static final int[] COL_Y = {50, 220, 50, 200};
    private static final int[] COL_Z = {50, 100, 255, 200};
    // 负方向暗色
    private static final int[] COL_XN = {150, 50, 50, 160};
    private static final int[] COL_YN = {50, 150, 50, 160};
    private static final int[] COL_ZN = {50, 70, 150, 160};

    private static boolean registered = false;

    private BuildingEditorAxisRenderer() {}

    public static void register() {
        if (registered) return;
        registered = true;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, BuildingEditorAxisRenderer::onRenderLevelStage);
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

        // 使用实心半透明材质渲染，而不是线条
        VertexConsumer vc = buf.getBuffer(HandleRenderType.XRAY_QUADS);
        PoseStack.Pose pose = poseStack.last();

        BlockPos corner = BuildingEditorClientState.getWorldMin();
        if (corner == null) corner = worldAnchor;

        BuildingEditorClientState.AxisDrag hovering = BuildingEditorClientState.getHoveredAxis();

        // 渲染正方向 (扩展)
        draw3DArrow(vc, pose, corner, 1, 0, 0, COL_X, hovering == BuildingEditorClientState.AxisDrag.X_POS);
        draw3DArrow(vc, pose, corner, 0, 1, 0, COL_Y, hovering == BuildingEditorClientState.AxisDrag.Y_POS);
        draw3DArrow(vc, pose, corner, 0, 0, 1, COL_Z, hovering == BuildingEditorClientState.AxisDrag.Z_POS);

        // 渲染负方向 (收缩)
        BlockPos maxCorner = BuildingEditorClientState.getWorldMax();
        if (maxCorner != null) {
            draw3DArrow(vc, pose, maxCorner, -1, 0, 0, COL_XN, hovering == BuildingEditorClientState.AxisDrag.X_NEG);
            draw3DArrow(vc, pose, maxCorner, 0, -1, 0, COL_YN, hovering == BuildingEditorClientState.AxisDrag.Y_NEG);
            draw3DArrow(vc, pose, maxCorner, 0, 0, -1, COL_ZN, hovering == BuildingEditorClientState.AxisDrag.Z_NEG);
        }

        buf.endBatch(HandleRenderType.XRAY_QUADS);
        poseStack.popPose();
    }

    /** 绘制实体的 3D 箭头 */
    private static void draw3DArrow(VertexConsumer vc, PoseStack.Pose pose, BlockPos base, int dx, int dy, int dz, int[] col, boolean isHovered) {
        float bx = base.getX() + 0.5f;
        float by = base.getY() + 0.5f;
        float bz = base.getZ() + 0.5f;

        float bright = isHovered ? 1.3f : 1.0f;
        int r = Math.min(255, (int)(col[0] * bright));
        int g = Math.min(255, (int)(col[1] * bright));
        int b = Math.min(255, (int)(col[2] * bright));
        int a = isHovered ? 255 : col[3];

        // 1. 绘制长条形杆子 (Shaft)
        AABB shaft = getAxisAABB(bx, by, bz, dx, dy, dz, SHAFT_LEN, SHAFT_THICKNESS);
        fillAABB(vc, pose, shaft, r, g, b, a);

        // 2. 绘制方块头部 (Head)
        float headStart = SHAFT_LEN;
        float headEnd = SHAFT_LEN + HEAD_LEN;
        AABB head = getAxisAABB(
                bx + dx * headStart, by + dy * headStart, bz + dz * headStart,
                dx, dy, dz, HEAD_LEN, HEAD_THICKNESS
        );
        fillAABB(vc, pose, head, r, g, b, a);
    }

    /** 生成轴的物理碰撞箱（用于渲染和精准鼠标检测） */
    private static AABB getAxisAABB(float x, float y, float z, int dx, int dy, int dz, float length, float thickness) {
        float minX = x - thickness, minY = y - thickness, minZ = z - thickness;
        float maxX = x + thickness, maxY = y + thickness, maxZ = z + thickness;

        if (dx > 0) maxX = x + length;
        if (dx < 0) minX = x - length;
        if (dy > 0) maxY = y + length;
        if (dy < 0) minY = y - length;
        if (dz > 0) maxZ = z + length;
        if (dz < 0) minZ = z - length;

        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * 极其精准的视线碰撞检测：直接检测射线与箭头 AABB 的交点。
     */
    public static BuildingEditorClientState.AxisDrag hitTestAxis(Vec3 rayOrigin, Vec3 rayDir) {
        BlockPos corner = BuildingEditorClientState.getWorldMin() != null ? BuildingEditorClientState.getWorldMin() : BuildingEditorClientState.getWorldAnchor();
        if (corner == null) return null;

        Vec3 rayEnd = rayOrigin.add(rayDir.scale(100.0)); // 射线投射 100 格
        BuildingEditorClientState.AxisDrag bestAxis = null;
        double bestDist = Double.MAX_VALUE;

        // 测试正方向 (包含 Shaft 和 Head 的联合碰撞箱)
        bestDist = checkHit(bestDist, rayOrigin, rayEnd, corner, 1, 0, 0, BuildingEditorClientState.AxisDrag.X_POS, bestAxis);
        if (bestDist < Double.MAX_VALUE) bestAxis = BuildingEditorClientState.AxisDrag.X_POS;
        bestDist = checkHit(bestDist, rayOrigin, rayEnd, corner, 0, 1, 0, BuildingEditorClientState.AxisDrag.Y_POS, bestAxis);
        if (bestDist < Double.MAX_VALUE && bestAxis != BuildingEditorClientState.AxisDrag.X_POS) bestAxis = BuildingEditorClientState.AxisDrag.Y_POS;
        bestDist = checkHit(bestDist, rayOrigin, rayEnd, corner, 0, 0, 1, BuildingEditorClientState.AxisDrag.Z_POS, bestAxis);
        if (bestDist < Double.MAX_VALUE && bestAxis != BuildingEditorClientState.AxisDrag.Y_POS && bestAxis != BuildingEditorClientState.AxisDrag.X_POS) bestAxis = BuildingEditorClientState.AxisDrag.Z_POS;

        // 重新进行一次干净的遍历测试，寻找最近的 Hit
        bestAxis = null;
        bestDist = Double.MAX_VALUE;

        // X_POS
        double dist = getHitDist(rayOrigin, rayEnd, corner, 1, 0, 0);
        if (dist < bestDist) { bestDist = dist; bestAxis = BuildingEditorClientState.AxisDrag.X_POS; }
        // Y_POS
        dist = getHitDist(rayOrigin, rayEnd, corner, 0, 1, 0);
        if (dist < bestDist) { bestDist = dist; bestAxis = BuildingEditorClientState.AxisDrag.Y_POS; }
        // Z_POS
        dist = getHitDist(rayOrigin, rayEnd, corner, 0, 0, 1);
        if (dist < bestDist) { bestDist = dist; bestAxis = BuildingEditorClientState.AxisDrag.Z_POS; }

        // 测试负方向
        BlockPos maxCorner = BuildingEditorClientState.getWorldMax();
        if (maxCorner != null) {
            dist = getHitDist(rayOrigin, rayEnd, maxCorner, -1, 0, 0);
            if (dist < bestDist) { bestDist = dist; bestAxis = BuildingEditorClientState.AxisDrag.X_NEG; }
            dist = getHitDist(rayOrigin, rayEnd, maxCorner, 0, -1, 0);
            if (dist < bestDist) { bestDist = dist; bestAxis = BuildingEditorClientState.AxisDrag.Y_NEG; }
            dist = getHitDist(rayOrigin, rayEnd, maxCorner, 0, 0, -1);
            if (dist < bestDist) { bestDist = dist; bestAxis = BuildingEditorClientState.AxisDrag.Z_NEG; }
        }

        return bestAxis;
    }

    private static double checkHit(double currentBest, Vec3 start, Vec3 end, BlockPos base, int dx, int dy, int dz, BuildingEditorClientState.AxisDrag axis, BuildingEditorClientState.AxisDrag bestAxisOut) {
        return getHitDist(start, end, base, dx, dy, dz);
    }

    private static double getHitDist(Vec3 rayOrigin, Vec3 rayEnd, BlockPos base, int dx, int dy, int dz) {
        float bx = base.getX() + 0.5f; float by = base.getY() + 0.5f; float bz = base.getZ() + 0.5f;
        // 把杆子和箭头的判定框合并（加粗一点方便选中）
        AABB hitBox = getAxisAABB(bx, by, bz, dx, dy, dz, SHAFT_LEN + HEAD_LEN, HEAD_THICKNESS + 0.1f);
        Optional<Vec3> hit = hitBox.clip(rayOrigin, rayEnd);
        return hit.map(vec3 -> vec3.distanceToSqr(rayOrigin)).orElse(Double.MAX_VALUE);
    }

    // --- 绘制 3D 长方体的底层工具 ---
    private static void fillAABB(VertexConsumer vc, PoseStack.Pose pose, AABB box, int r, int g, int b, int a) {
        float x0 = (float)box.minX, y0 = (float)box.minY, z0 = (float)box.minZ;
        float x1 = (float)box.maxX, y1 = (float)box.maxY, z1 = (float)box.maxZ;
        quad(vc, pose, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a); // bottom
        quad(vc, pose, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b, a); // top
        quad(vc, pose, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, a); // back
        quad(vc, pose, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b, a); // right
        quad(vc, pose, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, a); // front
        quad(vc, pose, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, a); // left
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose pose, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float x4, float y4, float z4, int r, int g, int b, int a) {
        vc.addVertex(pose, x1,y1,z1).setColor(r,g,b,a);
        vc.addVertex(pose, x2,y2,z2).setColor(r,g,b,a);
        vc.addVertex(pose, x3,y3,z3).setColor(r,g,b,a);
        vc.addVertex(pose, x4,y4,z4).setColor(r,g,b,a);
    }
    // ═══════════════════════════════════════════════════════════════
    // ── X-Ray 穿透渲染类型 ──
    // ═══════════════════════════════════════════════════════════════

    private static abstract class HandleRenderType extends RenderType {
        private HandleRenderType(String name, com.mojang.blaze3d.vertex.VertexFormat format, com.mojang.blaze3d.vertex.VertexFormat.Mode mode, int bufSize, boolean affectsCrumbling, boolean sortOnUpload, Runnable setupState, Runnable clearState) {
            super(name, format, mode, bufSize, affectsCrumbling, sortOnUpload, setupState, clearState);
        }

        // 创建一个无视深度的自定义渲染材质
        public static final RenderType XRAY_QUADS = create(
                "build_editor_handle",
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR,
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                256,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(POSITION_COLOR_SHADER)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY) // 允许半透明
                        .setDepthTestState(NO_DEPTH_TEST)               // <--- 核心魔法：关闭深度测试，无视遮挡！
                        .setCullState(NO_CULL)                          // 禁用背面剔除，无论什么角度都能看到
                        .createCompositeState(false)
        );
    }
}
