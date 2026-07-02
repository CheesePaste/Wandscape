package com.wsteam.wandscape.projection.client;

import org.lwjgl.glfw.GLFW;
import com.wsteam.wandscape.projection.data.BuildingSlot;
import com.wsteam.wandscape.projection.network.ProjectionExitPacket;
import com.wsteam.wandscape.projection.network.ProjectionPlacePacket;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.shared.ui.panel.WandscapePanelState;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Per-tick input handler for ground-based building placement mode.
 *
 * <p>Player walks to the build site normally. Ghost preview raycasts from
 * the camera position. Right-click places the selected building.
 * Movement is blocked globally by WandscapePanelController when the cursor is lifted.
 */
public final class ProjectionFlightController {

    private static final String TAG = "ProjectionFlightController";

    /** Extended reach distance in projection mode (blocks). */
    private static final double PROJECTION_REACH = 64.0;

    // ── Input edge detection state ──
    private static boolean wasRightDown = false;
    private static boolean wasEscapeDown = false;

    private static boolean registered = false;

    private ProjectionFlightController() {}

    // ── Registration ──

    public static void register() {
        if (registered) return;
        registered = true;
        var bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        bus.addListener(ClientTickEvent.Post.class, ProjectionFlightController::onClientTickPost);
        bus.addListener(InputEvent.MouseScrollingEvent.class, ProjectionFlightController::onMouseScroll);
        Log.info(TAG, "[Projection] Flight controller registered");
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Client tick ──
    // ═══════════════════════════════════════════════════════════════

    static void onClientTickPost(ClientTickEvent.Post event) {
        if (!ProjectionClientState.isProjecting()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.screen != null) return;

        boolean buildingBarOpen = WandscapePanelState.isBuildingBarOpen();
        boolean cursorLifted = WandscapePanelState.isPanelOpen() && WandscapePanelState.isCursorLifted();

        long window = mc.getWindow().getWindow();

        // ── Building bar mode: no ghost, drain all input ──
        if (buildingBarOpen) {
            drainVanillaInput(mc);
            return;
        }

        if (cursorLifted) {
            drainVanillaInput(mc);
            return;
        }

        // ── Walking mode: ghost preview, handle clicks, drain only attack/use ──
        updateGhostPosition(mc);
        handleClicks(mc, window);
        handleEscape(mc, window);
        drainAttackUse(mc);
    }

    // ── Scroll wheel ──

    /** Handle mouse scroll via NeoForge's {@link InputEvent.MouseScrollingEvent}.
     *  Accumulates delta in client state; the tick handler processes it. */
    static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!ProjectionClientState.isProjecting()) return;
        event.setCanceled(true);

        // Building bar open — scroll does NOT cycle building selection (removed per user request)
        // No scroll-to-switch outside bar — selection is bar-only
    }

    // ── Ghost position ──

    private static void updateGhostPosition(Minecraft mc) {
        // Perform a long-range raycast from camera
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        Vec3 lookVec = new Vec3(camera.getLookVector().x(),
                camera.getLookVector().y(),
                camera.getLookVector().z());

        // Use the MC level's clip for accuracy
        var clipCtx = new net.minecraft.world.level.ClipContext(
                origin,
                origin.add(lookVec.scale(PROJECTION_REACH)),
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                mc.player);
        BlockHitResult hit = mc.level.clip(clipCtx);

        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos targetPos = hit.getBlockPos();
            // Place on the face the player is looking at (adjacent block)
            BlockPos placePos = targetPos.relative(hit.getDirection());
            ProjectionClientState.setGhostPos(placePos);

            // Check overlap with existing buildings
            BuildingApi api = WandscapeApis.getBuildingApi();
            boolean overlap = api != null && api.getBuildingAt(placePos) != null;
            ProjectionClientState.setOverlapDetected(overlap);
        } else {
            ProjectionClientState.setGhostPos(null);
        }
    }

    // ── Click handling ──

    private static void handleClicks(Minecraft mc, long window) {
        boolean rightDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

        boolean rightClicked = rightDown && !wasRightDown;
        wasRightDown = rightDown;

        // Right-click: place building
        if (rightClicked) {
            handlePlace(mc);
        }
    }

    private static void handlePlace(Minecraft mc) {
        BlockPos ghostPos = ProjectionClientState.getGhostPos();
        if (ghostPos == null || ProjectionClientState.isOverlapDetected()) {
            if (ghostPos != null) {
                mc.player.displayClientMessage(
                        Component.literal("[Projection] §cCannot place here — overlapping building"),
                        true);
            }
            return;
        }

        var slots = ProjectionClientState.getBuildingSlots();
        int index = ProjectionClientState.getSelectedSlotIndex();
        if (slots.isEmpty() || index < 0 || index >= slots.size()) return;

        BuildingSlot slot = slots.get(index);
        PacketDistributor.sendToServer(new ProjectionPlacePacket(slot.id(), ghostPos));

        Log.info(TAG, "[Projection] Placed '{}' at {}", slot.displayName(), ghostPos);
    }

    // ── Escape ──

    private static void handleEscape(Minecraft mc, long window) {
        boolean escapeDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        boolean escapeClicked = escapeDown && !wasEscapeDown;
        wasEscapeDown = escapeDown;

        if (!escapeClicked) return;

        // Panel not open → exit projection entirely
        if (!WandscapePanelState.isPanelOpen()) {
            doExit();
        }
        // When panel is open, ESC handled by WandscapePanelController via ScreenEvent.Opening
    }

    // ── Exit ──

    private static void doExit() {
        PacketDistributor.sendToServer(new ProjectionExitPacket());
        ProjectionClientState.exitProjection();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("[Projection] §eExited building placement mode"),
                    true);
        }
    }

    // ── Input draining ──

    /** Drain all vanilla input — used when bar is open or cursor is lifted. */
    private static void drainVanillaInput(Minecraft mc) {
        while (mc.options.keyAttack.consumeClick()) {}
        while (mc.options.keyUse.consumeClick()) {}
        while (mc.options.keyJump.consumeClick()) {}
        while (mc.options.keyShift.consumeClick()) {}
        while (mc.options.keyInventory.consumeClick()) {}
        while (mc.options.keyDrop.consumeClick()) {}
        while (mc.options.keySprint.consumeClick()) {}
    }

    /** Drain only attack/use/inventory — player can walk normally. */
    private static void drainAttackUse(Minecraft mc) {
        while (mc.options.keyAttack.consumeClick()) {}
        while (mc.options.keyUse.consumeClick()) {}
        while (mc.options.keyInventory.consumeClick()) {}
    }
}
