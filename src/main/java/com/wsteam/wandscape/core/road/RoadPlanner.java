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
 *
 * <p>V3.2: paths are 3D ({@link PathPoint}) with Y interpolated
 * for vertical continuity across uneven terrain.
 */
public final class RoadPlanner {

    private RoadPlanner() {}

    // ---- Public API ----

    /**
     * Compute the initial road network via MST with 3D paths.
     * Only triggers when building count >= threshold.
     *
     * @param buildings list of all buildings in the colony
     * @param threshold minimum building count to trigger road generation
     * @param amplitude lateral distance of each switchback zigzag swing
     * @return a new RoadNetwork (empty if threshold not met)
     */
    public static RoadNetwork computeMST(List<RoadBuildingData> buildings,
                                         int threshold, int amplitude) {
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

        // Build point list for MST (2D — topology only)
        List<XZPoint> points = buildings.stream()
                .map(XZPoint::fromBuildData)
                .toList();

        List<MstEdge> mstEdges = MstCalculator.prim(points, XZPoint::manhattanTo);

        // Create RoadEdges with 3D paths
        for (MstEdge me : mstEdges) {
            RoadBuildingData from = buildings.get(me.fromIndex());
            RoadBuildingData to = buildings.get(me.toIndex());
            PathPoint fromPt = pathPoint(from);
            PathPoint toPt = pathPoint(to);

            List<PathPoint> path = PathGenerator.lShape3D(fromPt, toPt, amplitude);
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
     * Add a new building to an existing road network with 3D path.
     * Connects the building to the nearest existing node,
     * matching Y at the connection point for vertical continuity.
     *
     * @param network     the existing road network (may be empty)
     * @param newBuilding the newly built building
     * @param amplitude   lateral distance of each switchback zigzag swing
     * @return the same network instance (mutated), or a new network if previously empty
     */
    public static RoadNetwork incrementalAdd(RoadNetwork network,
                                              RoadBuildingData newBuilding,
                                              int amplitude) {
        PathPoint newPt = pathPoint(newBuilding);

        if (network.nodeCount() == 0) {
            network.addNode(new RoadNode(newBuilding.id(),
                    new GridPos(newBuilding.x(), newBuilding.y(), newBuilding.z()),
                    RoadNode.NodeType.BUILDING));
            return network;
        }

        // Find walkable connection point BEFORE adding self (avoids self-match in fallback)
        PathPoint nearestPt = network.findNearestWalkablePathPoint(newPt);

        // Find which node owns the connection point
        UUID connectNodeId = null;
        for (RoadEdge e : network.getEdges().values()) {
            for (PathPoint pp : e.getPath()) {
                if (pp.xz().equals(nearestPt.xz())) {
                    RoadNode nf = network.getNode(e.getFromNodeId());
                    RoadNode nt = network.getNode(e.getToNodeId());
                    int df = (nf != null) ? nf.xz().manhattanTo(newPt.xz()) : Integer.MAX_VALUE;
                    int dt = (nt != null) ? nt.xz().manhattanTo(newPt.xz()) : Integer.MAX_VALUE;
                    connectNodeId = (df <= dt) ? e.getFromNodeId() : e.getToNodeId();
                    break;
                }
            }
        }
        if (connectNodeId == null) {
            // Fallback: nearest node (no edges exist yet)
            RoadNode nn = network.findNearestNode(newPt.xz());
            if (nn != null && !nn.nodeId().equals(newBuilding.id())) {
                connectNodeId = nn.nodeId();
            } else {
                network.addNode(new RoadNode(newBuilding.id(),
                        new GridPos(newBuilding.x(), newBuilding.y(), newBuilding.z()),
                        RoadNode.NodeType.BUILDING));
                return network;
            }
        }

        // Add the building node
        network.addNode(new RoadNode(newBuilding.id(),
                new GridPos(newBuilding.x(), newBuilding.y(), newBuilding.z()),
                RoadNode.NodeType.BUILDING));

        // Generate 3D path
        List<PathPoint> path = PathGenerator.lShape3D(newPt, nearestPt, amplitude);
        if (path.isEmpty()) return network;

        RoadEdge edge = new RoadEdge(
                UUID.randomUUID(),
                newBuilding.id(), connectNodeId,
                "dirt", path);
        network.addEdge(edge);

        return network;
    }

    /**
     * Rebuild the road network by computing a fresh MST and diffing
     * against the existing network.
     *
     * @param amplitude lateral distance of each switchback zigzag swing
     */
    public static NetworkDiff rebuild(RoadNetwork network,
                                       List<RoadBuildingData> buildings,
                                       int amplitude) {
        RoadNetwork fresh = computeMST(buildings, 0, amplitude);

        Set<BuildingPair> freshPairs = new HashSet<>();
        for (RoadEdge edge : fresh.getEdges().values()) {
            freshPairs.add(BuildingPair.of(edge.getFromNodeId(), edge.getToNodeId()));
        }

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
                BuildingPair pair = BuildingPair.of(edge.getFromNodeId(), edge.getToNodeId());
                if (freshPairs.contains(pair)) {
                    retained.add(edge);
                    freshPairs.remove(pair);
                } else {
                    deprecated.add(edge);
                }
            } else {
                retained.add(edge);
            }
        }

        List<RoadEdge> newEdges = new ArrayList<>();
        for (BuildingPair pair : freshPairs) {
            RoadBuildingData fromBd = findBuilding(buildings, pair.a);
            RoadBuildingData toBd = findBuilding(buildings, pair.b);
            if (fromBd == null || toBd == null) continue;

            PathPoint fromPt = pathPoint(fromBd);
            PathPoint toPt = pathPoint(toBd);
            List<PathPoint> path = PathGenerator.lShape3D(fromPt, toPt, amplitude);
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
     * Split a 3D path into segments, each no longer than {@code maxLen}.
     * Split points are at L-shaped turn positions and maxLen boundaries.
     */
    public static List<List<PathPoint>> splitIntoSegments(List<PathPoint> path, int maxLen) {
        if (path.isEmpty()) return Collections.emptyList();
        if (maxLen <= 0) return List.of(new ArrayList<>(path));

        List<Integer> turns = PathGenerator.turnIndices3D(path);
        Set<Integer> breakpoints = new LinkedHashSet<>(turns);

        for (int i = maxLen; i < path.size(); i += maxLen) {
            breakpoints.add(i - 1);
        }

        List<List<PathPoint>> segments = new ArrayList<>();
        int segStart = 0;
        for (int bp : breakpoints) {
            if (bp >= segStart && bp < path.size() - 1) {
                List<PathPoint> seg = path.subList(segStart, bp + 1);
                if (!seg.isEmpty()) {
                    segments.add(new ArrayList<>(seg));
                }
                segStart = bp + 1;
            }
        }
        if (segStart < path.size()) {
            List<PathPoint> seg = new ArrayList<>(path.subList(segStart, path.size()));
            if (!seg.isEmpty()) {
                segments.add(seg);
            }
        }

        return segments;
    }

    /**
     * Remove points from a new path that are already occupied by existing edges.
     * Filters by 3D position — only skip if the exact (x,y,z) is occupied.
     */
    public static List<PathPoint> filterNewPath(List<PathPoint> path, Set<PathPoint> occupied) {
        if (occupied.isEmpty()) return path;
        List<PathPoint> result = new ArrayList<>();
        for (PathPoint p : path) {
            if (!occupied.contains(p)) {
                result.add(p);
            }
        }
        return result;
    }

    // ---- Helpers ----

    private static PathPoint pathPoint(RoadBuildingData bd) {
        return new PathPoint(bd.x(), bd.y(), bd.z());
    }

    private static RoadBuildingData findBuilding(List<RoadBuildingData> buildings, UUID id) {
        for (RoadBuildingData bd : buildings) {
            if (bd.id().equals(id)) return bd;
        }
        return null;
    }

    private record BuildingPair(UUID a, UUID b) {
        static BuildingPair of(UUID x, UUID y) {
            return x.compareTo(y) <= 0
                    ? new BuildingPair(x, y)
                    : new BuildingPair(y, x);
        }
    }
}
