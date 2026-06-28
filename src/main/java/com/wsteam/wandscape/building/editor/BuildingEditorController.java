package com.wsteam.wandscape.building.editor;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.network.BuildingEditorExitPacket;
import com.wsteam.wandscape.building.network.BuildingEditorExportPacket;
import com.wsteam.wandscape.imgui.ImGuiManager;
import com.wsteam.wandscape.shared.ui.panel.WandscapePanelState;

import imgui.ImGui;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Per-tick lifecycle for the building editor.
 *
 * <p>Mouse model:
 * <ul>
 *   <li>Cursor always visible (ImGui releases mouse)</li>
 *   <li><b>Right-click held</b> → camera rotation (MC-style)</li>
 *   <li><b>Otherwise</b> → WASD flight + left-click world interaction</li>
 * </ul>
 *
 * <p>The panel is rendered via ImGui ({@link BuildingEditorImGui}).
 */
public final class BuildingEditorController {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile float flyingSpeed = 0.15f;

    // Keyboard edge
    private static boolean wasEscapeDown = false;
    private static boolean wasEnterDown = false;

    // Right-click camera state
    private static boolean cameraActive = false;

    private static int tickCount = 0;
    private static boolean registered = false;

    private BuildingEditorController() {}

    public static void register() {
        if (registered) return;
        registered = true;
        var bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        bus.addListener(ClientTickEvent.Post.class, BuildingEditorController::onClientTickPost);
        bus.addListener(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent.class,
                BuildingEditorController::onMouseScroll);
        LOGGER.info("[BuildEditor] Controller registered");
    }

    static void onClientTickPost(ClientTickEvent.Post event) {
        if (!BuildingEditorClientState.isEditing()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        long window = mc.getWindow().getWindow();
        boolean rightDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        boolean imguiReady = ImGuiManager.isInitialized();
        boolean imguiWantsKb = imguiReady && ImGui.getIO().getWantCaptureKeyboard();

        // ── Right-click camera rotation ──
        if (!cameraActive && rightDown) {
            // Start: let MC grab the mouse (its own internal cursor state)
            cameraActive = true;
            mc.mouseHandler.grabMouse();
        } else if (cameraActive && !rightDown) {
            // End: release back to visible cursor
            cameraActive = false;
            mc.mouseHandler.releaseMouse();
        }

        // When panel cursor is lifted, skip world clicks but still allow camera rotation
        boolean cursorLifted = WandscapePanelState.isPanelOpen() && WandscapePanelState.isCursorLifted();

        if (cameraActive) {
            // MC handles the look input natively when mouse is grabbed.
            // We just need to zero out our custom flight movement.
            mc.player.setDeltaMovement(Vec3.ZERO);
        } else {
            // ── Flight (only when ImGui doesn't want keyboard) ──
            if (!imguiWantsKb) {
                handleFlightMovement(mc, window);
            } else {
                mc.player.setDeltaMovement(Vec3.ZERO);
            }

            // ── World clicks (skip if mouse is over ImGui panel) ──
            double[] mx = new double[1], my = new double[1];
            GLFW.glfwGetCursorPos(window, mx, my);
            float guiScale = (float) mc.getWindow().getGuiScale();
            float mouseGuiX = (float) (mx[0] / guiScale);
            boolean overPanel = mouseGuiX >= BuildingEditorImGui.panelLeftEdge;

            tickCount++;
            if (tickCount % 40 == 0) {
                LOGGER.info("[BuildEditor] Controller heartbeat: tick={} cameraActive={} overPanel={} mouseX={} panelEdge={} editing={}",
                        tickCount, cameraActive, overPanel, (int)mouseGuiX, (int)BuildingEditorImGui.panelLeftEdge,
                        BuildingEditorClientState.isEditing());
            }

            if (!overPanel && !cursorLifted) {
                BuildingEditorInputHandler.handleClicks(mc, window);
            }
        }

        // ── Keyboard shortcuts (only when not camera rotating) ──
        handleKeyboard(mc, window, imguiWantsKb);

        // ── Drain vanilla keyboard (mouse blocked by ImGuiManager event cancel) ──
        if (!imguiWantsKb) {
            drainVanillaInput(mc);
        }
    }

    // ── Flight ──

    private static void handleFlightMovement(Minecraft mc, long window) {
        boolean wDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS;
        boolean aDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS;
        boolean sDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS;
        boolean dDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS;
        boolean spaceDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS;
        boolean shiftDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        boolean sprintDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;

        if (!wDown && !aDown && !sDown && !dDown && !spaceDown && !shiftDown) {
            mc.player.setDeltaMovement(Vec3.ZERO);
            return;
        }

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 forward = new Vec3(camera.getLookVector().x(),
                camera.getLookVector().y(), camera.getLookVector().z());
        Vec3 right = forward.cross(new Vec3(0, 1, 0)).normalize();
        Vec3 up = new Vec3(0, 1, 0);

        Vec3 moveDir = Vec3.ZERO;
        if (wDown) moveDir = moveDir.add(forward);
        if (sDown) moveDir = moveDir.subtract(forward);
        if (aDown) moveDir = moveDir.subtract(right);
        if (dDown) moveDir = moveDir.add(right);
        if (spaceDown) moveDir = moveDir.add(up);
        if (shiftDown) moveDir = moveDir.subtract(up);

        if (moveDir.lengthSqr() > 1e-6) {
            moveDir = moveDir.normalize();
            float speed = flyingSpeed;
            if (sprintDown) speed *= 2.0f;
            mc.player.setDeltaMovement(moveDir.scale(speed * 20.0));
        } else {
            mc.player.setDeltaMovement(Vec3.ZERO);
        }
    }

    // ── Keyboard shortcuts ──

    private static void handleKeyboard(Minecraft mc, long window, boolean imguiWantsKb) {
        boolean escapeDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        boolean enterDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ENTER) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_KP_ENTER) == GLFW.GLFW_PRESS;

