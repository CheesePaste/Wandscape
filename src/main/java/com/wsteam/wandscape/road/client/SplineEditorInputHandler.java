package com.wsteam.wandscape.road.client;

import java.util.Optional;

import org.lwjgl.glfw.GLFW;

import com.wsteam.wandscape.road.core.SplineModel;
import com.wsteam.wandscape.road.core.SplinePoint;
import com.wsteam.wandscape.road.core.SplineVec3;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Handles mouse clicks, raycasting, point selection, and 3D axis Gizmo dragging for the spline editor.
 */
public final class SplineEditorInputHandler {
    private static final String TAG = "SplineEditorInputHandler";
    private static final double REACH = 128.0;

    private static boolean wasLeftDown = false;
    private static int tickCounter = 0;

    // Drag state
    private static SplineVec3 dragStartPointPos = null;
    private static Vec3 dragStartAxisOrigin = null;
    private static double dragStartAxisValue = 0.0;

    private SplineEditorInputHandler() {}

    public static void handleClicks(Minecraft mc, long window) {
        if (!SplineEditorClientState.isEditing()) return;

        tickCounter++;
        boolean leftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean leftClicked = leftDown && !wasLeftDown;
        boolean leftReleased = !leftDown && wasLeftDown;

        Vec3 rayOrigin = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 rayDir = getMouseWorldRay(mc);

        // 1. Hover check for Axis Gizmo (only if we have a selected point)
        updateGizmoHover(rayOrigin, rayDir);

        if (leftClicked) {
            // Check if we clicked on the Gizmo axis first
            if (SplineEditorClientState.getHoveredAxis() != SplineEditorClientState.AxisDrag.NONE) {
                startGizmoDrag(mc, rayOrigin, rayDir);
            } else {
                // Otherwise, try selecting an existing point in the world
                boolean selected = trySelectPoint(rayOrigin, rayDir);
                
                // If we didn't click any point and we are in ADD mode, add a new point on the block surface
                if (!selected && SplineEditorClientState.getEditMode() == SplineEditorClientState.EditMode.ADD) {
                    addNewSplinePoint(mc, rayDir);
                }
            }
        }

        // 2. Perform active drag
        if (leftDown && SplineEditorClientState.isDragging()) {
            continueGizmoDrag(mc, rayOrigin, rayDir);
        }

        if (leftReleased && SplineEditorClientState.isDragging()) {
            finishGizmoDrag();
        }

        wasLeftDown = leftDown;
    }

