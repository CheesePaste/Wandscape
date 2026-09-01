package com.wsteam.wandscape.content.building.scanner.client.gizmo;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.content.building.data.BlockOffset;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

/**
 * Handles mouse events, Unity-style right-click camera rotation, raycasting,
 * and 3D axis Gizmo dragging for the Building Scanner Visual Adjuster.
 */
public final class ScannerGizmoController {
    private static final String TAG = "ScannerGizmoController";
    private static final double REACH = 512.0;

    private static boolean registered = false;
    private static boolean cameraActive = false;

    private static double savedCursorX = 0, savedCursorY = 0;
    private static boolean hasSavedCursor = false;

    // Key debounce
    private static boolean wasTabDown = false;
    private static boolean wasEnterDown = false;
    private static boolean wasEscapeDown = false;

    private ScannerGizmoController() {}

    public static void register() {
        if (registered) return;
        registered = true;
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(InputEvent.MouseButton.Pre.class, ScannerGizmoController::onMouseButtonPre);
        bus.addListener(ClientTickEvent.Post.class, ScannerGizmoController::onClientTickPost);
        bus.addListener(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock.class, event -> {
            if (ScannerGizmoState.isActive()) {
                event.setCanceled(true);
            }
        });
        bus.addListener(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem.class, event -> {
            if (ScannerGizmoState.isActive()) {
                event.setCanceled(true);
            }
        });
        bus.addListener(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.class, event -> {
            if (ScannerGizmoState.isActive()) {
                event.setCanceled(true);
            }
        });
        Log.info(TAG, "ScannerGizmoController registered");
    }

    public static boolean isCameraActive() {
        return cameraActive;
    }

    private static void onMouseButtonPre(InputEvent.MouseButton.Pre event) {
        if (!ScannerGizmoState.isActive()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.mouseHandler == null || mc.getWindow() == null) return;

        long window = mc.getWindow().getWindow();
        int button = event.getButton();
        int action = event.getAction();

        double[] mxArr = new double[1], myArr = new double[1];
        GLFW.glfwGetCursorPos(window, mxArr, myArr);
        double scale = mc.getWindow().getGuiScale();
        double mx = mxArr[0] / scale;
        double my = myArr[0] / scale;

        boolean overPanel = ScannerGizmoOverlay.isMouseOverPanel(mx, my);

        // ── 1. Right Mouse Button: Unity-style camera rotation ──
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (action == GLFW.GLFW_PRESS) {
                if (!cameraActive && !overPanel) {
                    savedCursorX = mxArr[0];
                    savedCursorY = myArr[0];
                    hasSavedCursor = true;
                    cameraActive = true;
                    mc.mouseHandler.grabMouse();
                }
            } else if (cameraActive && action == GLFW.GLFW_RELEASE) {
                cameraActive = false;
                mc.mouseHandler.releaseMouse();
                if (hasSavedCursor) {
                    GLFW.glfwSetCursorPos(window, savedCursorX, savedCursorY);
                    hasSavedCursor = false;
                }
            }
            event.setCanceled(true);
            return;
        }

