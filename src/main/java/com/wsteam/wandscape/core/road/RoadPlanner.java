package com.wsteam.wandscape.core.road;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.wsteam.wandscape.core.types.GridPos;

/**
 * Orchestrates road network planning.
 * All methods are stateless pure functions taking data records as input.
 */
public final class RoadPlanner {

    private RoadPlanner() {}

    // ---- Public API ----

    /**
     * Compute the initial road network via MST.
     * Only triggers when building count >= threshold.
     *
     * @param buildings list of all buildings in the colony
     * @param threshold minimum building count to trigger road generation
     * @return a new RoadNetwork (empty if threshold not met)
     */
    public static RoadNetwork computeMST(List<RoadBuildingData> buildings, int threshold) {
        if (buildings.size() < threshold) {
            return new RoadNetwork();
        }

        RoadNetwork network = new RoadNetwork();

        // Create BUILDING nodes
        for (RoadBuildingData bd : buildings) {
            network.addNode(new RoadNode(bd.id(),
                    new GridPos(bd.x(), bd.y(), bd.z()),
                    RoadNode.NodeType.BUILDING));
        }

        // Build point list for MST
        List<XZPoint> points = buildings.stream()
                .map(XZPoint::fromBuildData)
                .toList();

        // Run MST
        List<MstEdge> mstEdges = MstCalculator.prim(points, XZPoint::manhattanTo);

        // Create RoadEdges from MST output
        for (MstEdge me : mstEdges) {
            RoadBuildingData from = buildings.get(me.fromIndex());
            RoadBuildingData to = buildings.get(me.toIndex());
            XZPoint fromXz = XZPoint.fromBuildData(from);
            XZPoint toXz = XZPoint.fromBuildData(to);

            List<XZPoint> path = PathGenerator.lShape(fromXz, toXz);
            if (path.isEmpty()) continue;

            RoadEdge edge = new RoadEdge(
                    UUID.randomUUID(),
                    from.id(), to.id(),
                    "dirt", path);
            network.addEdge(edge);
        }

        return network;
    }

    /**
     * Add a new building to an existing road network.
     * Connects the building to the nearest existing node.
     *
     * @param network     the existing road network (may be empty)
     * @param newBuilding the newly built building
     * @return the same network instance (mutated), or a new network if previously empty
     */
    public static RoadNetwork incrementalAdd(RoadNetwork network,
                                              RoadBuildingData newBuilding) {
        XZPoint newXz = XZPoint.fromBuildData(newBuilding);

        // Find nearest existing node BEFORE adding the new building
        // (otherwise the new building itself is the nearest)
        RoadNode nearest = network.findNearestNode(newXz);

        // Add the building node
        network.addNode(new RoadNode(newBuilding.id(),
                new GridPos(newBuilding.x(), newBuilding.y(), newBuilding.z()),
                RoadNode.NodeType.BUILDING));

        if (nearest == null) {
            // No other nodes to connect to
            return network;
        }

        // Don't connect to yourself (shouldn't happen since we searched before adding)
        if (nearest.nodeId().equals(newBuilding.id())) {
            return network;
        }

        // Generate path
        List<XZPoint> path = PathGenerator.lShape(newXz, nearest.xz());
        if (path.isEmpty()) return network;

        RoadEdge edge = new RoadEdge(
                UUID.randomUUID(),
                newBuilding.id(), nearest.nodeId(),
                "dirt", path);
        network.addEdge(edge);

        return network;
    }