        boolean escapeClicked = escapeDown && !wasEscapeDown;
        boolean enterClicked = enterDown && !wasEnterDown;

        wasEscapeDown = escapeDown;
        wasEnterDown = enterDown;

        // Escape: exit editor (only when ImGui doesn't own keyboard AND panel is not open)
        if (escapeClicked && !imguiWantsKb && !WandscapePanelState.isPanelOpen()) {
            doExit();
        }

        // Enter: export (only when ImGui doesn't own keyboard)
        if (enterClicked && !imguiWantsKb) {
            doExport();
        }
    }

    // ── Mouse scroll ──

    static void onMouseScroll(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent event) {
        if (!BuildingEditorClientState.isEditing()) return;
        if (ImGuiManager.isInitialized() && ImGui.getIO().getWantCaptureMouse()) return;
        event.setCanceled(true);
    }

    // ── Exit / Export ──

    public static void doExit() {
        PacketDistributor.sendToServer(new BuildingEditorExitPacket());
        BuildingEditorClientState.exitEditMode();
        ImGuiManager.setVisible(false);
        if (cameraActive) {
            cameraActive = false;
            Minecraft.getInstance().mouseHandler.releaseMouse();
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("[BuildEditor] §eExited editor mode"), true);
        }
    }

    public static void doExport() {
        String json = BuildingEditorClientState.buildExportJson();
        if (json == null || json.isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("[BuildEditor] §cNothing to export — set an AABB first"), true);
            }
            return;
        }
        PacketDistributor.sendToServer(new BuildingEditorExportPacket(json, true));
        LOGGER.info("[BuildEditor] Export packet sent ({} chars)", json.length());
    }

    // ── Vanilla drain ──

    private static void drainVanillaInput(Minecraft mc) {
        while (mc.options.keyAttack.consumeClick()) {}
        while (mc.options.keyUse.consumeClick()) {}
        while (mc.options.keyJump.consumeClick()) {}
        while (mc.options.keyInventory.consumeClick()) {}
        while (mc.options.keyDrop.consumeClick()) {}
        while (mc.options.keySprint.consumeClick()) {}
    }

    public static float getFlyingSpeed() { return flyingSpeed; }
}
