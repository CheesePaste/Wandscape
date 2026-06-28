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

    /**
     * Find the nearest existing path point to a target, considering Y reachability.
     *
     * <p>Filters to only points where the Y difference can be walked with at most
     * 1 block vertical change per XZ step. Falls back to the absolute nearest
     * XZ point if no walkable point exists (the builder will insert stairs).
     *
     * @param target the position to search near (includes Y)
     * @return a reachable PathPoint, or the fallback nearest by XZ
     */
    public PathPoint findNearestWalkablePathPoint(PathPoint target) {
        PathPoint bestPt = null;
        double bestScore = Double.MAX_VALUE;

        for (RoadEdge edge : edges.values()) {
            for (PathPoint pp : edge.getPath()) {
                int xzDist = pp.manhattanXZTo(target);
                int dy = Math.abs(pp.y() - target.y());
                // Score: XZ distance + Y penalty. Walkable gets bonus.
                // Never filter — switchback path handles any slope.
                double score = xzDist + dy * 0.8;
                // But if Y change can fit within XZ steps (walkable), discount
                if (dy <= xzDist) score -= 0.4 * xzDist;
                if (score < bestScore) {
                    bestScore = score;
                    bestPt = pp;
                }
            }
        }

        if (bestPt != null) return bestPt;
        RoadNode node = findNearestNode(target.xz());
        if (node != null) {
            return new PathPoint(node.pos().x(), node.pos().y(), node.pos().z());
        }
        return new PathPoint(target.x(), target.y(), target.z());
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

    // ---- Path planning queries ----

    /**
     * Find the edge connecting two nodes, if one exists.
     * Checks both directions (from→to and to→from).
     *
     * @return the edge ID, or empty if no edge connects them
     */
    public Optional<UUID> findEdgeBetween(UUID nodeA, UUID nodeB) {
        for (RoadEdge e : edges.values()) {
            UUID from = e.getFromNodeId(), to = e.getToNodeId();
            if ((from.equals(nodeA) && to.equals(nodeB))
                    || (from.equals(nodeB) && to.equals(nodeA))) {
                return Optional.of(e.getEdgeId());
            }
        }
        return Optional.empty();
    }

    /**
     * Find a node at a specific XZ position (ignoring Y).
     * Useful for checking whether the player clicked on an existing node.
     *
     * @return the node, or empty if no node exists at this XZ
     */
    public Optional<RoadNode> findNodeAtXZ(int x, int z) {
        for (RoadNode node : nodes.values()) {
            if (node.pos().x() == x && node.pos().z() == z) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    // ---- Mutation operations (editor support) ----

    /** Remove an edge by UUID. Returns false if not found. */
    public boolean removeEdge(UUID edgeId) {
        return edges.remove(edgeId) != null;
    }

    /** Remove a node by UUID. Returns false if not found. */
    public boolean removeNode(UUID nodeId) {
        return nodes.remove(nodeId) != null;
    }

    /** Count edges connected to a node (as either endpoint). */
    public int getEdgeCountForNode(UUID nodeId) {
        int count = 0;
        for (RoadEdge edge : edges.values()) {
            if (edge.getFromNodeId().equals(nodeId) || edge.getToNodeId().equals(nodeId)) {
                count++;
            }
        }
        return count;
    }

    /** Update a node's type (e.g. BUILDING → ORPHAN after all edges removed). */
    public void updateNodeType(UUID nodeId, RoadNode.NodeType newType) {
        RoadNode old = nodes.get(nodeId);
        if (old != null) {
            nodes.put(nodeId, new RoadNode(nodeId, old.pos(), newType));
        }
    }

    @Override
    public String toString() {
        return "RoadNetwork[nodes=" + nodes.size() + " edges=" + edges.size() + "]";
    }
}
