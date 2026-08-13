package com.wsteam.wandscape.shared.api;

import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.road.core.RoadEdge;
import com.wsteam.wandscape.road.core.RoadNetwork;
/**
 * API for the road system. Provides access to the road network state.
 */
public interface RoadApi {

    /** Get the full road network for a colony. */
    RoadNetwork getNetwork(UUID colonyId);

    /** Get all road edges for a colony. */
    List<RoadEdge> getEdges(UUID colonyId);

    /**
     * Remove a road edge from the network and demolish its blocks in-world.
     * Immediate server-side operation. Used by the road editor tool.
     *
     * @param colonyId colony identifier (null for default)
     * @param edgeId   the edge to remove
     */
    void removeEdge(UUID colonyId, UUID edgeId);
}
