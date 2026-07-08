package com.wsteam.wandscape.road.client;

import org.lwjgl.glfw.GLFW;

import com.wsteam.wandscape.imgui.ImGuiManager;
import com.wsteam.wandscape.road.core.SplineVec3;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.ui.panel.WandscapePanelState;

import imgui.ImGui;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Per-tick controller for the Spline Road Editor.
 * Manages right-click camera rotation, WASD flight, and blocks normal gameplay.
 */
public final class SplineEditorController {
    private static final String TAG = "SplineEditorController";
    private static volatile float flyingSpeed = 0.15f;

    private static boolean wasEscapeDown = false;
    private static boolean cameraActive = false;
    private static boolean registered = false;

    private SplineEditorController() {}

    public static void register() {
        if (registered) return;
        registered = true;
        var bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        bus.addListener(ClientTickEvent.Post.class, SplineEditorController::onClientTickPost);
        Log.info(TAG, "[SplineEditor] Controller registered");
    }

    static void onClientTickPost(ClientTickEvent.Post event) {
        if (!SplineEditorClientState.isEditing()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        long window = mc.getWindow().getWindow();
        boolean rightDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        boolean imguiReady = ImGuiManager.isInitialized();
        boolean imguiWantsKb = imguiReady && ImGui.getIO().getWantCaptureKeyboard();

        // ── Right-click camera rotation ──
        if (!cameraActive && rightDown) {
            cameraActive = true;
            mc.mouseHandler.grabMouse();
        } else if (cameraActive && !rightDown) {
            cameraActive = false;
            mc.mouseHandler.releaseMouse();
        }

        boolean cursorLifted = WandscapePanelState.isPanelOpen() && WandscapePanelState.isCursorLifted();

        if (cameraActive) {
            // Flight movement while holding right-click
            if (!imguiWantsKb) {
                handleFlightMovement(mc, window);
            } else {
                mc.player.setDeltaMovement(Vec3.ZERO);
            }
        } else {
            // Lock movement when not rotating
            mc.player.setDeltaMovement(Vec3.ZERO);

            // Skip interaction if hovering ImGui panel
            double[] mx = new double[1], my = new double[1];
            GLFW.glfwGetCursorPos(window, mx, my);

            // Compare raw mouse X to the ImGui window's absolute X position
            boolean overPanel = mx[0] >= SplineEditorImGui.panelLeftEdge;

            if (!overPanel && !cursorLifted) {
                SplineEditorInputHandler.handleClicks(mc, window);
            }
        }

        // ── Keyboard shortcuts ──
        handleKeyboard(mc, window, imguiWantsKb);

        // ── Drain vanilla inputs ──
        if (!imguiWantsKb) {
            drainVanillaInput(mc);
        }
    }

    private static void handleFlightMovement(Minecraft mc, long window) {
        boolean wDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS;
        boolean aDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS;
        boolean sDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS;
        boolean dDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS;
        boolean spaceDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS;
        boolean shiftDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;

        if (!wDown && !aDown && !sDown && !dDown && !spaceDown && !shiftDown) {
            mc.player.setDeltaMovement(Vec3.ZERO);
            return;
        }

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 forward = new Vec3(camera.getLookVector().x(), camera.getLookVector().y(), camera.getLookVector().z());
        Vec3 right = forward.cross(new Vec3(0, 1, 0)).normalize();
        Vec3 up = new Vec3(0, 1, 0);

        Vec3 moveDir = Vec3.ZERO;
        if (wDown) moveDir = moveDir.add(forward);
        if (sDown) moveDir = moveDir.subtract(forward);
        if (aDown) moveDir = moveDir.subtract(right);
        if (dDown) moveDir = moveDir.add(right);
        if (spaceDown) moveDir = moveDir.add(up);
        if (shiftDown) moveDir = moveDir.subtract(up);

        if (moveDir.lengthSqr() > 0) {
            moveDir = moveDir.normalize().scale(flyingSpeed);
            mc.player.setDeltaMovement(moveDir);
        } else {
            mc.player.setDeltaMovement(Vec3.ZERO);
        }
    }

    private static void handleKeyboard(Minecraft mc, long window, boolean imguiWantsKb) {
        boolean escDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        if (escDown && !wasEscapeDown) {
            if (!cameraActive) {
                // Exit editor mode
                SplineEditorClientState.exitEditMode();
                ImGuiManager.toggle(); // Turn off ImGui if active
            }
        }
        wasEscapeDown = escDown;

        // Shortcut for deleting points (Only when ImGui is not focusing typing)
        if (!imguiWantsKb && !cameraActive) {
            boolean delDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_DELETE) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_BACKSPACE) == GLFW.GLFW_PRESS;
            if (delDown) {
                int selected = SplineEditorClientState.getSelectedPointIndex();
                if (selected != -1) {
                    SplineEditorClientState.getModel().removePoint(selected);
                    SplineEditorClientState.setSelectedPoint(-1, SplineEditorClientState.SelectionType.NONE);
                }
            }
        }
    }

    private static void drainVanillaInput(Minecraft mc) {
        while (mc.options.keyAttack.consumeClick()) {}
        while (mc.options.keyUse.consumeClick()) {}
    }
}
