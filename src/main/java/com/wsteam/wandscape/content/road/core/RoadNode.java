package com.wsteam.wandscape.content.road.core;

import com.wsteam.wandscape.content.task.types.GridPos;

import java.util.UUID;
/**
 * A node in the road network graph.
 * Can be a building anchor, a road intersection, or an orphan
 * (building was removed but roads still connect here).
 */
public record RoadNode(UUID nodeId, GridPos pos, NodeType type) {

    public enum NodeType {
        /** Node where two road edges cross. */
        INTERSECTION,
        /** Node whose associated building no longer exists. */
        ORPHAN,
        /** Node placed by a player for custom road routing. */
        PLAYER
    }

    public XZPoint xz() {
        return new XZPoint(pos.x(), pos.z());
    }
}
