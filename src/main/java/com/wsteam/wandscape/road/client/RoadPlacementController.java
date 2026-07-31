package com.wsteam.wandscape.road.client;

import org.lwjgl.glfw.GLFW;

import com.wsteam.wandscape.road.network.DestroyFillPacket;
import com.wsteam.wandscape.road.network.FillBoxPacket;
import com.wsteam.wandscape.road.network.RoadPlacePacket;
import com.wsteam.wandscape.shared.ui.panel.WandscapePanelState;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Per-tick input handler for road placement mode.
 *
 * <p>State machine (via RoadPlacementState):
 * <pre>
 *   IDLE       → right-click → PLAN_START (startPos set)
 *   PLAN_START → left-click  → PLAN_END   (endPos set)
 *   PLAN_START → right-click → PLAN_START (overwrite startPos)
 *   PLAN_END   → left-click  → PLAN_END   (overwrite endPos)
 *   PLAN_END   → right-click → IDLE       (clear all)
 *   PLAN_END   → Enter       → publish → IDLE
 *   PLAN_START → Backspace   → IDLE       (clear start)
 *   PLAN_END   → Backspace   → PLAN_START (clear end)
 * </pre>
 */
public final class RoadPlacementController {

    private static final String TAG = "RoadPlacementController";

    private static final double REACH = 64.0;

    // ── Input edge detection ──
    private static boolean wasRightDown = false;
    private static boolean wasLeftDown = false;
    private static boolean wasEnterDown = false;
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

        // Overview mode is compatible — OverviewFlightController already skips right-click
        // when road is projecting (see OverviewFlightController.onClientTickPost).
        // Was: if (OverviewClientState.isActive()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.screen != null) return;

        long window = mc.getWindow().getWindow();

        // ESC must work in both PLACING (cursor in game) and BAR (cursor lifted) phases.
        // When cursor is lifted (mouse released), vanilla MC does NOT open the PauseScreen
        // on ESC — it grabs the mouse back instead. So we cannot rely on ScreenEvent.Opening
        // to handle ESC; we do it here directly.
        handleEscapeInput(mc, window);

        // Cursor lifted → panel UI mode: drain all input
        boolean cursorLifted = WandscapePanelState.isPanelOpen() && WandscapePanelState.isCursorLifted();
        if (cursorLifted) {
            drainVanillaInput(mc);
            return;
        }

