package com.wsteam.wandscape.projection.client;

import java.util.ArrayList;
import java.util.List;

import com.wsteam.wandscape.projection.network.BuildingDebugRequestPacket;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.common.NeoForge;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Standalone debug-inspect mode controller.
 *
 * <p>Intercepts left-click BEFORE vanilla processes it via
 * {@link InputEvent.MouseButton.Pre} + {@code setCanceled(true)}.
 * When active:
 * <ul>
 *   <li>Left-click: raycast from camera, send {@link BuildingDebugRequestPacket} to server,
 *       server looks up building in {@link BuildingSavedData} and replies with
 *       {@link BuildingDebugResponsePacket} which opens the debug screen.</li>
 *   <li>Right-click / Escape / G again exits debug mode.</li>
 *   <li>Vanilla input is consumed so the player doesn't break blocks or open containers.</li>
 * </ul>
 */
public final class BuildingDebugController {

    private static final String TAG = "BuildingDebugController";
    private static final double DEBUG_REACH = 64.0;

    private static boolean wasLeftDown = false;
    private static boolean wasRightDown = false;
    private static boolean wasEscapeDown = false;
    private static boolean registered = false;
    private static boolean waitingForResponse = false;
    private static long requestTime = 0;

    private BuildingDebugController() {}

    public static void register() {
        if (registered) return;
        registered = true;
        var bus = NeoForge.EVENT_BUS;
        // Pre-event: cancel vanilla clicks BEFORE they are processed
        bus.addListener(net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre.class,
                BuildingDebugController::onMouseButtonPre);
        // Post-tick: handle exit keys and consume vanilla clicks
        bus.addListener(ClientTickEvent.Post.class, BuildingDebugController::onClientTickPost);
        Log.info(TAG, "[Debug] Controller registered (pre-event interception)");
    }

    public static void toggle() {
        boolean newActive = !BuildingDebugClientState.isActive();
        BuildingDebugClientState.setActive(newActive);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("[Debug] " + (newActive ? "§aON §f— left-click buildings to inspect" : "§cOFF")),
                    false);
        }
        if (!newActive) {
            waitingForResponse = false;
        }
    }

    // ── Pre-event: intercept left-click before vanilla ──────────────────────

    private static void onMouseButtonPre(net.neoforged.neoforge.client.event.InputEvent.MouseButton.Pre event) {
        if (!BuildingDebugClientState.isActive()) return;
        if (waitingForResponse) return; // don't spam packets while waiting

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // Left-click: inspect
        if (event.getButton() == 0 && !event.isCanceled()) {
            BlockPos hitPos = raycastBuildingPos(mc);
            if (hitPos != null) {
                event.setCanceled(true);
                waitingForResponse = true;
                requestTime = System.currentTimeMillis();
                PacketDistributor.sendToServer(new BuildingDebugRequestPacket(hitPos));
                Log.info(TAG, "[Debug] Sent request for pos={}", hitPos);
            }
        }
    }

    // ── Post-tick: handle exit + consume input ──────────────────────────────

    static void onClientTickPost(ClientTickEvent.Post event) {
        if (!BuildingDebugClientState.isActive()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.screen != null) return;

        // Timeout waiting for response
        if (waitingForResponse && System.currentTimeMillis() - requestTime > 3000) {
            waitingForResponse = false;
        }

        long window = mc.getWindow().getWindow();

        // Right-click / Escape: exit
        handleExit(mc, window);

        // Consume vanilla clicks that may have slipped through
        while (mc.options.keyAttack.consumeClick()) {}
        while (mc.options.keyUse.consumeClick()) {}
        while (mc.options.keyInventory.consumeClick()) {}
        while (mc.options.keyDrop.consumeClick()) {}
    }

    // ── Exit handling ───────────────────────────────────────────────────────

    private static void handleExit(Minecraft mc, long window) {
        boolean rightDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        boolean rightClicked = rightDown && !wasRightDown;
        wasRightDown = rightDown;

        boolean escapeDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        boolean escapeClicked = escapeDown && !wasEscapeDown;
        wasEscapeDown = escapeDown;

        if (rightClicked || escapeClicked) {
            BuildingDebugClientState.setActive(false);
            waitingForResponse = false;
            mc.player.displayClientMessage(
                    Component.literal("[Debug] §cOFF"), false);
        }
    }

    // ── Raycast ─────────────────────────────────────────────────────────────

    @javax.annotation.Nullable
    private static BlockPos raycastBuildingPos(Minecraft mc) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        Vec3 lookVec = new Vec3(camera.getLookVector().x(),
                camera.getLookVector().y(), camera.getLookVector().z());

        ClipContext ctx = new ClipContext(
                origin, origin.add(lookVec.scale(DEBUG_REACH)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player);
        BlockHitResult hit = mc.level.clip(ctx);

        if (hit.getType() != HitResult.Type.BLOCK) return null;
        return hit.getBlockPos();
    }
}
