package com.wsteam.wandscape.road.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.wsteam.wandscape.road.core.RoadNetwork;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Client-side static state holder for road projection mode.
 *
 * <p>Mirrors {@code ProjectionClientState} pattern: soul-out-of-body flight
 * combined with Cities: Skylines-style road planning via left-click path points.
 *
 * <h3>State machine</h3>
 * <pre>
 *   IDLE      — no active path point, waiting for first click
 *   PLANNING  — start point set, showing preview line to crosshair
 *
 *   Left-click in IDLE     → sets activeStartPos (PLANNING)
 *   Left-click in PLANNING → creates PendingSegment, adds to queue, clears activeStartPos (IDLE)
 *   Backspace in PLANNING  → clears activeStartPos (IDLE)
 *   Backspace in IDLE      → removes last queued segment
 *   Enter                  → triggers publish all queued segments to server
 *   Escape                 → exit road projection mode
 * </pre>
 *
 * <p>Thread-safe via volatile fields + synchronized collections.
 */
public final class RoadProjectionClientState {

    private static final String TAG = "RoadProjectionClientState";

    /** Whether the player is currently in road projection mode. */
    private static volatile boolean projecting = false;

    /** World position where the player's body is anchored (used for beam rendering). */
    private static volatile BlockPos bodyAnchor = null;

    /** Current crosshair ground target (null = no valid target). */
    private static volatile BlockPos ghostPos = null;

    // ── Road planning state ──

    /** The first point of the current segment being planned (null = IDLE). */
    private static volatile BlockPos activeStartPos = null;

    /** Completed 2-point road segments waiting to be published. */
    private static final List<PendingSegment> pendingSegments =
            Collections.synchronizedList(new ArrayList<>());

    /** Cached road network from server (existing edges + nodes). */
    private static volatile RoadNetwork cachedNetwork = new RoadNetwork();

    /** Current road width (scroll-wheel adjustable, odd: 1,3,5,7,9). */
    private static volatile int currentWidth = 3;

    /** Height offset in blocks applied to path points.
     *  PageUp/+ increases, PageDown/- decreases. Default 0 = on ground.
     *  Applied at click time: the stored start/end positions already include the offset. */
    private static volatile int currentYOffset = 0;

    /** Flag: client sent RoadEditorTogglePacket and is waiting for RoadNetworkSyncPacket
     *  to enter road projection mode (routed via modified handleClient). */
    private static volatile boolean expectingSync = false;

    private RoadProjectionClientState() {}

    // ── Projection mode ──

    public static boolean isProjecting() {
        return projecting;
    }

    /**
     * Enter road projection mode. Called when RoadNetworkSyncPacket arrives
     * and {@link #expectingSync} is true.
     */
    public static void enterProjection(RoadNetwork network) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        bodyAnchor = mc.player.blockPosition();

        // Store network
        cachedNetwork = network;
        currentWidth = 3;
        currentYOffset = 0;
        activeStartPos = null;
        ghostPos = null;
        synchronized (pendingSegments) {
            pendingSegments.clear();
        }

        projecting = true;