        // Normal world interaction mode (PLACING phase)
        updateGhostPosition(mc);
        handleMouseButtons(mc, window);
        handleKeyboard(mc, window);
        drainAttackUse(mc);
    }

    // ── Ghost position ──

    private static void updateGhostPosition(Minecraft mc) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        Vec3 lookVec = new Vec3(camera.getLookVector().x(),
                camera.getLookVector().y(),
                camera.getLookVector().z());

        var clipCtx = new ClipContext(
                origin,
                origin.add(lookVec.scale(REACH)),
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

    // ── Mouse button handling ──

    private static void handleMouseButtons(Minecraft mc, long window) {
        boolean rightDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        boolean leftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        boolean rightClicked = rightDown && !wasRightDown;
        boolean leftClicked = leftDown && !wasLeftDown;
        wasRightDown = rightDown;
        wasLeftDown = leftDown;

        if (rightClicked) {
            handleRightClick(mc);
        }
        if (leftClicked) {
            handleLeftClick(mc);
        }
    }

    private static void handleRightClick(Minecraft mc) {
        BlockPos ghostPos = RoadPlacementState.getGhostPos();
        if (ghostPos == null) return;

        if (RoadPlacementState.isReady()) {
            // PLAN_END → right-click: clear all, return to IDLE
            RoadPlacementState.clearAll();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("[Road] §eCleared — right-click to set new start"), true);
            }
        } else if (RoadPlacementState.isDestroyFill()) {
            // Destroy/Fill: capture reference block + position
            RoadPlacementState.setStartPos(ghostPos);
            BlockState state = mc.level != null ? mc.level.getBlockState(ghostPos) : null;
            String blockName = state != null
                    ? net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()
                    : "unknown";
            RoadPlacementState.setRefBlockId(blockName);
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("[Destroy/Fill] §aRef block: " + blockName + " at Y=" + ghostPos.getY()
                                + " §7— left-click to set area, right-click to move ref"), true);
            }
        } else {
            // IDLE or PLAN_START → right-click: set / overwrite startPos
            RoadPlacementState.setStartPos(ghostPos);
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("[Road] §aStart point set at " + ghostPos.toShortString()
                                + " §7— left-click to set end, right-click to move start"), true);
            }
        }
    }

    private static void handleLeftClick(Minecraft mc) {
        BlockPos ghostPos = RoadPlacementState.getGhostPos();
        if (ghostPos == null) return;

        if (!RoadPlacementState.isPlanning()) return; // IDLE → no action

        // PLAN_START or PLAN_END → set / overwrite endPos
        RoadPlacementState.setEndPos(ghostPos);
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal(tag() + " §aEnd point set at " + ghostPos.toShortString()
                            + " §7— Enter to publish, right-click to clear, Backspace to undo end"), true);
        }
    }

    // ── Keyboard handling (no ESC — handled by handleEscapeInput) ──

    private static void handleKeyboard(Minecraft mc, long window) {
        boolean enterDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ENTER) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_KP_ENTER) == GLFW.GLFW_PRESS;
        boolean backspaceDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_BACKSPACE) == GLFW.GLFW_PRESS;

        boolean enterClicked = enterDown && !wasEnterDown;
        boolean backspaceClicked = backspaceDown && !wasBackspaceDown;
        wasEnterDown = enterDown;
        wasBackspaceDown = backspaceDown;

        if (enterClicked) {
            handleEnter(mc);
        }
        if (backspaceClicked) {
            handleBackspace(mc);
        }
    }

    private static void handleEnter(Minecraft mc) {
        if (!RoadPlacementState.isReady()) {
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal(tag() + " §eSet both start and end points first"), true);
            }
            return;
        }

        BlockPos start = RoadPlacementState.getStartPos();
        BlockPos end = RoadPlacementState.getEndPos();

        if (RoadPlacementState.isFill()) {
            String presetId = RoadPlacementState.getSelectedPreset().id();
            PacketDistributor.sendToServer(new FillBoxPacket(presetId, start, end));
            Log.info(TAG, "[Fill] Published box: preset={} from={} to={}",
                    presetId, start.toShortString(), end.toShortString());
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("[Fill] §aFill task submitted! NPC will fill the cube."), true);
            }
        } else if (RoadPlacementState.isDestroyFill()) {
            PacketDistributor.sendToServer(new DestroyFillPacket(start, end));
            Log.info(TAG, "[DestroyFill] Published: ref={} to={}", start.toShortString(), end.toShortString());
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("[Destroy/Fill] §aTerrain flatten task submitted! NPC will flatten the area."), true);
            }
        } else {
            String presetId = RoadPlacementState.getSelectedPreset().id();
            PacketDistributor.sendToServer(new RoadPlacePacket(presetId, start, end));
            Log.info(TAG, "[Road] Published road: preset={} from={} to={}",
                    presetId, start.toShortString(), end.toShortString());
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("[Road] §aRoad task submitted! NPC will pave the path."), true);
            }
        }

        // Return to IDLE
        RoadPlacementState.clearAll();
    }

    /** Chat prefix for the active tool mode. */
    private static String tag() {
        if (RoadPlacementState.isFill()) return "[Fill]";
        if (RoadPlacementState.isDestroyFill()) return "[Destroy/Fill]";
        return "[Road]";
    }

    private static void handleBackspace(Minecraft mc) {
        if (RoadPlacementState.hasEnd()) {
            // PLAN_END → clear end, back to PLAN_START
            RoadPlacementState.clearEndPos();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("[Road] §eEnd point cleared — set new end or Backspace to cancel"), true);
            }
        } else if (RoadPlacementState.isPlanning()) {
            // PLAN_START → clear start, back to IDLE
            RoadPlacementState.clearStartPos();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("[Road] §eCancelled"), true);
            }
        }
    }

    // ── ESC handling (runs before cursorLifted guard) ──

    /**
     * Handles ESC in BAR phase only (cursor lifted). PLACING phase ESC is handled by
     * {@code WandscapePanelController.onScreenOpen} via PauseScreen interception,
     * matching BUILD mode behavior.
     *
     * <p>After BAR → exit we also consume the key press to prevent the vanilla
     * KeyboardHandler from calling {@code pauseGame()} on the same ESC press
     * (ScreenEvent.Opening can't catch it because activeSubMode is already NONE).</p>
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
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("[Road] §eExited road placement mode"), true);
            }
        }
        // When panel is open, ESC handled by WandscapePanelController via ScreenEvent.Opening
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
