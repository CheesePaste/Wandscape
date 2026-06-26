package com.wsteam.wandscape.road.client;

import java.util.List;
import java.util.UUID;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.core.road.PathPoint;
import com.wsteam.wandscape.core.road.RoadEdge;
import com.wsteam.wandscape.core.road.RoadNetwork;
import com.wsteam.wandscape.road.network.RoadBatchPublishPacket;
import com.wsteam.wandscape.road.network.RoadEdgeRemovePacket;
import com.wsteam.wandscape.road.network.RoadEditorTogglePacket;

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

/**
 * Per-tick input handler for road projection mode.
 *
 * <p>Flight mechanics mirror {@code ProjectionFlightController} exactly.
 * Left-click is repurposed for Cities: Skylines-style path point placement
 * instead of building placement.
 *
 * <h3>Controls</h3>
 * <pre>
 *   WASD / Space / Shift — free flight movement
 *   Ctrl                — sprint (2x speed)
 *   Mouse scroll        — adjust road width (1-9, odd)
 *   Left-click          — add path point (first click = start, second click = end → queues segment)
 *   Right-click         — remove hovered road edge (if any), or exit
 *   Enter               — publish all queued road segments to server
 *   Backspace           — undo last point / last queued segment
 *   Esc                 — exit road projection mode
 *   PageUp/Keypad+      — raise height offset by 1
 *   PageDown/Keypad-    — lower height offset by 1
 * </pre>
 *
 * <h3>Input interception strategy</h3>
 * <p>Mouse clicks and discrete keystrokes are handled via NeoForge
 * {@link InputEvent.MouseButton.Pre} and {@link InputEvent.Key} with
 * {@code event.setCanceled(true)} — this prevents vanilla from ever
 * seeing the event, so no block-breaking or block-placing passthrough.
 *
 * <p>Continuous flight movement (WASD) still uses raw GLFW polling in
 * {@link ClientTickEvent.Post} — held keys are not discrete events.
 *
 * <p>Vanilla-held-key side effects (jump/sneak/sprint/inventory) are
 * drained at the end of each Post tick, same pattern as
 * {@code ProjectionFlightController}.
 */
public final class RoadProjectionController {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Extended reach distance in projection mode (blocks). */
    private static final double PROJECTION_REACH = 64.0;

    private static boolean registered = false;

    private RoadProjectionController() {}

    // ── Registration ──

    public static void register() {
        if (registered) return;
        registered = true;
        var bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        // Discrete input interception — these fire before vanilla and are canceled
        bus.addListener(InputEvent.MouseButton.Pre.class, RoadProjectionController::onMouseButton);
        bus.addListener(InputEvent.Key.class, RoadProjectionController::onKey);
        bus.addListener(InputEvent.MouseScrollingEvent.class, RoadProjectionController::onMouseScroll);
        // Per-tick: continuous flight, raycasting, hover, range check
        bus.addListener(ClientTickEvent.Post.class, RoadProjectionController::onClientTickPost);
        LOGGER.info("[RoadProjection] Flight controller registered");
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Mouse button interception (Pre event — before vanilla) ──
    // ═══════════════════════════════════════════════════════════════

    static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (!RoadProjectionClientState.isProjecting()) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null) return;

        int button = event.getButton();

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            // ── Left-click: path point placement ──
            handleLeftClick(mc);
            event.setCanceled(true);
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            // ── Right-click: remove hovered edge, or exit ──
            handleRightClick(mc);
            event.setCanceled(true);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Keyboard interception ──
    // ═══════════════════════════════════════════════════════════════