        Log.info(TAG, "[RoadProjection] Entered road projection mode. Body at {}, network: {} nodes, {} edges",
                bodyAnchor, network.nodeCount(), network.edgeCount());
    }

    /**
     * Exit road projection mode. Restores player abilities,
     * teleports to body anchor, clears state.
     */
    public static void exitProjection() {
        projecting = false;

        // Clear state
        bodyAnchor = null;
        activeStartPos = null;
        ghostPos = null;
        currentWidth = 3;
        currentYOffset = 0;
        synchronized (pendingSegments) {
            pendingSegments.clear();
        }
        cachedNetwork = new RoadNetwork();
        expectingSync = false;

        Log.info(TAG, "[RoadProjection] Exited road projection mode");
    }

    // ── Body anchor ──

    public static BlockPos getBodyAnchor() {
        return bodyAnchor;
    }

    // ── Ghost position ──

    public static BlockPos getGhostPos() {
        return ghostPos;
    }

    public static void setGhostPos(BlockPos pos) {
        ghostPos = pos;
    }

    // ── Path planning ──

    /** The first point of the current segment (null = IDLE, waiting for first click). */
    public static BlockPos getActiveStartPos() {
        return activeStartPos;
    }

    /** Set the first point, entering PLANNING state. */
    public static void setActiveStartPos(BlockPos pos) {
        activeStartPos = pos;
        Log.info(TAG, "[RoadProjection] Start point set at ({}, {}, {})",
                pos.getX(), pos.getY(), pos.getZ());
    }

    /** Clear the active start point, returning to IDLE state. */
    public static void clearActiveStart() {
        activeStartPos = null;
    }

    /** True if we're in PLANNING state (have a start point, waiting for end point). */
    public static boolean isPlanning() {
        return activeStartPos != null;
    }

    // ── Pending segments (publish queue) ──

    /**
     * Add a completed 2-point road segment to the publish queue.
     * Called when second left-click lands (PLANNING → IDLE).
     */
    public static void addPendingSegment(BlockPos start, BlockPos end, int width) {
        synchronized (pendingSegments) {
            pendingSegments.add(new PendingSegment(start, end, width));
        }
        Log.info(TAG, "[RoadProjection] Segment queued: ({},{},{}) → ({},{},{}) width={}, total={}",
                start.getX(), start.getY(), start.getZ(),
                end.getX(), end.getY(), end.getZ(),
                width, pendingSegments.size());
    }

    /** Remove and return the last queued segment (Backspace in IDLE). Returns null if queue empty. */
    public static PendingSegment removeLastSegment() {
        synchronized (pendingSegments) {
            if (pendingSegments.isEmpty()) return null;
            PendingSegment removed = pendingSegments.remove(pendingSegments.size() - 1);
            Log.info(TAG, "[RoadProjection] Removed last segment: ({},{},{}) → ({},{},{}), remaining={}",
                    removed.start.getX(), removed.start.getY(), removed.start.getZ(),
                    removed.end.getX(), removed.end.getY(), removed.end.getZ(),
                    pendingSegments.size());
            return removed;
        }
    }

    /** Immutable snapshot of current pending segments. */
    public static List<PendingSegment> getPendingSegments() {
        synchronized (pendingSegments) {
            return List.copyOf(pendingSegments);
        }
    }

    /** Number of segments in the publish queue. */
    public static int pendingSegmentCount() {
        synchronized (pendingSegments) {
            return pendingSegments.size();
        }
    }

    /** Clear all pending segments. */
    public static void clearPendingSegments() {
        synchronized (pendingSegments) {
            pendingSegments.clear();
        }
    }

    // ── Road width ──

    public static int getCurrentWidth() { return currentWidth; }

    /** Adjust width by ±2 (keep odd: 1,3,5,7,9). */
    public static void adjustWidth(int delta) {
        int w = currentWidth + delta * 2;
        w = Math.max(1, Math.min(9, w));
        if (w % 2 == 0) w += delta > 0 ? 1 : -1;
        w = Math.max(1, Math.min(9, w));
        currentWidth = w;
        Log.info(TAG, "[RoadProjection] Width set to {}", currentWidth);
    }

    // ── Y offset ──

    public static int getCurrentYOffset() {
        return currentYOffset;
    }

    /** Adjust height offset by ±1. Clamped to [-32, 32]. */
    public static void adjustYOffset(int delta) {
        currentYOffset = Math.max(-32, Math.min(32, currentYOffset + delta));
        Log.info(TAG, "[RoadProjection] Y offset set to {}", currentYOffset);
    }

    /**
     * Returns the effective ghost position with Y offset applied.
     * The raw ghostPos is the raycast-hit block; this adjusts it vertically.
     */
    public static BlockPos getEffectiveGhostPos() {
        if (ghostPos == null) return null;
        return ghostPos.offset(0, currentYOffset, 0);
    }

    // ── Network ──

    public static RoadNetwork getCachedNetwork() {
        return cachedNetwork;
    }

    public static void setCachedNetwork(RoadNetwork network) {
        cachedNetwork = network;
    }

    // ── Sync flag ──

    /** Set by V-key handler before sending RoadEditorTogglePacket.
     *  RoadNetworkSyncPacket.handleClient checks this to route to road projection. */
    public static void setExpectingSync(boolean expecting) {
        expectingSync = expecting;
    }

    public static boolean isExpectingSync() {
        return expectingSync;
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Inner types ──
    // ═══════════════════════════════════════════════════════════════

    /**
     * A pending road segment — two ground positions + width.
     * Stored client-side until the player publishes.
     */
    public record PendingSegment(BlockPos start, BlockPos end, int width) {}
}
