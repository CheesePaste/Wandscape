package com.wsteam.wandscape.road.client;

import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.road.RoadNetwork;

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
    /** The node selected as path-planning start (V1 stub). */
    private static volatile UUID selectedFromNodeId = null;

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
        selectedFromNodeId = null;
    }

    // ── Hover ──

    public static UUID getHoveredEdgeId() {
        return hoveredEdgeId;
    }

    public static void setHoveredEdgeId(UUID edgeId) {
        hoveredEdgeId = edgeId;
    }

    // ── Path planning stub ──

    public static UUID getSelectedFromNodeId() {
        return selectedFromNodeId;
    }

    public static void setSelectedFromNodeId(UUID nodeId) {
        selectedFromNodeId = nodeId;
    }

    public static void clearSelection() {
        selectedFromNodeId = null;
    }
}