    /**
     * Detect discrete key presses.
     *
     * <p>Note: {@link InputEvent.Key} is <b>not</b> {@code ICancellableEvent} —
     * it cannot be cancelled. We use it only to detect key-down events reliably.
     * Vanilla side-effects (inventory open, chat, pause menu) are suppressed by
     * the {@link #drainVanillaInput} call in the Post tick handler.
     */
    static void onKey(InputEvent.Key event) {
        if (!RoadProjectionClientState.isProjecting()) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return; // let UI keys through when screen is open
        if (mc.level == null || mc.player == null) return;

        int key = event.getKey();

        switch (key) {
            // ── Height offset ──
            case GLFW.GLFW_KEY_PAGE_UP, GLFW.GLFW_KEY_KP_ADD, GLFW.GLFW_KEY_EQUAL -> {
                RoadProjectionClientState.adjustYOffset(1);
                mc.player.displayClientMessage(
                        Component.literal("§7[Road] Height offset: §f" +
                                RoadProjectionClientState.getCurrentYOffset()),
                        true);
            }
            case GLFW.GLFW_KEY_PAGE_DOWN, GLFW.GLFW_KEY_KP_SUBTRACT, GLFW.GLFW_KEY_MINUS -> {
                RoadProjectionClientState.adjustYOffset(-1);
                mc.player.displayClientMessage(
                        Component.literal("§7[Road] Height offset: §f" +
                                RoadProjectionClientState.getCurrentYOffset()),
                        true);
            }

            // ── Enter: publish ──
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                handlePublish(mc);
            }

            // ── Backspace: undo ──
            case GLFW.GLFW_KEY_BACKSPACE -> {
                handleUndo(mc);
            }

            // ── Escape: exit ──
            case GLFW.GLFW_KEY_ESCAPE -> {
                doExit();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Per-tick: flight, raycast, hover, range ──
    // ═══════════════════════════════════════════════════════════════

    static void onClientTickPost(ClientTickEvent.Post event) {
        if (!RoadProjectionClientState.isProjecting()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.screen != null) return;

        long window = mc.getWindow().getWindow();

        // 1. Held-key flight movement
        handleFlightMovement(mc, window);

        // 2. Ghost position (ground target under crosshair)
        updateGhostPosition(mc);

        // 3. Edge hover detection
        updateEdgeHover(mc);

        // 4. Drain vanilla key effects (jump/sneak/sprint/inventory) that
        //    would otherwise fire from held keys between Key events
        drainVanillaInput(mc);

        // 5. Range check
        checkRange(mc);
    }

    // ── Flight movement (GLFW polling for continuous held-key input) ──

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
            float speed = RoadProjectionClientState.getFlyingSpeed();
            if (sprintDown) speed *= 2.0f;
            mc.player.setDeltaMovement(moveDir.scale(speed * 20.0));
        } else {
            mc.player.setDeltaMovement(Vec3.ZERO);
        }
    }

    // ── Ghost position ──

    private static void updateGhostPosition(Minecraft mc) {
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 origin = camera.getPosition();
        Vec3 lookVec = new Vec3(camera.getLookVector().x(),
                camera.getLookVector().y(),
                camera.getLookVector().z());

        var clipCtx = new net.minecraft.world.level.ClipContext(
                origin,
                origin.add(lookVec.scale(PROJECTION_REACH)),
                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                mc.player);
        BlockHitResult hit = mc.level.clip(clipCtx);

        if (hit.getType() == HitResult.Type.BLOCK) {
            // Road path points sit ON the ground block (not above it).
            RoadProjectionClientState.setGhostPos(hit.getBlockPos());
        } else {
            RoadProjectionClientState.setGhostPos(null);
        }
    }

    // ── Left-click logic ──

    private static void handleLeftClick(Minecraft mc) {
        BlockPos ghostPos = RoadProjectionClientState.getEffectiveGhostPos();
        if (ghostPos == null) {
            mc.player.displayClientMessage(
                    Component.literal("§7[Road] §cNo valid ground target — aim at terrain"),
                    true);
            return;
        }

        if (!RoadProjectionClientState.isPlanning()) {
            // IDLE → PLANNING: set start point
            RoadProjectionClientState.setActiveStartPos(ghostPos);
            mc.player.displayClientMessage(
                    Component.literal("§7[Road] §aStart point set at §f(" +
                            ghostPos.getX() + ", " + ghostPos.getY() + ", " + ghostPos.getZ() +
                            ") §a— click end point to complete road"),
                    true);
        } else {
            // PLANNING → IDLE: set end point, queue segment
            BlockPos startPos = RoadProjectionClientState.getActiveStartPos();

            if (startPos.equals(ghostPos)) {
                mc.player.displayClientMessage(
                        Component.literal("§7[Road] §cStart and end cannot be the same position"),
                        true);
                return;
            }

            int width = RoadProjectionClientState.getCurrentWidth();
            RoadProjectionClientState.addPendingSegment(startPos, ghostPos, width);
            RoadProjectionClientState.clearActiveStart();

            int total = RoadProjectionClientState.pendingSegmentCount();
            mc.player.displayClientMessage(
                    Component.literal("§7[Road] §6Road segment queued §f#" + total +
                            " §6— §f(" + startPos.getX() + ", " + startPos.getY() + ", " + startPos.getZ() +
                            ") §6→ §f(" + ghostPos.getX() + ", " + ghostPos.getY() + ", " + ghostPos.getZ() +
                            ") §6width=" + width +
                            " §a[§lEnter§r§a] §6to publish, §c[§lBksp§r§c] §6to undo"),
                    true);
        }
    }

