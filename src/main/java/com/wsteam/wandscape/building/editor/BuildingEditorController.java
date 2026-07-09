package com.wsteam.wandscape.building.editor;

import org.lwjgl.glfw.GLFW;
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
import com.wsteam.wandscape.shared.log.Log;

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

    private static final String TAG = "BuildingEditorController";
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
        bus.addListener(net.neoforged.neoforge.client.event.RenderLevelStageEvent.class, BuildingEditorController::onRenderLevelStage);
        bus.addListener(net.neoforged.neoforge.client.event.InputEvent.MouseScrollingEvent.class,
                BuildingEditorController::onMouseScroll);
        Log.info(TAG, "[BuildEditor] Controller registered");
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
            // Flight movement while holding right-click
            if (imguiWantsKb) {
                mc.player.setDeltaMovement(Vec3.ZERO);
            }
        } else {
            // No right-click: no flight, allow world interaction
            mc.player.setDeltaMovement(Vec3.ZERO);

            // ── World clicks (skip if mouse is over ImGui panel) ──
            double[] mx = new double[1], my = new double[1];
            GLFW.glfwGetCursorPos(window, mx, my);
            float guiScale = (float) mc.getWindow().getGuiScale();
            float mouseGuiX = (float) (mx[0] / guiScale);
            boolean overPanel = mouseGuiX >= BuildingEditorImGui.panelLeftEdge;

            tickCount++;
            if (tickCount % 40 == 0) {
                Log.info(TAG, "[BuildEditor] Controller heartbeat: tick={} cameraActive={} overPanel={} mouseX={} panelEdge={} editing={}",
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

    // ── Flight & Camera Sync (Per Frame) ──
    private static long lastFrameNanos = 0;

    static void onRenderLevelStage(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (!BuildingEditorClientState.isEditing()) return;
        if (event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_SKY) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        
        // Sync rotation natively from player every frame
        BuildingEditorClientState.setCamRotation(mc.player.getYRot(), mc.player.getXRot());

        // Frame-time delta
        long now = System.nanoTime();
        if (lastFrameNanos == 0) lastFrameNanos = now;
        double elapsed = (now - lastFrameNanos) / 1_000_000_000.0;
        lastFrameNanos = now;
        if (elapsed > 0.05) elapsed = 0.05;

        // If not holding right-click or if ImGui is capturing input, don't fly
        boolean imguiReady = ImGuiManager.isInitialized();
        boolean imguiWantsKb = imguiReady && ImGui.getIO().getWantCaptureKeyboard();
        if (!cameraActive || imguiWantsKb) return;

        long window = mc.getWindow().getWindow();
        float forward = 0, strafe = 0, vertical = 0;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS) forward += 1;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS) forward -= 1;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS) strafe -= 1;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS) strafe += 1;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS) vertical += 1;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS) vertical -= 1;

        if (forward != 0 || strafe != 0 || vertical != 0) {
            boolean sprintDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                    || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
            
            Vec3 fwd = Vec3.directionFromRotation(0, BuildingEditorClientState.getCamYaw());
            Vec3 right = fwd.cross(new Vec3(0, 1, 0)).normalize();
            
            float speed = flyingSpeed * 20.0f; // Scale it to be comparable to previous logic (BPS)
            if (sprintDown) speed *= 2.0f;
            
            double move = speed * elapsed;
            double moveX = (fwd.x * forward + right.x * strafe) * move;
            double moveZ = (fwd.z * forward + right.z * strafe) * move;
            double moveY = vertical * move;
            
            BuildingEditorClientState.setCamPosition(
                    BuildingEditorClientState.getCamX() + moveX,
                    BuildingEditorClientState.getCamY() + moveY,
                    BuildingEditorClientState.getCamZ() + moveZ
            );
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

        // Only handle scroll when right-click is held (camera is active)
        if (!cameraActive) {
            return; // let vanilla handle it (e.g. hotbar)
        }
        
        event.setCanceled(true);
        double delta = event.getScrollDeltaY();
        if (delta == 0) return;

        Minecraft mc = Minecraft.getInstance();
        long window = mc.getWindow().getWindow();
        boolean ctrlDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;

        if (ctrlDown) {
            // Ctrl + Scroll -> adjust flying speed
            float factor = (float) Math.pow(1.3, delta);
            flyingSpeed = Math.max(0.02f, Math.min(5.0f, flyingSpeed * factor));
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal(String.format("[BuildEditor] §eSpeed: %.2f", flyingSpeed)), true);
            }
        } else {
            // Scroll -> move camera forward/backward
            Vec3 dir = Vec3.directionFromRotation(
                    BuildingEditorClientState.getCamPitch(), BuildingEditorClientState.getCamYaw());
            double move = delta * 4.0; // SCROLL_SPEED
            BuildingEditorClientState.setCamPosition(
                    BuildingEditorClientState.getCamX() + dir.x * move,
                    BuildingEditorClientState.getCamY() + dir.y * move,
                    BuildingEditorClientState.getCamZ() + dir.z * move
            );
        }
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
        Log.info(TAG, "[BuildEditor] Export packet sent ({} chars)", json.length());
    }

    public static void doExportRoadTemplate() {
        if (!BuildingEditorClientState.hasAABB()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("[BuildEditor] §cNothing to export — set an AABB first"), true);
            }
            return;
        }

        String id = BuildingEditorClientState.getBuildingId();
        if (id == null || id.isEmpty()) {
            id = "custom_road_" + (System.currentTimeMillis() % 10000);
        }

        com.wsteam.wandscape.road.core.RoadTemplate t = new com.wsteam.wandscape.road.core.RoadTemplate(id);

        java.util.List<com.wsteam.wandscape.building.data.BlockOffset> pat = BuildingEditorClientState.getPattern();
        java.util.Map<String, String> bm = BuildingEditorClientState.getBlockMapping();

        for (com.wsteam.wandscape.building.data.BlockOffset off : pat) {
            String stateStr = bm.get(off.toKey());
            if (stateStr != null) {
                t.addBlock(off.x(), off.y(), off.z(), stateStr);
            }
        }

        com.wsteam.wandscape.road.client.SplineEditorClientState.registerTemplate(t);

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("[BuildEditor] §aExported & registered road template: " + id), true);
        }
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
