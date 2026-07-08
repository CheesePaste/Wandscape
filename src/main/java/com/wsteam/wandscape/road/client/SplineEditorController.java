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

    public static void doBuildArray() {
        com.wsteam.wandscape.road.core.SplineModel model = SplineEditorClientState.getModel();
        com.wsteam.wandscape.road.core.RoadTemplate activeTemplate = SplineEditorClientState.getActiveTemplate();
        double stepDistance = SplineEditorClientState.getArrayStepDistance();

        if (model.getPoints().isEmpty() || activeTemplate == null || activeTemplate.getBlocks().isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§cCannot build: empty model or template"), true);
            }
            return;
        }

        java.util.List<com.wsteam.wandscape.road.core.CurveSample> samples = model.tessellate(stepDistance);
        if (samples.isEmpty()) return;

        com.google.gson.JsonArray tiles = new com.google.gson.JsonArray();

        for (com.wsteam.wandscape.road.core.CurveSample sample : samples) {
            SplineVec3 pos = sample.position();
            SplineVec3 tan = sample.tangent();
            org.joml.Vector3f forward = new org.joml.Vector3f((float)tan.x(), (float)tan.y(), (float)tan.z()).normalize();
            org.joml.Vector3f right = new org.joml.Vector3f(0, 1, 0).cross(forward);
            if (right.lengthSquared() < 0.0001f) {
                right.set(1, 0, 0).cross(forward);
            }
            right.normalize();
            org.joml.Vector3f up = new org.joml.Vector3f(forward).cross(right).normalize();

            org.joml.Matrix4f m = new org.joml.Matrix4f();
            m.set(
                    right.x, right.y, right.z, 0f,
                    up.x,    up.y,    up.z,    0f,
                    forward.x, forward.y, forward.z, 0f,
                    (float)pos.x(),   (float)pos.y(),   (float)pos.z(),   1f
            );

            float roll = (float) Math.toRadians(SplineEditorClientState.getArrayOffsetRoll());
            float pitch = (float) Math.toRadians(SplineEditorClientState.getArrayOffsetPitch());
            float yaw = (float) Math.toRadians(SplineEditorClientState.getArrayOffsetYaw());

            if (roll != 0) m.rotateLocalZ(roll);
            if (pitch != 0) m.rotateLocalX(pitch);
            if (yaw != 0) m.rotateLocalY(yaw);

            for (com.wsteam.wandscape.road.core.RoadTemplate.RoadTemplateBlock b : activeTemplate.getBlocks()) {
                org.joml.Vector4f localPos = new org.joml.Vector4f((float)b.x() + 0.5f, (float)b.y(), (float)b.z() + 0.5f, 1.0f);
                localPos.mul(m);

                int wx = (int) Math.floor(localPos.x);
                int wy = (int) Math.floor(localPos.y);
                int wz = (int) Math.floor(localPos.z);

                com.google.gson.JsonObject tile = new com.google.gson.JsonObject();
                com.google.gson.JsonArray posArr = new com.google.gson.JsonArray();
                posArr.add(wx);
                posArr.add(wy);
                posArr.add(wz);
                tile.add("pos", posArr);
                tile.addProperty("block", b.blockState());
                tiles.add(tile);
            }
        }
        
        if (tiles.isEmpty()) return;

        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new com.wsteam.wandscape.road.network.SplineBuildPacket(tiles.toString()));

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§aSent build task with " + tiles.size() + " blocks!"), true);
        }
    }
}