    // ── Right-click logic ──

    private static void handleRightClick(Minecraft mc) {
        UUID hoveredEdgeId = RoadEditorClientState.getHoveredEdgeId();
        if (hoveredEdgeId != null) {
            PacketDistributor.sendToServer(new RoadEdgeRemovePacket(hoveredEdgeId));
            RoadEditorClientState.setHoveredEdgeId(null);
            mc.player.displayClientMessage(
                    Component.literal("§7[Road] §cEdge removed — NPCs will demolish"),
                    true);
        } else {
            // No edge hovered — exit
            doExit();
        }
    }

    // ── Publish ──

    private static void handlePublish(Minecraft mc) {
        List<RoadProjectionClientState.PendingSegment> segments =
                RoadProjectionClientState.getPendingSegments();

        if (segments.isEmpty()) {
            mc.player.displayClientMessage(
                    Component.literal("§7[Road] §eNo road segments queued — click to place path points first"),
                    true);
            return;
        }

        PacketDistributor.sendToServer(new RoadBatchPublishPacket(
                segments.stream()
                        .map(s -> new RoadBatchPublishPacket.SegmentData(
                                s.start(), s.end(), s.width()))
                        .toList()));

        int count = segments.size();
        RoadProjectionClientState.clearPendingSegments();

        mc.player.displayClientMessage(
                Component.literal("§7[Road] §aPublished §f" + count +
                        " §aroad segment" + (count != 1 ? "s" : "") +
                        " — NPCs will begin construction"),
                false);

        LOGGER.info("[RoadProjection] Published {} road segments", count);
    }

    // ── Undo ──

    private static void handleUndo(Minecraft mc) {
        if (RoadProjectionClientState.isPlanning()) {
            RoadProjectionClientState.clearActiveStart();
            mc.player.displayClientMessage(
                    Component.literal("§7[Road] §eStart point discarded — click to set new start"),
                    true);
        } else {
            RoadProjectionClientState.PendingSegment removed =
                    RoadProjectionClientState.removeLastSegment();
            if (removed != null) {
                int remaining = RoadProjectionClientState.pendingSegmentCount();
                mc.player.displayClientMessage(
                        Component.literal("§7[Road] §eRemoved last road segment — §f" +
                                remaining + " §eremaining in queue"),
                        true);
            } else {
                mc.player.displayClientMessage(
                        Component.literal("§7[Road] §eNothing to undo"),
                        true);
            }
        }
    }

    // ── Mouse scroll ──

