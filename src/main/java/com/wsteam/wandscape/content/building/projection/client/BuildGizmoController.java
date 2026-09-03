package com.wsteam.wandscape.content.building.projection.client;
import com.wsteam.wandscape.content.task.component.Position;

import com.wsteam.wandscape.content.road.client.RoadPlacementController;
import com.wsteam.wandscape.content.building.network.BuildingAreaSyncPacket;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * Controller for mouse interaction, hover detection, and 3D Axis Gizmo dragging
 * for building ghost position fine-tuning in Build Projection mode.
 * <p>Gizmo is active ONLY when the ghost position is PINNED (locked).</p>
 */
public final class BuildGizmoController {

    public enum AxisDrag {
        NONE, X_POS, X_NEG, Y_POS, Y_NEG, Z_POS, Z_NEG
    }

    private static final String TAG = "BuildGizmoController";
    private static final double REACH = 512.0;
    private static AxisDrag hoveredAxis = AxisDrag.NONE;
    private static AxisDrag draggingAxis = AxisDrag.NONE;

    private static BlockPos dragStartGhostPos = null;
    private static Vec3 dragStartAxisOrigin = null;
    private static double dragStartAxisValue = 0.0;

    private static boolean wasLeftDown = false;
    private static boolean registered = false;

    private BuildGizmoController() {}

    public static void register() {
        if (registered) return;
        registered = true;
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(ClientTickEvent.Post.class, BuildGizmoController::onClientTickPost);
        bus.addListener(InputEvent.MouseButton.Pre.class, BuildGizmoController::onMouseButtonPre);
    }

    public static boolean isActive() {
        return BuildPopPanelOverlay.isActive() && ProjectionClientState.isPinned();
    }

    public static AxisDrag getHoveredAxis() { return hoveredAxis; }
    public static AxisDrag getDraggingAxis() { return draggingAxis; }

    /** Calculate distance-compensated scale factor for screen-constant visual size. */
    public static float getDistanceScale(Vec3 camPos, Vec3 ghostCenter) {
        double dist = camPos.distanceTo(ghostCenter);
        return (float) Math.max(1.0, dist * 0.12);
    }

    static void onClientTickPost(ClientTickEvent.Post event) {
        if (!isActive()) {
            hoveredAxis = AxisDrag.NONE;
            if (draggingAxis != AxisDrag.NONE) {
                finishGizmoDrag();
            }
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        long window = mc.getWindow().getWindow();
        if (window == 0L) return;

        boolean leftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean leftClicked = leftDown && !wasLeftDown;
        boolean leftReleased = !leftDown && wasLeftDown;
        wasLeftDown = leftDown;

        BlockPos ghostPos = ProjectionClientState.getGhostPos();
        if (ghostPos == null) {
            hoveredAxis = AxisDrag.NONE;
            return;
        }

        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 rayOrigin = cam.getPosition();
        Vec3 rayDir = RoadPlacementController.getMouseWorldRay(mc);

        // Update hover axis when not dragging
        if (draggingAxis == AxisDrag.NONE) {
            updateGizmoHover(rayOrigin, rayDir, ghostPos);
        }

        if (leftClicked && hoveredAxis != AxisDrag.NONE) {
            startGizmoDrag(rayOrigin, rayDir, ghostPos);
        } else if (leftDown && draggingAxis != AxisDrag.NONE) {
            continueGizmoDrag(rayOrigin, rayDir);
        } else if (leftReleased && draggingAxis != AxisDrag.NONE) {
            finishGizmoDrag();
        }
    }

    static void onMouseButtonPre(InputEvent.MouseButton.Pre event) {
        if (!isActive()) return;
        // 光标落在右侧建造面板（含微调按钮/锁定/提交）上时交给面板处理，
        // 避免同一记左键既点按钮又在 3D 轴上起拖拽，导致虚影双重位移。
        Minecraft mc = Minecraft.getInstance();
        double guiScale = mc.getWindow().getGuiScale();
        double mx = mc.mouseHandler.xpos() / guiScale;
        double my = mc.mouseHandler.ypos() / guiScale;
        int screenW = mc.getWindow().getGuiScaledWidth();
        if (BuildPopPanelOverlay.isOverPanel(mx, my, screenW)) return;
        if (hoveredAxis != AxisDrag.NONE || draggingAxis != AxisDrag.NONE) {
            if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                event.setCanceled(true);
            }
        }
    }

    private static void updateGizmoHover(Vec3 rayOrigin, Vec3 rayDir, BlockPos ghostPos) {
        Vec3 origin = new Vec3(ghostPos.getX() + 0.5, ghostPos.getY() + 0.5, ghostPos.getZ() + 0.5);
        float scale = getDistanceScale(rayOrigin, origin);
        hoveredAxis = hitTestGizmo(rayOrigin, rayDir, origin, scale);
    }

