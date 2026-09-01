package com.wsteam.wandscape.content.road.client;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.content.road.core.SplineModel;
import com.wsteam.wandscape.content.road.core.SplinePoint;
import com.wsteam.wandscape.content.road.core.SplineVec3;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

/**
 * Handles mouse clicks, raycasting, point selection, and 3D axis Gizmo dragging for the spline editor.
 */
public final class SplineEditorInputHandler {
    private static final String TAG = "SplineEditorInputHandler";
    private static final double REACH = 512.0;

    private static final boolean wasLeftDown = false;
    private static final int tickCounter = 0;

    // Drag state
    private static SplineVec3 dragStartPointPos = null;
    private static Vec3 dragStartAxisOrigin = null;
    private static double dragStartAxisValue = 0.0;

    private SplineEditorInputHandler() {}

    public static void onLeftPress(Minecraft mc) {
        if (!SplineEditorClientState.isEditing()) return;
        if (RoadPlacementState.getActiveTool() != RoadPlacementState.ToolMode.SPLINE) return;

        Vec3 rayOrigin = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 rayDir = getMouseWorldRay(mc);

        // 1. Check Gizmo axis hit test first if a point is already selected
        int selIdx = SplineEditorClientState.getSelectedPointIndex();
        SplineEditorClientState.SelectionType selType = SplineEditorClientState.getSelectedType();

        SplineEditorClientState.AxisDrag hitAxis = SplineEditorClientState.AxisDrag.NONE;
        if (selIdx != -1 && selType != SplineEditorClientState.SelectionType.NONE) {
            SplinePoint pt = SplineEditorClientState.getModel().getPoints().get(selIdx);
            SplineVec3 ptPos = switch (selType) {
                case ANCHOR -> pt.getAnchor();
                case CONTROL_PREV -> pt.getControlPrev();
                case CONTROL_NEXT -> pt.getControlNext();
                default -> null;
            };
            if (ptPos != null) {
                hitAxis = hitTestGizmo(rayOrigin, rayDir, ptPos);
            }
        }

        if (hitAxis != SplineEditorClientState.AxisDrag.NONE) {
            SplineEditorClientState.setHoveredAxis(hitAxis);
            startGizmoDrag(mc, rayOrigin, rayDir, hitAxis);
            return;
        }

        // 2. Otherwise try selecting an existing point / handle in the world
        boolean selected = trySelectPoint(rayOrigin, rayDir);

        // 3. If no point clicked and in ADD mode, place a new anchor point
        if (!selected && SplineEditorClientState.getEditMode() == SplineEditorClientState.EditMode.ADD) {
            addNewSplinePoint(mc, rayDir);
        }
    }

    public static void onLeftRelease(Minecraft mc) {
        if (SplineEditorClientState.isDragging()) {
            finishGizmoDrag();
        }
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

    public static void updateGizmoHover(Vec3 rayOrigin, Vec3 rayDir) {
        if (SplineEditorClientState.isDragging()) {
            return;
        }

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

    public static SplineEditorClientState.AxisDrag hitTestGizmo(Vec3 rayOrigin, Vec3 rayDir, SplineVec3 pos) {
        double px = pos.x();
        double py = pos.y();
        double pz = pos.z();
        Vec3 gizmoCenter = new Vec3(px, py, pz);

        SplineEditorClientState.AxisDrag bestAxis = SplineEditorClientState.AxisDrag.NONE;
        double minDistance = Double.MAX_VALUE;

        float sMin = 0.15f;
        float sMax = 1.85f;
        double hitRadius = 0.35; // 35cm generous capsule tolerance for responsive dragging

        for (SplineEditorClientState.AxisDrag axis : SplineEditorClientState.AxisDrag.values()) {
            if (axis == SplineEditorClientState.AxisDrag.NONE) continue;

            Vec3 u = getGizmoWorldDir(axis);
            Vec3 w0 = rayOrigin.subtract(gizmoCenter);

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

            Vec3 pSeg = gizmoCenter.add(u.scale(s));
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

    public static boolean trySelectPoint(Vec3 rayOrigin, Vec3 rayDir) {
        SplineModel model = SplineEditorClientState.getModel();
        Vec3 rayEnd = rayOrigin.add(rayDir.scale(REACH));

        int bestIndex = -1;
        SplineEditorClientState.SelectionType bestType = SplineEditorClientState.SelectionType.NONE;
        double minDistance = Double.MAX_VALUE;

        for (int i = 0; i < model.getPoints().size(); i++) {
            SplinePoint pt = model.getPoints().get(i);

            // 1. Anchor (R=0.35)
            AABB aabbAnchor = getPointAABB(pt.getAnchor(), 0.35);
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
                // 2. Control Prev (R=0.22)
                AABB aabbPrev = getPointAABB(pt.getControlPrev(), 0.22);
                Optional<Vec3> hitPrev = aabbPrev.clip(rayOrigin, rayEnd);
                if (hitPrev.isPresent()) {
                    double d = rayOrigin.distanceTo(hitPrev.get());
                    if (d < minDistance) {
                        minDistance = d;
                        bestIndex = i;
                        bestType = SplineEditorClientState.SelectionType.CONTROL_PREV;
                    }
                }

                // 3. Control Next (R=0.22)
                AABB aabbNext = getPointAABB(pt.getControlNext(), 0.22);
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

    private static void startGizmoDrag(Minecraft mc, Vec3 rayOrigin, Vec3 rayDir, SplineEditorClientState.AxisDrag axis) {
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

        SplineEditorClientState.setDraggingAxis(axis);

        dragStartPointPos = ptPos;
        dragStartAxisOrigin = new Vec3(ptPos.x(), ptPos.y(), ptPos.z());
        
        Vec3 axisDir = getGizmoWorldDir(axis);
        dragStartAxisValue = getClosestPointOnAxis(rayOrigin, rayDir, dragStartAxisOrigin, axisDir);
        Log.info(TAG, "[SplineEditor] Start Gizmo Drag axis={}, startVal={}", axis, dragStartAxisValue);
    }

    public static void continueGizmoDrag(Minecraft mc, Vec3 rayOrigin, Vec3 rayDir) {
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

    public static void finishGizmoDrag() {
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