        // ── 2. Left Mouse Button: Panel Interaction & 3D Gizmo Dragging ──
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (action == GLFW.GLFW_PRESS) {
                if (overPanel) {
                    ScannerGizmoOverlay.handleMouseClick(mx, my, button);
                    event.setCanceled(true);
                    return;
                }

                if (mc.gameRenderer == null || mc.gameRenderer.getMainCamera() == null) {
                    event.setCanceled(true);
                    return;
                }
                Vec3 rayOrigin = mc.gameRenderer.getMainCamera().getPosition();
                Vec3 rayDir = getMouseWorldRay(mc);

                // A. Check Gizmo axis hit test on active anchor
                ScannerGizmoState.AxisDrag hitAxis = hitTestGizmo(mc, rayOrigin, rayDir, ScannerGizmoState.getSelectedAnchor());
                if (hitAxis != ScannerGizmoState.AxisDrag.NONE) {
                    startGizmoDrag(mc, rayOrigin, rayDir, hitAxis);
                    event.setCanceled(true);
                    return;
                }

                // B. Check if user clicked on the opposite anchor cube to switch anchors
                ScannerGizmoState.Anchor other = (ScannerGizmoState.getSelectedAnchor() == ScannerGizmoState.Anchor.MIN)
                        ? ScannerGizmoState.Anchor.MAX : ScannerGizmoState.Anchor.MIN;
                Vec3 otherPos = ScannerGizmoState.getWorldAnchorPos(other);
                float otherScale = ScannerGizmoRenderer.getDistanceScale(rayOrigin, otherPos);
                double cubeRadius = 0.30 * otherScale;
                AABB otherCube = new AABB(otherPos.x - cubeRadius, otherPos.y - cubeRadius, otherPos.z - cubeRadius,
                        otherPos.x + cubeRadius, otherPos.y + cubeRadius, otherPos.z + cubeRadius);
                Optional<Vec3> hitOther = otherCube.clip(rayOrigin, rayOrigin.add(rayDir.scale(REACH)));
                if (hitOther.isPresent()) {
                    ScannerGizmoState.setSelectedAnchor(other);
                }
            } else if (action == GLFW.GLFW_RELEASE) {
                if (ScannerGizmoState.isDragging()) {
                    finishGizmoDrag();
                }
            }
            event.setCanceled(true);
            return;
        }

        // Always consume other mouse buttons in visual adjust mode
        event.setCanceled(true);
    }

    private static void onClientTickPost(ClientTickEvent.Post event) {
        if (!ScannerGizmoState.isActive()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.getWindow() == null) return;

        long window = mc.getWindow().getWindow();

        // ── Hotkeys ──
        boolean isTabDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_TAB) == GLFW.GLFW_PRESS;
        if (isTabDown && !wasTabDown) {
            ScannerGizmoState.toggleAnchor();
        }
        wasTabDown = isTabDown;

        boolean isEnterDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ENTER) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_KP_ENTER) == GLFW.GLFW_PRESS;
        if (isEnterDown && !wasEnterDown) {
            ScannerGizmoState.confirm();
            return;
        }
        wasEnterDown = isEnterDown;

        boolean isEscapeDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        if (isEscapeDown && !wasEscapeDown) {
            ScannerGizmoState.cancel();
            return;
        }
        wasEscapeDown = isEscapeDown;

        // ── Drag & Hover Updates ──
        if (mc.gameRenderer == null || mc.gameRenderer.getMainCamera() == null) return;
        Vec3 rayOrigin = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 rayDir = getMouseWorldRay(mc);

        if (ScannerGizmoState.isDragging()) {
            continueGizmoDrag(rayOrigin, rayDir);
        } else if (!cameraActive) {
            ScannerGizmoState.AxisDrag hitAxis = hitTestGizmo(mc, rayOrigin, rayDir, ScannerGizmoState.getSelectedAnchor());
            ScannerGizmoState.setHoveredAxis(hitAxis);
        }
    }

    private static void startGizmoDrag(Minecraft mc, Vec3 rayOrigin, Vec3 rayDir, ScannerGizmoState.AxisDrag axis) {
        ScannerGizmoState.setDraggingAxis(axis);

        ScannerGizmoState.Anchor anchor = ScannerGizmoState.getSelectedAnchor();
        BlockOffset curOff = (anchor == ScannerGizmoState.Anchor.MIN) ? ScannerGizmoState.getCurrentMin() : ScannerGizmoState.getCurrentMax();
        Vec3 origin = ScannerGizmoState.getWorldAnchorPos(anchor);
        Vec3 axisDir = getGizmoWorldDir(axis);

        double startVal = getClosestPointOnAxis(rayOrigin, rayDir, origin, axisDir);
        ScannerGizmoState.setDragStartState(curOff, origin, startVal);
        Log.info(TAG, "Started Gizmo drag: axis={}, startVal={}", axis, startVal);
    }

    private static void continueGizmoDrag(Vec3 rayOrigin, Vec3 rayDir) {
        ScannerGizmoState.AxisDrag axis = ScannerGizmoState.getDraggingAxis();
        if (axis == ScannerGizmoState.AxisDrag.NONE) return;

        Vec3 origin = ScannerGizmoState.getDragStartAxisOrigin();
        if (origin == null) return;

        Vec3 axisDir = getGizmoWorldDir(axis);
        double curVal = getClosestPointOnAxis(rayOrigin, rayDir, origin, axisDir);
        double delta = curVal - ScannerGizmoState.getDragStartAxisValue();

        int intDelta = (int) Math.round(delta);
        BlockOffset startOff = ScannerGizmoState.getDragStartOffset();

        int newX = startOff.x() + (int) (axisDir.x * intDelta);
        int newY = startOff.y() + (int) (axisDir.y * intDelta);
        int newZ = startOff.z() + (int) (axisDir.z * intDelta);

        if (ScannerGizmoState.getSelectedAnchor() == ScannerGizmoState.Anchor.MIN) {
            ScannerGizmoState.setMin(newX, newY, newZ);
        } else {
            ScannerGizmoState.setMax(newX, newY, newZ);
        }
    }

    private static void finishGizmoDrag() {
        Log.info(TAG, "Finished Gizmo drag");
        ScannerGizmoState.setDraggingAxis(ScannerGizmoState.AxisDrag.NONE);
    }

    public static ScannerGizmoState.AxisDrag hitTestGizmo(Minecraft mc, Vec3 rayOrigin, Vec3 rayDir, ScannerGizmoState.Anchor anchor) {
        Vec3 pos = ScannerGizmoState.getWorldAnchorPos(anchor);
        Vec3 rayEnd = rayOrigin.add(rayDir.scale(REACH));

        ScannerGizmoState.AxisDrag bestAxis = ScannerGizmoState.AxisDrag.NONE;
        double minDistance = Double.MAX_VALUE;

        float scale = ScannerGizmoRenderer.getDistanceScale(rayOrigin, pos);
        float sMin = 0.15f * scale;
        float sMax = (ScannerGizmoRenderer.BASE_SHAFT_LEN + ScannerGizmoRenderer.BASE_HEAD_LEN + 0.1f) * scale;
        double hitRadius = 0.35 * scale; // Responsive, generous hit capsule

        for (ScannerGizmoState.AxisDrag axis : ScannerGizmoState.AxisDrag.values()) {
            if (axis == ScannerGizmoState.AxisDrag.NONE) continue;

            Vec3 u = getGizmoWorldDir(axis);
            Vec3 w0 = rayOrigin.subtract(pos);

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

            Vec3 pSeg = pos.add(u.scale(s));
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

    public static Vec3 getMouseWorldRay(Minecraft mc) {
        long window = mc.getWindow().getWindow();
        double[] mx = new double[1], my = new double[1];
        GLFW.glfwGetCursorPos(window, mx, my);
        int screenW = mc.getWindow().getScreenWidth();
        int screenH = mc.getWindow().getScreenHeight();
        if (screenW <= 0) screenW = 1;
        if (screenH <= 0) screenH = 1;

        float ndcX = (float) (2.0 * mx[0] / screenW - 1.0);
        float ndcY = (float) (1.0 - 2.0 * my[0] / screenH);

        Camera cam = mc.gameRenderer.getMainCamera();
        float baseFov = (float) mc.options.fov().get();
        float fovModifier = (mc.player != null) ? mc.player.getFieldOfViewModifier() : 1.0f;
        float fov = baseFov * fovModifier;
        float fovRad = (float) Math.toRadians(fov);
        float aspect = (float) screenW / (float) screenH;
        float tanHalfFov = (float) Math.tan(fovRad * 0.5f);

        org.joml.Vector3f jLook = cam.getLookVector();
        org.joml.Vector3f jUp   = cam.getUpVector();
        org.joml.Vector3f jLeft = cam.getLeftVector();

        Vec3 forward = new Vec3(jLook.x, jLook.y, jLook.z);
        Vec3 up      = new Vec3(jUp.x,   jUp.y,   jUp.z);
        Vec3 right   = new Vec3(jLeft.x, jLeft.y, jLeft.z).scale(-1.0);

        return forward
                .add(right.scale(ndcX * tanHalfFov * aspect))
                .add(up.scale(ndcY * tanHalfFov))
                .normalize();
    }

    private static Vec3 getGizmoWorldDir(ScannerGizmoState.AxisDrag axis) {
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
        Vec3 p1 = rayOrigin;
        Vec3 d1 = rayDir;
        Vec3 p2 = axisOrigin;
        Vec3 d2 = axisDir;

        Vec3 r = p1.subtract(p2);
        double a = d1.dot(d1);
        double b = d1.dot(d2);
        double c = d2.dot(d2);
        double d = d1.dot(r);
        double e = d2.dot(r);

        double denom = a * c - b * b;
        if (Math.abs(denom) < 1e-6) {
            return 0.0;
        }

        return (a * e - b * d) / denom;
    }
}