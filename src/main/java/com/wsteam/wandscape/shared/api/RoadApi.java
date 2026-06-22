package com.wsteam.wandscape.shared.api;

import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.core.road.RoadEdge;
import com.wsteam.wandscape.core.road.RoadNetwork;

/**
 * API for the road system. Provides access to road network state
 * and operations for triggering road construction.
 */
public interface RoadApi {

    /** Get the full road network for a colony. */
    RoadNetwork getNetwork(UUID colonyId);

    /** Get all road edges for a colony. */
    List<RoadEdge> getEdges(UUID colonyId);

    /** Trigger a full MST rebuild and diff against existing network. */
    void requestFullRebuild(UUID colonyId);

    /** Add a single building to the road network incrementally. */
    void requestIncrementalUpdate(UUID colonyId, UUID buildingId);

    /** Minimum number of buildings before roads are built. */
    int getBuildingThreshold();

    /** Get the block ID used for a given road tier. */
    String getRoadBlock(String tier);

    /**
     * Remove a road edge from the network and demolish its blocks in-world.
     * Immediate server-side operation. Used by the road editor tool.
     *
     * @param colonyId colony identifier (null for default)
     * @param edgeId   the edge to remove
     */
    void removeEdge(UUID colonyId, UUID edgeId);
}