    private static AxisDrag hitTestGizmo(Vec3 rayOrigin, Vec3 rayDir, Vec3 origin, float scale) {
        AxisDrag bestAxis = AxisDrag.NONE;
        double minDistance = Double.MAX_VALUE;

        float sMin = 0.15f * scale;
        float sMax = 1.9f * scale;
        double hitRadius = 0.35 * scale;

        for (AxisDrag axis : AxisDrag.values()) {
            if (axis == AxisDrag.NONE) continue;

            Vec3 u = getGizmoWorldDir(axis);
            Vec3 w0 = rayOrigin.subtract(origin);

            double a = u.dot(u); // 1.0
            double b = u.dot(rayDir);
            double c = rayDir.dot(rayDir); // 1.0
            double d = u.dot(w0);
            double e = rayDir.dot(w0);

            double denom = a * c - b * b;
            double s;
            if (Math.abs(denom) < 1e-6) {
                s = (sMin + sMax) * 0.5;
            } else {
                s = (c * d - b * e) / denom;
            }
            s = Math.max(sMin, Math.min(sMax, s));

            Vec3 pSeg = origin.add(u.scale(s));
            double t = Math.max(0.0, pSeg.subtract(rayOrigin).dot(rayDir));
            if (t > REACH) continue;

            Vec3 pRay = rayOrigin.add(rayDir.scale(t));
            double dist = pSeg.distanceTo(pRay);

            if (dist <= hitRadius && dist < minDistance) {
                minDistance = dist;
                bestAxis = axis;
            }
        }

        return bestAxis;
    }

    public static AABB getGizmoAxisAABB(double x, double y, double z, AxisDrag axis, float length, float thickness) {
        double minX = x - thickness, minY = y - thickness, minZ = z - thickness;
        double maxX = x + thickness, maxY = y + thickness, maxZ = z + thickness;

        switch (axis) {
            case X_POS -> maxX = x + length;
            case X_NEG -> minX = x - length;
            case Y_POS -> maxY = y + length;
            case Y_NEG -> minY = y - length;
            case Z_POS -> maxZ = z + length;
            case Z_NEG -> minZ = z - length;
            default -> {}
        }

        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static void startGizmoDrag(Vec3 rayOrigin, Vec3 rayDir, BlockPos ghostPos) {
        draggingAxis = hoveredAxis;
        dragStartGhostPos = ghostPos;
        dragStartAxisOrigin = new Vec3(ghostPos.getX() + 0.5, ghostPos.getY() + 0.5, ghostPos.getZ() + 0.5);
        Vec3 axisDir = getGizmoWorldDir(draggingAxis);
        dragStartAxisValue = getClosestPointOnAxis(rayOrigin, rayDir, dragStartAxisOrigin, axisDir);
    }

    private static void continueGizmoDrag(Vec3 rayOrigin, Vec3 rayDir) {
        if (draggingAxis == AxisDrag.NONE || dragStartGhostPos == null || dragStartAxisOrigin == null) return;

        Vec3 axisDir = getGizmoWorldDir(draggingAxis);
        double currentAxisValue = getClosestPointOnAxis(rayOrigin, rayDir, dragStartAxisOrigin, axisDir);
        double delta = currentAxisValue - dragStartAxisValue;

        int shiftX = (int) Math.round(axisDir.x * delta);
        int shiftY = (int) Math.round(axisDir.y * delta);
        int shiftZ = (int) Math.round(axisDir.z * delta);

        BlockPos newPos = dragStartGhostPos.offset(shiftX, shiftY, shiftZ);
        ProjectionClientState.setGhostPos(newPos);

        ProjectionClientState.setOverlapDetected(ProjectionClientState.currentSelectionConflicts(newPos));
    }

    private static void finishGizmoDrag() {
        draggingAxis = AxisDrag.NONE;
        dragStartGhostPos = null;
        dragStartAxisOrigin = null;
    }

    private static Vec3 getGizmoWorldDir(AxisDrag axis) {
        return switch (axis) {
            case X_POS -> new Vec3( 1, 0, 0);
            case X_NEG -> new Vec3(-1, 0, 0);
            case Y_POS -> new Vec3( 0, 1, 0);
            case Y_NEG -> new Vec3( 0,-1, 0);
            case Z_POS -> new Vec3( 0, 0, 1);
            case Z_NEG -> new Vec3( 0, 0,-1);
            default -> Vec3.ZERO;
        };
    }

    private static double getClosestPointOnAxis(Vec3 rayOrigin, Vec3 rayDir, Vec3 axisOrigin, Vec3 axisDir) {
        Vec3 w0 = rayOrigin.subtract(axisOrigin);
        double a = axisDir.dot(axisDir);
        double b = axisDir.dot(rayDir);
        double c = rayDir.dot(rayDir);
        double d = axisDir.dot(w0);
        double e = rayDir.dot(w0);

        double denom = a * c - b * b;
        if (Math.abs(denom) < 1e-6) return 0;
        return (c * d - b * e) / denom;
    }
}