    static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!RoadProjectionClientState.isProjecting()) return;
        event.setCanceled(true);

        int delta = event.getScrollDeltaY() > 0 ? 1 : -1;
        RoadProjectionClientState.adjustWidth(delta);

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("§7[Road] Road width: §f" +
                            RoadProjectionClientState.getCurrentWidth()),
                    true);
        }
    }

    // ── Exit ──

    private static void doExit() {
        int pending = RoadProjectionClientState.pendingSegmentCount();
        if (pending > 0) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("§7[Road] §6⚠ §f" + pending +
                                " §6unpublished road segment" + (pending != 1 ? "s" : "") +
                                " discarded"),
                        false);
            }
        }

        PacketDistributor.sendToServer(new RoadEditorTogglePacket());
        RoadProjectionClientState.exitProjection();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("§7[Road] §eRoad projection ended — returned to body"),
                    true);
        }
    }

    // ── Vanilla input drain ──

    private static void drainVanillaInput(Minecraft mc) {
        while (mc.options.keyAttack.consumeClick()) {}
        while (mc.options.keyUse.consumeClick()) {}
        while (mc.options.keyJump.consumeClick()) {}
        while (mc.options.keyShift.consumeClick()) {}
        while (mc.options.keyInventory.consumeClick()) {}
        while (mc.options.keyDrop.consumeClick()) {}
        while (mc.options.keySprint.consumeClick()) {}
    }

    // ── Edge hover detection ──

    private static final double HOVER_THRESHOLD = 2.8;

    private static void updateEdgeHover(Minecraft mc) {
        RoadNetwork network = RoadProjectionClientState.getCachedNetwork();
        if (network == null) {
            RoadEditorClientState.setHoveredEdgeId(null);
            return;
        }

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        Vec3 lookVec = new Vec3(camera.getLookVector().x(),
                camera.getLookVector().y(),
                camera.getLookVector().z());

        UUID bestEdgeId = null;
        double bestDist = HOVER_THRESHOLD;

        for (RoadEdge edge : network.getEdges().values()) {
            List<PathPoint> path = edge.getPath();
            for (int i = 0; i < path.size() - 1; i++) {
                PathPoint p1 = path.get(i);
                PathPoint p2 = path.get(i + 1);
                Vec3 a = new Vec3(p1.x() + 0.5, p1.y() + 0.5, p1.z() + 0.5);
                Vec3 b = new Vec3(p2.x() + 0.5, p2.y() + 0.5, p2.z() + 0.5);
                double dist = rayToSegmentDist(camPos, lookVec, a, b);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestEdgeId = edge.getEdgeId();
                }
            }
        }

        RoadEditorClientState.setHoveredEdgeId(bestEdgeId);
    }

    private static double rayToSegmentDist(Vec3 rayOrigin, Vec3 rayDir,
                                            Vec3 segA, Vec3 segB) {
        Vec3 segVec = segB.subtract(segA);
        double segLenSq = segVec.lengthSqr();
        if (segLenSq < 1e-9) {
            return rayToPointDist(rayOrigin, rayDir, segA);
        }

        Vec3 w0 = rayOrigin.subtract(segA);
        double a = rayDir.dot(rayDir);
        double b = rayDir.dot(segVec);
        double c = segVec.dot(segVec);
        double d = rayDir.dot(w0);
        double e = segVec.dot(w0);

        double denom = a * c - b * b;
        if (Math.abs(denom) < 1e-9) {
            double tSeg = Math.max(0, Math.min(1, -e / c));
            Vec3 segPt = segA.add(segVec.scale(tSeg));
            return rayToPointDist(rayOrigin, rayDir, segPt);
        }

        double t = Math.max(0, (b * e - c * d) / denom);
        double u = Math.max(0, Math.min(1, (a * e - b * d) / denom));

        Vec3 rayPt = rayOrigin.add(rayDir.scale(t));
        Vec3 segPt = segA.add(segVec.scale(u));
        return rayPt.distanceTo(segPt);
    }

    private static double rayToPointDist(Vec3 rayOrigin, Vec3 rayDir, Vec3 point) {
        Vec3 toPoint = point.subtract(rayOrigin);
        double t = toPoint.dot(rayDir);
        if (t <= 0) return rayOrigin.distanceTo(point);
        return rayOrigin.add(rayDir.scale(t)).distanceTo(point);
    }

    // ── Range check ──

    private static void checkRange(Minecraft mc) {
        BlockPos anchor = RoadProjectionClientState.getBodyAnchor();
        if (anchor == null || mc.player == null) return;

        int maxRange = Config.PROJECTION_MAX_RANGE.get();
        if (maxRange <= 0) return;

        double dist = Math.sqrt(anchor.distSqr(mc.player.blockPosition()));
        if (dist > maxRange) {
            mc.player.displayClientMessage(
                    Component.literal("§7[Road] §eExceeded max projection range (" +
                            maxRange + " blocks) — returning to body"),
                    false);
            doExit();
        }
    }
}
