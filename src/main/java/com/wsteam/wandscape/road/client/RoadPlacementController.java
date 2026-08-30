package com.wsteam.wandscape.road.client;

import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.ui.panel.WandscapePanelState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * Input controller for Road Placement modes (Replace, Fill, DestroyFill).
 * Supports click-drag bounding box selection as well as dual-corner Gizmo fine-tuning.
 */
public final class RoadPlacementController {

    private static final String TAG = "RoadPlacementController";
    private static final double REACH = 512.0;

    // Drag state for Gizmos
    private static BlockPos dragStartBlockPos = null;
    private static Vec3 dragStartAxisOrigin = null;
    private static double dragStartAxisValue = 0.0;

    // Drag state for Box selection
    private static boolean isLmbDragging = false;

    // Input edge detection
    private static boolean wasBackspaceDown = false;
    private static boolean wasEscapeDown = false;
    private static boolean registered = false;

    private RoadPlacementController() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, RoadPlacementController::onClientTickPost);
        Log.info(TAG, "[RoadPlacement] Controller registered");
    }

    static void onClientTickPost(ClientTickEvent.Post event) {
        if (!RoadPlacementState.isProjecting()) return;
        if (WandscapePanelState.isPanelHidden()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.screen != null) return;

        long window = mc.getWindow().getWindow();
        handleEscapeInput(mc, window);

        updateGhostPosition(mc);

        // While dragging box with LMB, update endPos as ghost moves
        if (isLmbDragging && !RoadPlacementState.isDraggingGizmo()) {
            boolean leftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            if (leftDown) {
                BlockPos ghostPos = RoadPlacementState.getGhostPos();
                if (ghostPos != null) {
                    if (RoadPlacementState.getActiveTool() == RoadPlacementState.ToolMode.DESTROY_FILL
                            || RoadPlacementState.getActiveTool() == RoadPlacementState.ToolMode.REPLACE) {
                        BlockPos startPos = RoadPlacementState.getStartPos();
                        if (startPos != null) {
                            RoadPlacementState.setEndPos(new BlockPos(ghostPos.getX(), startPos.getY(), ghostPos.getZ()));
                        } else {
                            RoadPlacementState.setEndPos(ghostPos);
                        }
                    } else {
                        RoadPlacementState.setEndPos(ghostPos);
                    }
                }
            } else {
                isLmbDragging = false;
            }
        }

        handleKeyboard(window);
        drainVanillaInput(mc);
    }

    // ── Immediate Event-Driven Click Processing ──

    public static void onLeftPress(Minecraft mc) {
        if (mc.level == null) return;
        Vec3 rayOrigin = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 rayDir = getMouseWorldRay(mc);

        // 1. If clicking on an active Gizmo handle, start Gizmo drag
        RoadPlacementState.GizmoTarget hoveredTarget = RoadPlacementState.getHoveredTarget();
        RoadPlacementState.AxisDrag hoveredAxis = RoadPlacementState.getHoveredAxis();
        if (hoveredTarget != RoadPlacementState.GizmoTarget.NONE && hoveredAxis != RoadPlacementState.AxisDrag.NONE) {
            startGizmoDrag(mc, rayOrigin, rayDir);
            isLmbDragging = false;
            return;
        }

        // 2. Otherwise start a new drag-box selection on the targeted terrain block
        updateGhostPosition(mc);
        BlockPos ghostPos = RoadPlacementState.getGhostPos();
        if (ghostPos != null) {
            RoadPlacementState.setStartPos(ghostPos);
            RoadPlacementState.setEndPos(ghostPos);
            RoadPlacementState.resetGizmoState();

            if (RoadPlacementState.getActiveTool() == RoadPlacementState.ToolMode.DESTROY_FILL) {
                BlockState state = mc.level.getBlockState(ghostPos);
                String blockName = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                RoadPlacementState.setRefBlockId(blockName);
            }
            isLmbDragging = true;
        }
    }

    public static void onLeftRelease(Minecraft mc) {
        if (RoadPlacementState.isDraggingGizmo()) {
            finishGizmoDrag();
        }
        isLmbDragging = false;
    }

    // ── Gizmo Hover & Drag Logic ──

    public static void updateGizmoHover(Vec3 rayOrigin, Vec3 rayDir) {
        if (RoadPlacementState.isDraggingGizmo()) return;

        BlockPos startPos = RoadPlacementState.getStartPos();
        BlockPos endPos = RoadPlacementState.getEndPos();

        if (startPos == null && endPos == null) {
            RoadPlacementState.setHoveredTarget(RoadPlacementState.GizmoTarget.NONE);
            RoadPlacementState.setHoveredAxis(RoadPlacementState.AxisDrag.NONE);
            return;
        }

        // Test Start Gizmo
        RoadPlacementState.AxisDrag startAxis = (startPos != null) ? hitTestGizmo(rayOrigin, rayDir, startPos) : RoadPlacementState.AxisDrag.NONE;
        double startDist = Double.MAX_VALUE;
        if (startAxis != RoadPlacementState.AxisDrag.NONE && startPos != null) {
            startDist = rayOrigin.distanceTo(new Vec3(startPos.getX() + 0.5, startPos.getY() + 0.5, startPos.getZ() + 0.5));
        }

        // Test End Gizmo
        RoadPlacementState.AxisDrag endAxis = (endPos != null) ? hitTestGizmo(rayOrigin, rayDir, endPos) : RoadPlacementState.AxisDrag.NONE;
        double endDist = Double.MAX_VALUE;
        if (endAxis != RoadPlacementState.AxisDrag.NONE && endPos != null) {
            endDist = rayOrigin.distanceTo(new Vec3(endPos.getX() + 0.5, endPos.getY() + 0.5, endPos.getZ() + 0.5));
        }

        if (startAxis != RoadPlacementState.AxisDrag.NONE && (endAxis == RoadPlacementState.AxisDrag.NONE || startDist <= endDist)) {
            RoadPlacementState.setHoveredTarget(RoadPlacementState.GizmoTarget.START);
            RoadPlacementState.setHoveredAxis(startAxis);
        } else if (endAxis != RoadPlacementState.AxisDrag.NONE) {
            RoadPlacementState.setHoveredTarget(RoadPlacementState.GizmoTarget.END);
            RoadPlacementState.setHoveredAxis(endAxis);
        } else {
            RoadPlacementState.setHoveredTarget(RoadPlacementState.GizmoTarget.NONE);
            RoadPlacementState.setHoveredAxis(RoadPlacementState.AxisDrag.NONE);
        }
    }

    public static RoadPlacementState.AxisDrag hitTestGizmo(Vec3 rayOrigin, Vec3 rayDir, BlockPos pos) {
        double px = pos.getX() + 0.5;
        double py = pos.getY() + 0.5;
        double pz = pos.getZ() + 0.5;
        Vec3 gizmoCenter = new Vec3(px, py, pz);

        RoadPlacementState.AxisDrag bestAxis = RoadPlacementState.AxisDrag.NONE;
        double minDistance = Double.MAX_VALUE;

        float sMin = 0.15f;
        float sMax = 1.85f;
        double hitRadius = 0.35; // 35cm generous capsule tolerance for responsive dragging

        for (RoadPlacementState.AxisDrag axis : RoadPlacementState.AxisDrag.values()) {
            if (axis == RoadPlacementState.AxisDrag.NONE) continue;

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

    public static void startGizmoDrag(Minecraft mc, Vec3 rayOrigin, Vec3 rayDir) {
        RoadPlacementState.GizmoTarget target = RoadPlacementState.getHoveredTarget();
        RoadPlacementState.AxisDrag axis = RoadPlacementState.getHoveredAxis();
        if (target == RoadPlacementState.GizmoTarget.NONE || axis == RoadPlacementState.AxisDrag.NONE) return;

        BlockPos pos = (target == RoadPlacementState.GizmoTarget.START)
                ? RoadPlacementState.getStartPos()
                : RoadPlacementState.getEndPos();
        if (pos == null) return;

        RoadPlacementState.setDraggingTarget(target);
        RoadPlacementState.setDraggingAxis(axis);

        dragStartBlockPos = pos;
        dragStartAxisOrigin = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

        Vec3 axisDir = getGizmoWorldDir(axis);
        dragStartAxisValue = getClosestPointOnAxis(rayOrigin, rayDir, dragStartAxisOrigin, axisDir);
        Log.info(TAG, "[RoadPlacement] Start Gizmo Drag target={}, axis={}, startVal={}", target, axis, dragStartAxisValue);
    }

    public static void continueGizmoDrag(Minecraft mc, Vec3 rayOrigin, Vec3 rayDir) {
        RoadPlacementState.GizmoTarget target = RoadPlacementState.getDraggingTarget();
        RoadPlacementState.AxisDrag axis = RoadPlacementState.getDraggingAxis();
        if (target == RoadPlacementState.GizmoTarget.NONE || axis == RoadPlacementState.AxisDrag.NONE
                || dragStartBlockPos == null || dragStartAxisOrigin == null) return;

        Vec3 axisDir = getGizmoWorldDir(axis);
        double currentAxisValue = getClosestPointOnAxis(rayOrigin, rayDir, dragStartAxisOrigin, axisDir);

        double delta = currentAxisValue - dragStartAxisValue;
        int shift = (int) Math.round(delta);

        int shiftX = (int) Math.round(axisDir.x * shift);
        int shiftY = (int) Math.round(axisDir.y * shift);
        int shiftZ = (int) Math.round(axisDir.z * shift);

        BlockPos newPos = dragStartBlockPos.offset(shiftX, shiftY, shiftZ);

        if (target == RoadPlacementState.GizmoTarget.START) {
            RoadPlacementState.setStartPos(newPos);
            if ((RoadPlacementState.getActiveTool() == RoadPlacementState.ToolMode.DESTROY_FILL
                    || RoadPlacementState.getActiveTool() == RoadPlacementState.ToolMode.REPLACE) && shiftY != 0) {
                BlockPos endPos = RoadPlacementState.getEndPos();
                if (endPos != null) {
                    RoadPlacementState.setEndPos(new BlockPos(endPos.getX(), newPos.getY(), endPos.getZ()));
                }
            }
        } else if (target == RoadPlacementState.GizmoTarget.END) {
            RoadPlacementState.setEndPos(newPos);
            if ((RoadPlacementState.getActiveTool() == RoadPlacementState.ToolMode.DESTROY_FILL
                    || RoadPlacementState.getActiveTool() == RoadPlacementState.ToolMode.REPLACE) && shiftY != 0) {
                BlockPos startPos = RoadPlacementState.getStartPos();
                if (startPos != null) {
                    RoadPlacementState.setStartPos(new BlockPos(startPos.getX(), newPos.getY(), startPos.getZ()));
                }
            }
        }
    }

    public static void finishGizmoDrag() {
        Log.info(TAG, "[RoadPlacement] Finish Gizmo Drag");
        RoadPlacementState.setDraggingTarget(RoadPlacementState.GizmoTarget.NONE);
        RoadPlacementState.setDraggingAxis(RoadPlacementState.AxisDrag.NONE);
        dragStartBlockPos = null;
        dragStartAxisOrigin = null;
    }

    public static Vec3 getGizmoWorldDir(RoadPlacementState.AxisDrag axis) {
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

    // ── Mouse Raycasting & Ghost position ──

    public static Vec3 getMouseWorldRay(Minecraft mc) {
        long window = mc.getWindow().getWindow();
        double[] mx = new double[1], my = new double[1];
        org.lwjgl.glfw.GLFW.glfwGetCursorPos(window, mx, my);
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

    private static void updateGhostPosition(Minecraft mc) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        Vec3 rayDir = getMouseWorldRay(mc);

        var clipCtx = new ClipContext(
                origin,
                origin.add(rayDir.scale(REACH)),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                mc.player);
        BlockHitResult hit = mc.level.clip(clipCtx);

        if (hit.getType() == HitResult.Type.BLOCK) {
            RoadPlacementState.setGhostPos(hit.getBlockPos());
        } else {
            RoadPlacementState.setGhostPos(null);
        }
    }

    // ── Keyboard handling ──

    private static void handleKeyboard(long window) {
        boolean backspaceDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_BACKSPACE) == GLFW.GLFW_PRESS;
        boolean backspaceClicked = backspaceDown && !wasBackspaceDown;
        wasBackspaceDown = backspaceDown;

        if (backspaceClicked) {
            handleBackspace();
        }
    }

    private static void handleBackspace() {
        if (RoadPlacementState.hasEnd()) {
            RoadPlacementState.clearEndPos();
        } else if (RoadPlacementState.isPlanning()) {
            RoadPlacementState.clearStartPos();
        }
    }

    private static void handleEscapeInput(Minecraft mc, long window) {
        boolean escapeDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        boolean escapeClicked = escapeDown && !wasEscapeDown;
        wasEscapeDown = escapeDown;
        if (!escapeClicked) return;

        if (!WandscapePanelState.isPanelOpen()
                && RoadPlacementState.getRoadPhase() != RoadPlacementState.RoadPhase.PLACING) {
            WandscapePanelState.exitCurrentSubMode();
            WandscapePanelState.setSubMode(WandscapePanelState.SubMode.NONE);
        }
    }

    private static void drainVanillaInput(Minecraft mc) {
        while (mc.options.keyAttack.consumeClick()) {}
        while (mc.options.keyUse.consumeClick()) {}
        while (mc.options.keyJump.consumeClick()) {}
        while (mc.options.keyShift.consumeClick()) {}
        while (mc.options.keyInventory.consumeClick()) {}
        while (mc.options.keyDrop.consumeClick()) {}
        while (mc.options.keySprint.consumeClick()) {}
    }
}
