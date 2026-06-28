package com.wsteam.wandscape.projection.client;

import org.lwjgl.glfw.GLFW;
import com.wsteam.wandscape.Config;
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
 * Per-tick input handler for soul projection mode.
 *
 * <p>Handles:
 * <ul>
 *   <li>WASD / Space / Shift — free flight movement in camera direction</li>
 *   <li>Ctrl — sprint (2x speed)</li>
 *   <li>Mouse scroll — cycle building selection</li>
 *   <li>Left-click — place selected building at ghost position</li>
 *   <li>Right-click / Escape — exit projection mode</li>
 *   <li>Ghost position raycasting — update crosshair target</li>
 * </ul>
 *
 * <p>Raw GLFW input polling follows the {@code RoadEditorRenderer} pattern.
 * All vanilla clicks are consumed to prevent block interaction or inventory opens.
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

        // ── 1. Flight movement (always works) ──
        handleFlightMovement(mc, window);

        // ── Building bar mode: no ghost, flight only; clicks/scroll handled by bar ──
        if (buildingBarOpen) {
            drainVanillaInput(mc);
            checkRange(mc);
            return;
        }

        if (cursorLifted) {
            drainVanillaInput(mc);
            return;
        }

        // ── 2. Ghost position update ──
        updateGhostPosition(mc);

        // ── 4. Click handling ──
        handleClicks(mc, window);

        // ── 5. Escape key ──
        handleEscape(mc, window);

        // ── 6. Drain all vanilla clicks ──
        drainVanillaInput(mc);

        // ── 7. Range check ──
        checkRange(mc);
    }

    // ── Flight movement ──

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
                camera.getLookVector().y(),
                camera.getLookVector().z());
        // Right = forward × world up
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
            float speed = ProjectionClientState.getFlyingSpeed();
            if (sprintDown) speed *= 2.0f;
            // Scale by 20 to convert per-tick speed to per-second velocity
            mc.player.setDeltaMovement(moveDir.scale(speed * 20.0));
        } else {
            mc.player.setDeltaMovement(Vec3.ZERO);
        }
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
        // Send exit packet BEFORE changing client state
        PacketDistributor.sendToServer(new ProjectionExitPacket());
        ProjectionClientState.exitProjection();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("[Projection] §eSoul returned to body"),
                    true);
        }
    }

    // ── Vanilla input drain ──

    private static void drainVanillaInput(Minecraft mc) {
        // Consume all vanilla click inputs (same pattern as RoadEditor lines 419-425)
        while (mc.options.keyAttack.consumeClick()) {}
        while (mc.options.keyUse.consumeClick()) {}
        while (mc.options.keyJump.consumeClick()) {}
        while (mc.options.keyShift.consumeClick()) {}
        // Also block inventory, drop, etc.
        while (mc.options.keyInventory.consumeClick()) {}
        while (mc.options.keyDrop.consumeClick()) {}
        // Block sprint toggle (vanilla sprint via Ctrl)
        while (mc.options.keySprint.consumeClick()) {}
    }

    // ── Range check ──

    private static void checkRange(Minecraft mc) {
        BlockPos anchor = ProjectionClientState.getBodyAnchor();
        if (anchor == null || mc.player == null) return;

        int maxRange = Config.PROJECTION_MAX_RANGE.get();
        if (maxRange <= 0) return; // 0 = no limit

        double dist = Math.sqrt(anchor.distSqr(mc.player.blockPosition()));
        if (dist > maxRange) {
            mc.player.displayClientMessage(
                    Component.literal("[Projection] §eExceeded max projection range (" + maxRange + " blocks) — returning to body"),
                    false);
            doExit();
        }
    }
}
