package com.wsteam.wandscape.building.editor;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.network.BuildingEditorExitPacket;
import com.wsteam.wandscape.building.network.BuildingEditorExportPacket;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Per-tick lifecycle controller for the building editor.
 * Flight movement (WASD), keyboard shortcuts, text input focused fields.
 * No Screen — the overlay renders via {@link BuildingEditorOverlay}.
 */
public final class BuildingEditorController {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile float flyingSpeed = 0.15f;

    // Keyboard edge detection
    private static boolean wasEscapeDown = false;
    private static boolean wasEDown = false;
    private static boolean wasEnterDown = false;

    // Key edge tracking for text input (one-shot per key)
    private static final boolean[] wasKeyDown = new boolean[GLFW.GLFW_KEY_LAST + 1];

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

    // ── Client tick ──

    static void onClientTickPost(ClientTickEvent.Post event) {
        if (!BuildingEditorClientState.isEditing()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        // Note: no Screen object exists — we always run

        long window = mc.getWindow().getWindow();
        int mods = getModifiers(window);

        // 1. Flight movement (WASD/Space/Shift) — only when no field is focused
        if (BuildingEditorClientState.getFocusedField() == null) {
            handleFlightMovement(mc, window);
        } else {
            mc.player.setDeltaMovement(Vec3.ZERO);
        }

        // 2. Mouse clicks (AABB selection, dragging, pattern editing, panel interaction)
        BuildingEditorInputHandler.handleClicks(mc, window);

        // 3. Text input for focused field
        String focused = BuildingEditorClientState.getFocusedField();
        if (focused != null) {
            handleTextInput(window, mods);
        }

        // 4. Keyboard shortcuts (global — work even when field is focused)
        handleKeyboard(mc, window, mods);

        // 5. Drain vanilla input
        drainVanillaInput(mc);
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

    // ── Text input ──

    private static void handleTextInput(long window, int mods) {
        for (int key = GLFW.GLFW_KEY_A; key <= GLFW.GLFW_KEY_Z; key++) {
            tryKey(window, key, mods);
        }
        for (int key = GLFW.GLFW_KEY_0; key <= GLFW.GLFW_KEY_9; key++) {
            tryKey(window, key, mods);
        }
        tryKey(window, GLFW.GLFW_KEY_MINUS, mods);
        tryKey(window, GLFW.GLFW_KEY_SPACE, mods);
        tryKey(window, GLFW.GLFW_KEY_PERIOD, mods);
        tryKey(window, GLFW.GLFW_KEY_SLASH, mods);
        tryKey(window, GLFW.GLFW_KEY_COMMA, mods);
        tryKey(window, GLFW.GLFW_KEY_SEMICOLON, mods);
        tryKey(window, GLFW.GLFW_KEY_BACKSPACE, mods);
        tryKey(window, GLFW.GLFW_KEY_ENTER, mods);
        tryKey(window, GLFW.GLFW_KEY_KP_ENTER, mods);
        tryKey(window, GLFW.GLFW_KEY_ESCAPE, mods);
    }

    private static void tryKey(long window, int key, int mods) {
        boolean down = GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
        boolean clicked = down && !wasKeyDown[key];
        wasKeyDown[key] = down;
        if (clicked) {
            BuildingEditorInputHandler.handleKeyPress(key, mods);
        }
    }

    // ── Keyboard shortcuts ──

    private static void handleKeyboard(Minecraft mc, long window, int mods) {
        boolean escapeDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        boolean eDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_E) == GLFW.GLFW_PRESS;
        boolean enterDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ENTER) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_KP_ENTER) == GLFW.GLFW_PRESS;

        boolean escapeClicked = escapeDown && !wasEscapeDown;
        boolean eClicked = eDown && !wasEDown;
        boolean enterClicked = enterDown && !wasEnterDown;

        wasEscapeDown = escapeDown;
        wasEDown = eDown;
        wasEnterDown = enterDown;

        // Escape: if field focused → defocus. else → exit editor
        if (escapeClicked) {
            if (BuildingEditorClientState.getFocusedField() != null) {
                BuildingEditorClientState.setFocusedField(null);
            } else {
                doExit();
            }
        }

        // E: toggle panel visibility
        if (eClicked) {
            boolean visible = !BuildingEditorClientState.isScreenVisible();
            BuildingEditorClientState.setScreenVisible(visible);
            mc.player.displayClientMessage(
                    Component.literal("[BuildEditor] Panel: " + (visible ? "§ashown" : "§chidden")), true);
        }

        // Enter: export (only when no field focused, to avoid conflict with defocus)
        if (enterClicked && BuildingEditorClientState.getFocusedField() == null) {
            doExport();
        }
    }

    // ── Mouse scroll ──

    static void onMouseScroll(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent event) {
        if (!BuildingEditorClientState.isEditing()) return;
        event.setCanceled(true);
    }

    // ── Exit ──

    public static void doExit() {
        PacketDistributor.sendToServer(new BuildingEditorExitPacket());
        BuildingEditorClientState.exitEditMode();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("[BuildEditor] §eExited editor mode"), true);
        }
    }

    // ── Export ──

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
        // Don't drain sneaking — it's used for anchor selection (Shift+click)
        // Letters are consumed by flight logic above
    }

    // ── Modifiers ──

    private static int getModifiers(long window) {
        int mods = 0;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS)
            mods |= GLFW.GLFW_MOD_SHIFT;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS)
            mods |= GLFW.GLFW_MOD_CONTROL;
        return mods;
    }

    public static float getFlyingSpeed() { return flyingSpeed; }
    public static void setFlyingSpeed(float speed) { flyingSpeed = speed; }
}
