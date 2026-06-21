package com.wsteam.wandscape.core.road;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The road network for a colony — a graph of nodes and edges.
 *
 * <p>Nodes are indexed by UUID (building ID or intersection ID).
 * Edges are indexed by a generated edge UUID.
 */
public class RoadNetwork {

    private final Map<UUID, RoadNode> nodes;
    private final Map<UUID, RoadEdge> edges;

    public RoadNetwork() {
        this.nodes = new LinkedHashMap<>();
        this.edges = new LinkedHashMap<>();
    }

    // ---- Node operations ----

    public void addNode(RoadNode node) {
        nodes.put(node.nodeId(), node);
    }

    public RoadNode getNode(UUID nodeId) {
        return nodes.get(nodeId);
    }

    public Map<UUID, RoadNode> getNodes() {
        return Collections.unmodifiableMap(nodes);
    }

    /** Get a building node by its building UUID. */
    public Optional<RoadNode> getBuildingNode(UUID buildingId) {
        RoadNode node = nodes.get(buildingId);
        return (node != null && node.type() == RoadNode.NodeType.BUILDING)
                ? Optional.of(node) : Optional.empty();
    }

    // ---- Edge operations ----

    public void addEdge(RoadEdge edge) {
        edges.put(edge.getEdgeId(), edge);
    }

    public RoadEdge getEdge(UUID edgeId) {
        return edges.get(edgeId);
    }

    public Map<UUID, RoadEdge> getEdges() {
        return Collections.unmodifiableMap(edges);
    }

    // ---- Network queries ----

    /**
     * Find the node nearest to the given position, by Manhattan distance (XZ only).
     * Returns null if the network has no nodes.
     */
    public RoadNode findNearestNode(XZPoint point) {
        RoadNode nearest = null;
        int bestDist = Integer.MAX_VALUE;
        for (RoadNode node : nodes.values()) {
            int dist = node.xz().manhattanTo(point);
            if (dist < bestDist) {
                bestDist = dist;
                nearest = node;
            }
        }
        return nearest;
    }

    public boolean isEmpty() {
        return nodes.isEmpty() && edges.isEmpty();
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    @Override
    public String toString() {
        return "RoadNetwork[nodes=" + nodes.size() + " edges=" + edges.size() + "]";
    }
}
