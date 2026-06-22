package com.wsteam.wandscape.road.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.road.RoadNetwork;

import net.minecraft.core.BlockPos;

/**
 * Client-side state holder for the road editor.
 * Accessible from both the renderer and the command/packet handlers.
 */
public final class RoadEditorClientState {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile boolean editMode = false;
    private static volatile RoadNetwork cachedNetwork = new RoadNetwork();

    /** The edge currently under the player's crosshair (null if none). */
    private static volatile UUID hoveredEdgeId = null;

    /** The node selected as the path-planning starting point. */
    private static volatile UUID startNodeId = null;

    /** Intermediate waypoints for custom route shaping. */
    private static final List<BlockPos> waypoints =
            Collections.synchronizedList(new ArrayList<>());

    /** The node selected as the path-planning destination (pending confirmation). */
    private static volatile UUID endNodeId = null;

    /** If set, the player is being asked to confirm replacing an existing edge. */
    private static volatile UUID pendingReplaceEdgeId = null;

    private RoadEditorClientState() {}

    // ── Edit mode ──

    public static boolean isEditing() {
        return editMode;
    }

    public static void setEditMode(boolean editing) {
        LOGGER.info("[RoadEditor] setEditMode: {} -> {}", editMode, editing);
        editMode = editing;
        if (!editing) {
            clearSnapshot();
        }
    }

    // ── Network snapshot ──

    public static RoadNetwork getCachedNetwork() {
        return cachedNetwork;
    }

    public static void setNetworkSnapshot(RoadNetwork network) {
        LOGGER.info("[RoadEditor] setNetworkSnapshot: nodes={} edges={}",
                network.nodeCount(), network.edgeCount());
        cachedNetwork = network;
    }

    public static void clearSnapshot() {
        LOGGER.info("[RoadEditor] clearSnapshot");
        cachedNetwork = new RoadNetwork();
        hoveredEdgeId = null;
        clearSelection();
    }

    // ── Hover ──

    public static UUID getHoveredEdgeId() {
        return hoveredEdgeId;
    }

    public static void setHoveredEdgeId(UUID edgeId) {
        hoveredEdgeId = edgeId;
    }

    // ── Path planning: start node ──

    public static UUID getStartNodeId() {
        return startNodeId;
    }

    public static void setStartNodeId(UUID nodeId) {
        startNodeId = nodeId;
    }

    // ── Path planning: waypoints ──

    /** Immutable snapshot of current waypoints. */
    public static List<BlockPos> getWaypoints() {
        synchronized (waypoints) {
            return List.copyOf(waypoints);
        }
    }

    public static void addWaypoint(BlockPos pos) {
        synchronized (waypoints) {
            waypoints.add(pos);
        }
        LOGGER.info("[RoadEditor] waypoint added: {} (total: {})", pos, waypoints.size());
    }

    public static void removeLastWaypoint() {
        synchronized (waypoints) {
            if (!waypoints.isEmpty()) {
                BlockPos removed = waypoints.remove(waypoints.size() - 1);
                LOGGER.info("[RoadEditor] waypoint removed: {} (remaining: {})", removed, waypoints.size());
            }
        }
    }

    public static int waypointCount() {
        synchronized (waypoints) {
            return waypoints.size();
        }
    }

    // ── Path planning: replace confirmation ──

    public static UUID getPendingReplaceEdgeId() {
        return pendingReplaceEdgeId;
    }

    public static void setPendingReplaceEdgeId(UUID edgeId) {
        pendingReplaceEdgeId = edgeId;
    }

    // ── Path planning: end node ──

    public static UUID getEndNodeId() {
        return endNodeId;
    }

    public static void setEndNodeId(UUID nodeId) {
        endNodeId = nodeId;
    }

    // ── Clear state ──

    public static void clearSelection() {
        startNodeId = null;
        endNodeId = null;
        pendingReplaceEdgeId = null;
        synchronized (waypoints) {
            waypoints.clear();
        }
    }
}
