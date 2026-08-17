package com.wsteam.wandscape.road.client;

import org.lwjgl.glfw.GLFW;

import com.wsteam.wandscape.shared.ui.panel.WandscapePanelState;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Per-tick input handler for road placement mode.
 *
 * <p>State machine (via RoadPlacementState):
 * <pre>
 *   IDLE       → LMB press over world → PLAN_START (start=end=ghost)
 *   PLAN_START → LMB drag             → PLAN_END   (end follows ghost)
 *   PLAN_END   → LMB drag             → PLAN_END   (overwrite end)
 *   PLAN_END   → Backspace            → PLAN_START (clear end)
 *   PLAN_START → Backspace            → IDLE       (clear start)
 *   PLAN_END   → ImGui 面板按钮发包     → IDLE (clearAll)
 * </pre>
 *
 * <p>Submission is done through the ImGui Road Studio button, not the keyboard.
 */
public final class RoadPlacementController {

    private static final String TAG = "RoadPlacementController";

    private static final double REACH = 64.0;

    // ── Input edge detection ──
    private static boolean wasLeftDown = false;
    private static boolean wasBackspaceDown = false;
    private static boolean wasEscapeDown = false;

    private static boolean registered = false;

    private RoadPlacementController() {}

    // ── Registration ──

    public static void register() {
        if (registered) return;
        registered = true;
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(ClientTickEvent.Post.class, RoadPlacementController::onClientTickPost);
        Log.info(TAG, "[RoadPlacement] Controller registered");
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Client tick ──
    // ═══════════════════════════════════════════════════════════════

    static void onClientTickPost(ClientTickEvent.Post event) {
        if (!RoadPlacementState.isProjecting()) return;

        // 面板隐藏时暂停道路放置输入，恢复时继续
        if (WandscapePanelState.isPanelHidden()) return;

        // Overview mode is compatible — OverviewFlightController already skips right-click
        // when road is projecting (see OverviewFlightController.onClientTickPost).
        // Was: if (OverviewClientState.isActive()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.screen != null) return;

        long window = mc.getWindow().getWindow();

        // ESC is defensive cleanup for the rare case where road placement is still
        // active after the panel has been closed. While the panel is open, ESC is
        // intercepted by WandscapePanelController's exit pipeline (ScreenEvent.Opening).
        handleEscapeInput(mc, window);

        // Cursor lifted or Native Road Studio active → panel UI mode
        boolean cursorLifted = (WandscapePanelState.isPanelOpen() && WandscapePanelState.isCursorLifted())
                || com.wsteam.wandscape.road.client.studio.RoadStudioOverlay.isVisible();
        boolean uiWantsMouse = RoadEditorInputHelper.wantsMouse();
        if (cursorLifted) {
            // When cursor is over 3D world (outside studio panel), handle world clicks for Replace/Fill/DestroyFill!
            if (!uiWantsMouse && RoadPlacementState.getActiveTool() != RoadPlacementState.ToolMode.SPLINE) {
                updateGhostPosition(mc);
                handleMouseButtons(mc, window);
            }
            drainVanillaInput(mc);
            return;
        }

        // Normal world interaction mode (PLACING phase)
        updateGhostPosition(mc);
        handleMouseButtons(mc, window);
        handleKeyboard(window);
        drainAttackUse(mc);
    }

    // ── Mouse Raycasting & Ghost position ──

    public static Vec3 getMouseWorldRay(Minecraft mc) {
        long window = mc.getWindow().getWindow();
        double[] mx = new double[1], my = new double[1];
        org.lwjgl.glfw.GLFW.glfwGetCursorPos(window, mx, my);
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

    // ── Mouse button handling (Left-click drag-box selection for REPLACE, FILL, DESTROY_FILL) ──

    private static boolean isLmbDragging = false;

    private static void handleMouseButtons(Minecraft mc, long window) {
        RoadPlacementState.ToolMode tool = RoadPlacementState.getActiveTool();
        if (tool == RoadPlacementState.ToolMode.SPLINE) return;

        boolean leftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean leftClicked = leftDown && !wasLeftDown;
        boolean leftReleased = !leftDown && wasLeftDown;
        wasLeftDown = leftDown;

        BlockPos ghostPos = RoadPlacementState.getGhostPos();

        if (leftClicked && ghostPos != null) {
            // Press LMB: start selection box/area
            RoadPlacementState.setStartPos(ghostPos);
            RoadPlacementState.setEndPos(ghostPos);
            if (tool == RoadPlacementState.ToolMode.DESTROY_FILL) {
                BlockState state = mc.level != null ? mc.level.getBlockState(ghostPos) : null;
                String blockName = state != null
                        ? net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()
                        : "minecraft:stone";
                RoadPlacementState.setRefBlockId(blockName);
            }
            isLmbDragging = true;
        } else if (leftDown && isLmbDragging && ghostPos != null) {
            // Drag LMB: dynamically update endPos to expand selection box/area
            RoadPlacementState.setEndPos(ghostPos);
        } else if (leftReleased) {
            isLmbDragging = false;
        }
    }

    // ── Keyboard handling (no ESC — handled by handleEscapeInput) ──

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
            // PLAN_END → clear end, back to PLAN_START
            RoadPlacementState.clearEndPos();
        } else if (RoadPlacementState.isPlanning()) {
            // PLAN_START → clear start, back to IDLE
            RoadPlacementState.clearStartPos();
        }
    }

    // ── ESC handling (runs before cursorLifted guard) ──

    /**
     * Defensive ESC cleanup for the rare case where road placement is still active
     * after the panel has been closed. While the panel is open, ESC is intercepted
     * by WandscapePanelController's exit pipeline (ScreenEvent.Opening).
     */
    private static void handleEscapeInput(Minecraft mc, long window) {
        boolean escapeDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        boolean escapeClicked = escapeDown && !wasEscapeDown;
        wasEscapeDown = escapeDown;
        if (!escapeClicked) return;

        // Panel not open → exit road placement mode entirely
        if (!WandscapePanelState.isPanelOpen()
                && RoadPlacementState.getRoadPhase() != RoadPlacementState.RoadPhase.PLACING) {
            WandscapePanelState.exitCurrentSubMode();
            WandscapePanelState.setSubMode(WandscapePanelState.SubMode.NONE);
        }
        // With the panel open, ESC is handled by WandscapePanelController instead
    }

    // ── Input draining ──

    private static void drainVanillaInput(Minecraft mc) {
        while (mc.options.keyAttack.consumeClick()) {}
        while (mc.options.keyUse.consumeClick()) {}
        while (mc.options.keyJump.consumeClick()) {}
        while (mc.options.keyShift.consumeClick()) {}
        while (mc.options.keyInventory.consumeClick()) {}
        while (mc.options.keyDrop.consumeClick()) {}
        while (mc.options.keySprint.consumeClick()) {}
    }

    private static void drainAttackUse(Minecraft mc) {
        while (mc.options.keyAttack.consumeClick()) {}
        while (mc.options.keyUse.consumeClick()) {}
        while (mc.options.keyInventory.consumeClick()) {}
    }
}