    /**
     * Rebuild the road network by computing a fresh MST and diffing
     * against the existing network. Only edges that directly connect
     * two BUILDING nodes are compared.
     *
     * @param network   the existing road network
     * @param buildings current list of buildings
     * @return diff describing which edges to retain, deprecate, and create
     */
    public static NetworkDiff rebuild(RoadNetwork network,
                                       List<RoadBuildingData> buildings) {
        // Compute what the new MST wants
        RoadNetwork fresh = computeMST(buildings, 0); // force compute (0 means no threshold)

        // Build a set of (buildingId1, buildingId2) pairs from the fresh MST
        Set<BuildingPair> freshPairs = new HashSet<>();
        for (RoadEdge edge : fresh.getEdges().values()) {
            freshPairs.add(BuildingPair.of(edge.getFromNodeId(), edge.getToNodeId()));
        }

        // Classify existing edges
        List<RoadEdge> retained = new ArrayList<>();
        List<RoadEdge> deprecated = new ArrayList<>();
        Set<UUID> buildingIds = new HashSet<>();
        for (RoadBuildingData bd : buildings) {
            buildingIds.add(bd.id());
        }

        for (RoadEdge edge : network.getEdges().values()) {
            boolean fromIsBuilding = buildingIds.contains(edge.getFromNodeId());
            boolean toIsBuilding = buildingIds.contains(edge.getToNodeId());

            if (fromIsBuilding && toIsBuilding) {
                // Both endpoints are current buildings — check if MST still wants this
                BuildingPair pair = BuildingPair.of(edge.getFromNodeId(), edge.getToNodeId());
                if (freshPairs.contains(pair)) {
                    retained.add(edge);
                    freshPairs.remove(pair); // Don't create a duplicate
                } else {
                    deprecated.add(edge);
                }
            } else {
                // Edge involves a non-building node (intersection, orphan)
                // V1: keep it, mark as retained
                retained.add(edge);
            }
        }

        // Remaining freshPairs are edges that need to be created
        List<RoadEdge> newEdges = new ArrayList<>();
        for (BuildingPair pair : freshPairs) {
            RoadBuildingData fromBd = findBuilding(buildings, pair.a);
            RoadBuildingData toBd = findBuilding(buildings, pair.b);
            if (fromBd == null || toBd == null) continue;

            XZPoint fromXz = XZPoint.fromBuildData(fromBd);
            XZPoint toXz = XZPoint.fromBuildData(toBd);
            List<XZPoint> path = PathGenerator.lShape(fromXz, toXz);
            if (path.isEmpty()) continue;

            RoadEdge edge = new RoadEdge(
                    UUID.randomUUID(),
                    pair.a, pair.b,
                    "dirt", path);
            newEdges.add(edge);
        }

        return new NetworkDiff(retained, deprecated, newEdges);
    }

    /**
     * Split a path into segments, each no longer than {@code maxLen}.
     * Split points are also placed at L-shaped turn positions
     * so segments align with direction changes.
     *
     * @param path   the full path to split
     * @param maxLen maximum tiles per segment
     * @return list of sub-paths, each non-empty
     */
    public static List<List<XZPoint>> splitIntoSegments(List<XZPoint> path, int maxLen) {
        if (path.isEmpty()) return Collections.emptyList();
        if (maxLen <= 0) return List.of(new ArrayList<>(path));

        List<Integer> turns = PathGenerator.turnIndices(path);
        Set<Integer> breakpoints = new LinkedHashSet<>(turns);

        // Ensure breaks at every maxLen boundary too
        for (int i = maxLen; i < path.size(); i += maxLen) {
            breakpoints.add(i - 1); // break after this index
        }

        List<List<XZPoint>> segments = new ArrayList<>();
        int segStart = 0;
        for (int bp : breakpoints) {
            if (bp >= segStart && bp < path.size() - 1) {
                List<XZPoint> seg = path.subList(segStart, bp + 1);
                if (!seg.isEmpty()) {
                    segments.add(new ArrayList<>(seg));
                }
                segStart = bp + 1;
            }
        }
        // Final segment
        if (segStart < path.size()) {
            List<XZPoint> seg = new ArrayList<>(path.subList(segStart, path.size()));
            if (!seg.isEmpty()) {
                segments.add(seg);
            }
        }

        return segments;
    }

    /**
     * Remove points from a new path that are already occupied by existing edges.
     * Used by incremental add to avoid submitting duplicate build tasks.
     *
     * @param path     the full path for a new edge
     * @param occupied set of XZ positions already covered by existing edges
     * @return path with occupied points removed (may be empty if fully overlapping)
     */
    public static List<XZPoint> filterNewPath(List<XZPoint> path, Set<XZPoint> occupied) {
        if (occupied.isEmpty()) return path;
        List<XZPoint> result = new ArrayList<>();
        for (XZPoint p : path) {
            if (!occupied.contains(p)) {
                result.add(p);
            }
        }
        return result;
    }

    // ---- Helpers ----

    private static RoadBuildingData findBuilding(List<RoadBuildingData> buildings, UUID id) {
        for (RoadBuildingData bd : buildings) {
            if (bd.id().equals(id)) return bd;
        }
        return null;
    }

    /**
     * An unordered pair of building UUIDs for edge comparison.
     */
    private record BuildingPair(UUID a, UUID b) {
        static BuildingPair of(UUID x, UUID y) {
            // Order consistently so (A,B) and (B,A) are equal
            return x.compareTo(y) <= 0
                    ? new BuildingPair(x, y)
                    : new BuildingPair(y, x);
        }
    }
}
