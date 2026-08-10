package com.wsteam.wandscape.projection.client;

import org.lwjgl.glfw.GLFW;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.projection.BuildingRotation;
import com.wsteam.wandscape.projection.data.BuildingSlot;
import com.wsteam.wandscape.projection.network.ProjectionExitPacket;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.shared.ui.panel.WandscapePanelState;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
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
    private static boolean wasLeftDown = false;
    private static boolean wasRightDown = false;
    private static boolean wasEscapeDown = false;
    private static boolean wasScreenOpen = false;
    private static long lastLeftClickTime = 0;

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

        // Skip when overview mode is active — OverviewFlightController handles all input
        if (com.wsteam.wandscape.overview.client.OverviewClientState.isActive()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        long window = mc.getWindow().getWindow();

        // Closing a Screen with the mouse (e.g. Construction UI buttons) would otherwise
        // re-appear as a fresh left-click on the next tick and cancel the pinned ghost.
        // Baseline the button edge-detection whenever a screen just closed.
        boolean screenOpen = mc.screen != null;
        if (wasScreenOpen && !screenOpen) {
            wasLeftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            wasRightDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        }
        wasScreenOpen = screenOpen;
        if (screenOpen) return;

        boolean buildingBarOpen = WandscapePanelState.isBuildingBarOpen();
        boolean cursorLifted = WandscapePanelState.isPanelOpen() && WandscapePanelState.isCursorLifted();

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
        // Let overview mode handle its own scroll
        if (com.wsteam.wandscape.overview.client.OverviewClientState.isActive()) return;
        event.setCanceled(true);

        // Building bar open — scroll does NOT cycle building selection (removed per user request)
        // No scroll-to-switch outside bar — selection is bar-only
    }

    // ── Ghost position ──

    private static void updateGhostPosition(Minecraft mc) {
        // Pinned: ghost stays fixed — only re-check overlap against the fixed position
        if (ProjectionClientState.isPinned()) {
            BlockPos fixed = ProjectionClientState.getGhostPos();
            if (fixed != null) {
                BuildingApi api = WandscapeApis.getBuildingApi();
                ProjectionClientState.setOverlapDetected(api != null && api.getBuildingAt(fixed) != null);
            }
            return;
        }

        long window = mc.getWindow().getWindow();
        boolean rightDown = window != 0L && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

        // Perform a long-range raycast from camera center
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
            BlockPos placePos = targetPos.relative(hit.getDirection());
            if (rightDown || ProjectionClientState.getGhostPos() == null) {
                ProjectionClientState.setGhostPos(placePos);
            }
        }

        BlockPos curGhost = ProjectionClientState.getGhostPos();
        if (curGhost != null) {
            BuildingApi api = WandscapeApis.getBuildingApi();
            boolean overlap = api != null && api.getBuildingAt(curGhost) != null;
            ProjectionClientState.setOverlapDetected(overlap);
        }
    }

    // ── Click handling ──

    private static void handleClicks(Minecraft mc, long window) {
        boolean leftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean rightDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

        boolean leftClicked = leftDown && !wasLeftDown;
        boolean rightClicked = rightDown && !wasRightDown;
        wasLeftDown = leftDown;
        wasRightDown = rightDown;

        // Pinned: double-click outside Gizmo opens construction screen; Gizmo drag handled by BuildGizmoController.
        // The mouse ray must actually hit the ghost building — the camera-center ray alone (which
        // determines the ghost position) is not a valid aim in overview where the cursor may be free.
        if (ProjectionClientState.isPinned()) {
            boolean overGizmo = BuildGizmoController.getHoveredAxis() != BuildGizmoController.AxisDrag.NONE;
            if (leftClicked && !overGizmo && isMouseRayHittingGhost(mc)) {
                long now = System.currentTimeMillis();
                if (now - lastLeftClickTime < 400) {
                    openConstructionScreen(mc);
                    lastLeftClickTime = 0;
                } else {
                    lastLeftClickTime = now;
                }
            }
            return;
        }

        // Left-click: rotate building 90° CCW
        if (leftClicked) {
            ProjectionClientState.rotate();
            int steps = ProjectionClientState.getRotationSteps();
            String direction = switch (steps) {
                case 1 -> "90°";
                case 2 -> "180°";
                case 3 -> "270°";
                default -> "0°";
            };
        }

        // Right-click: pin the ghost at its position and open the construction screen
        if (rightClicked) {
            BlockPos ghostPos = ProjectionClientState.getGhostPos();
            if (ghostPos == null) {
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("[Projection] §c").append(
                                    com.wsteam.wandscape.shared.ui.I18n.name(
                                            "message.wandscape.projection.cannot_pin",
                                            "无法固定 — 准星没有对准方块")), true);
                }
                return;
            }
            ProjectionClientState.setPinned(true);
            openConstructionScreen(mc);
            Log.info(TAG, "[Projection] Ghost pinned at {}", ghostPos);
        }
    }

    /** Ray distance for the ghost hit test (matches gizmo reach). */
    private static final double HIT_REACH = 128.0;

    /**
     * Mouse-ray hit test against the pinned ghost building's world AABB
     * (boundary rotated to the current rotation steps, same offsets as the
     * rendered outline). Double-click submit must be aimed with the actual
     * cursor ray — the camera-center ray is not sufficient in overview,
     * where the cursor may be free while the camera looks elsewhere.
     */
    public static boolean isMouseRayHittingGhost(Minecraft mc) {
        BlockPos pos = ProjectionClientState.getGhostPos();
        if (pos == null || mc.level == null) return false;

        var slots = ProjectionClientState.getBuildingSlots();
        int index = ProjectionClientState.getSelectedSlotIndex();
        BuildingConfig config = (slots.isEmpty() || index < 0 || index >= slots.size())
                ? null : BuildingConfigLoader.getInstance().get(slots.get(index).id());
        if (config == null || config.boundary() == null) return false;

        BuildingConfig.BoundaryBox boundary =
                BuildingRotation.rotateBoundary(config.boundary(), ProjectionClientState.getRotationSteps());

        double x0 = pos.getX() + boundary.min().x() + 0.5;
        double y0 = pos.getY() + boundary.min().y() + 0.5;
        double z0 = pos.getZ() + boundary.min().z() + 0.5;
        double x1 = pos.getX() + boundary.max().x() + 0.5;
        double y1 = pos.getY() + boundary.max().y() + 0.5;
        double z1 = pos.getZ() + boundary.max().z() + 0.5;

        Vec3 origin = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 dir = com.wsteam.wandscape.road.client.RoadPlacementController.getMouseWorldRay(mc);
        return new AABB(x0, y0, z0, x1, y1, z1).clip(origin, origin.add(dir.scale(HIT_REACH))).isPresent();
    }

    /** Open the construction screen for the pinned ghost position (also used by overview mode). */
    public static void openConstructionScreen(Minecraft mc) {
        BlockPos pos = ProjectionClientState.getGhostPos();
        if (pos == null) return;

        var slots = ProjectionClientState.getBuildingSlots();
        int index = ProjectionClientState.getSelectedSlotIndex();
        if (slots.isEmpty() || index < 0 || index >= slots.size()) return;

        BuildingSlot slot = slots.get(index);
        BuildingConfig config = BuildingConfigLoader.getInstance().get(slot.id());
        if (config == null) return;

        mc.setScreen(new ConstructionScreen(config, slot.id(), pos,
                ProjectionClientState.getRotationSteps()));
    }

    // ── Escape ──

    private static void handleEscape(Minecraft mc, long window) {
        boolean escapeDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        boolean escapeClicked = escapeDown && !wasEscapeDown;
        wasEscapeDown = escapeDown;

        if (!escapeClicked) return;

        // Panel not open → handle projection-level ESC. While the panel is open, ESC is
        // intercepted by WandscapePanelController's exit pipeline (ScreenEvent.Opening).
        if (!WandscapePanelState.isPanelOpen()) {
            // Pinned (gizmo phase): ESC first unpins back to aiming phase; next ESC exits.
            if (ProjectionClientState.isPinned()) {
                ProjectionClientState.setPinned(false);
                Log.info(TAG, "[Projection] Esc: unpinned ghost, staying in projection");
                return;
            }
            doExit();
        }
    }

    // ── Exit ──

    private static void doExit() {
        PacketDistributor.sendToServer(new ProjectionExitPacket());
        ProjectionClientState.exitProjection();
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