    private static Vec3 getMouseWorldRay(Minecraft mc) {
        long window = mc.getWindow().getWindow();
        double[] mx = new double[1], my = new double[1];
        GLFW.glfwGetCursorPos(window, mx, my);
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();

        float ndcX = (float) (2.0 * mx[0] / w - 1.0);
        float ndcY = (float) (1.0 - 2.0 * my[0] / h);

        Camera cam = mc.gameRenderer.getMainCamera();
        float fov = (float) mc.options.fov().get();
        float fovRad = (float) Math.toRadians(fov);
        float aspect = (float) w / Math.max(h, 1);
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

    private static void updateGizmoHover(Vec3 rayOrigin, Vec3 rayDir) {
        int selIdx = SplineEditorClientState.getSelectedPointIndex();
        SplineEditorClientState.SelectionType selType = SplineEditorClientState.getSelectedType();
        if (selIdx == -1 || selType == SplineEditorClientState.SelectionType.NONE) {
            SplineEditorClientState.setHoveredAxis(SplineEditorClientState.AxisDrag.NONE);
            return;
        }

        SplinePoint pt = SplineEditorClientState.getModel().getPoints().get(selIdx);
        SplineVec3 ptPos = switch (selType) {
            case ANCHOR -> pt.getAnchor();
            case CONTROL_PREV -> pt.getControlPrev();
            case CONTROL_NEXT -> pt.getControlNext();
            default -> null;
        };

        if (ptPos == null) {
            SplineEditorClientState.setHoveredAxis(SplineEditorClientState.AxisDrag.NONE);
            return;
        }

        SplineEditorClientState.setHoveredAxis(hitTestGizmo(rayOrigin, rayDir, ptPos));
    }

    private static SplineEditorClientState.AxisDrag hitTestGizmo(Vec3 rayOrigin, Vec3 rayDir, SplineVec3 pos) {
        double px = pos.x();
        double py = pos.y();
        double pz = pos.z();
        double reachEnd = REACH;

        Vec3 rayEnd = rayOrigin.add(rayDir.scale(reachEnd));

        SplineEditorClientState.AxisDrag bestAxis = SplineEditorClientState.AxisDrag.NONE;
        double minDistance = Double.MAX_VALUE;

        // Size constants matching renderer
        float shaftLen = 1.5f;
        float thickness = 0.08f;

        for (SplineEditorClientState.AxisDrag axis : SplineEditorClientState.AxisDrag.values()) {
            if (axis == SplineEditorClientState.AxisDrag.NONE) continue;

            AABB aabb = getGizmoAxisAABB(px, py, pz, axis, shaftLen, thickness);
            Optional<Vec3> hit = aabb.clip(rayOrigin, rayEnd);
            if (hit.isPresent()) {
                double dist = rayOrigin.distanceTo(hit.get());
                if (dist < minDistance) {
                    minDistance = dist;
                    bestAxis = axis;
                }
            }
        }

        return bestAxis;
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

    private static boolean trySelectPoint(Vec3 rayOrigin, Vec3 rayDir) {
        SplineModel model = SplineEditorClientState.getModel();
        Vec3 rayEnd = rayOrigin.add(rayDir.scale(REACH));

        int bestIndex = -1;
        SplineEditorClientState.SelectionType bestType = SplineEditorClientState.SelectionType.NONE;
        double minDistance = Double.MAX_VALUE;

        for (int i = 0; i < model.getPoints().size(); i++) {
            SplinePoint pt = model.getPoints().get(i);

            // 1. Anchor (R=0.25)
            AABB aabbAnchor = getPointAABB(pt.getAnchor(), 0.25);
            Optional<Vec3> hitAnchor = aabbAnchor.clip(rayOrigin, rayEnd);
            if (hitAnchor.isPresent()) {
                double d = rayOrigin.distanceTo(hitAnchor.get());
                if (d < minDistance) {
                    minDistance = d;
                    bestIndex = i;
                    bestType = SplineEditorClientState.SelectionType.ANCHOR;
                }
            }

            // Handles are only selectable in EDIT mode
            if (SplineEditorClientState.getEditMode() == SplineEditorClientState.EditMode.EDIT) {
                // 2. Control Prev (R=0.15)
                AABB aabbPrev = getPointAABB(pt.getControlPrev(), 0.15);
                Optional<Vec3> hitPrev = aabbPrev.clip(rayOrigin, rayEnd);
                if (hitPrev.isPresent()) {
                    double d = rayOrigin.distanceTo(hitPrev.get());
                    if (d < minDistance) {
                        minDistance = d;
                        bestIndex = i;
                        bestType = SplineEditorClientState.SelectionType.CONTROL_PREV;
                    }
                }

                // 3. Control Next (R=0.15)
                AABB aabbNext = getPointAABB(pt.getControlNext(), 0.15);
                Optional<Vec3> hitNext = aabbNext.clip(rayOrigin, rayEnd);
                if (hitNext.isPresent()) {
                    double d = rayOrigin.distanceTo(hitNext.get());
                    if (d < minDistance) {
                        minDistance = d;
                        bestIndex = i;
                        bestType = SplineEditorClientState.SelectionType.CONTROL_NEXT;
                    }
                }
            }
        }

        if (bestIndex != -1) {
            SplineEditorClientState.setSelectedPoint(bestIndex, bestType);
            Log.info(TAG, "[SplineEditor] Selected point idx={}, type={}", bestIndex, bestType);
            return true;
        }

        return false;
    }

    private static AABB getPointAABB(SplineVec3 pos, double radius) {
        return new AABB(pos.x() - radius, pos.y() - radius, pos.z() - radius,
                        pos.x() + radius, pos.y() + radius, pos.z() + radius);
    }

    private static void addNewSplinePoint(Minecraft mc, Vec3 rayDir) {
        BlockPos hit = raycastToBlock(mc, rayDir);
        if (hit == null) return;

        // Place on top of block
        SplineVec3 pos = new SplineVec3(hit.getX() + 0.5, hit.getY() + 1.0, hit.getZ() + 0.5);
        
        // Height snapping, check if free or terrain-snap
        SplineEditorClientState.getModel().addPoint(pos);
        int size = SplineEditorClientState.getModel().getPoints().size();
        SplineEditorClientState.setSelectedPoint(size - 1, SplineEditorClientState.SelectionType.ANCHOR);
        Log.info(TAG, "[SplineEditor] Added new anchor point at {}", pos);
    }

    // ── Drag Logic ──

    private static void startGizmoDrag(Minecraft mc, Vec3 rayOrigin, Vec3 rayDir) {
        int selIdx = SplineEditorClientState.getSelectedPointIndex();
        SplineEditorClientState.SelectionType selType = SplineEditorClientState.getSelectedType();
        if (selIdx == -1) return;

        SplinePoint pt = SplineEditorClientState.getModel().getPoints().get(selIdx);
        SplineVec3 ptPos = switch (selType) {
            case ANCHOR -> pt.getAnchor();
            case CONTROL_PREV -> pt.getControlPrev();
            case CONTROL_NEXT -> pt.getControlNext();
            default -> null;
        };

        if (ptPos == null) return;

        SplineEditorClientState.AxisDrag axis = SplineEditorClientState.getHoveredAxis();
        SplineEditorClientState.setDraggingAxis(axis);

        dragStartPointPos = ptPos;
        dragStartAxisOrigin = new Vec3(ptPos.x(), ptPos.y(), ptPos.z());
        
        Vec3 axisDir = getGizmoWorldDir(axis);
        dragStartAxisValue = getClosestPointOnAxis(rayOrigin, rayDir, dragStartAxisOrigin, axisDir);
        Log.info(TAG, "[SplineEditor] Start Gizmo Drag axis={}, startVal={}", axis, dragStartAxisValue);
    }

    private static void continueGizmoDrag(Minecraft mc, Vec3 rayOrigin, Vec3 rayDir) {
        SplineEditorClientState.AxisDrag axis = SplineEditorClientState.getDraggingAxis();
        if (axis == SplineEditorClientState.AxisDrag.NONE || dragStartPointPos == null || dragStartAxisOrigin == null) return;

        Vec3 axisDir = getGizmoWorldDir(axis);
        double currentAxisValue = getClosestPointOnAxis(rayOrigin, rayDir, dragStartAxisOrigin, axisDir);

        double delta = currentAxisValue - dragStartAxisValue;

        // Calculate absolute position along the axis
        SplineVec3 deltaVec = new SplineVec3(axisDir.x * delta, axisDir.y * delta, axisDir.z * delta);
        SplineVec3 newPos = dragStartPointPos.add(deltaVec);

        int selIdx = SplineEditorClientState.getSelectedPointIndex();
        SplineEditorClientState.SelectionType selType = SplineEditorClientState.getSelectedType();
        if (selIdx == -1) return;

        SplinePoint pt = SplineEditorClientState.getModel().getPoints().get(selIdx);
        
        boolean shiftPressed = GLFW.glfwGetKey(mc.getWindow().getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(mc.getWindow().getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;

        // If Shift is pressed during drag of a handle, break the lock temporarily
        boolean prevLocked = pt.isLocked();
        if (shiftPressed && (selType == SplineEditorClientState.SelectionType.CONTROL_PREV || selType == SplineEditorClientState.SelectionType.CONTROL_NEXT)) {
            pt.setLocked(false);
        }

        switch (selType) {
            case ANCHOR -> pt.setAnchor(newPos);
            case CONTROL_PREV -> pt.setControlPrev(newPos);
            case CONTROL_NEXT -> pt.setControlNext(newPos);
        }

        // Restore lock state if broken temporarily
        if (shiftPressed) {
            pt.setLocked(prevLocked);
        }
    }

    private static void finishGizmoDrag() {
        Log.info(TAG, "[SplineEditor] Finish Gizmo Drag");
        SplineEditorClientState.setDraggingAxis(SplineEditorClientState.AxisDrag.NONE);
        dragStartPointPos = null;
        dragStartAxisOrigin = null;
    }

    private static Vec3 getGizmoWorldDir(SplineEditorClientState.AxisDrag axis) {
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
        // Closest point of the ray to the axis line, parameterized along axisDir:
        // s = (c*d - b*e) / (a*c - b*b). (b*e - c*d) would be the sign-flipped value,
        // which inverts the drag direction.
        return (c * d - b * e) / denom;
    }

    private static BlockPos raycastToBlock(Minecraft mc, Vec3 rayDir) {
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 origin = cam.getPosition();
        var ctx = new net.minecraft.world.level.ClipContext(
                origin, origin.add(rayDir.scale(REACH)),
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player);
        BlockHitResult hit = mc.level.clip(ctx);
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }
}
