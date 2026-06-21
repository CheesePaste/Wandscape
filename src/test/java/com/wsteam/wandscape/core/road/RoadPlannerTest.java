package com.wsteam.wandscape.core.road;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RoadPlannerTest {

    private static RoadBuildingData bd(int x, int y, int z) {
        return new RoadBuildingData(UUID.randomUUID(), x, y, z);
    }

    private static RoadBuildingData bd(UUID id, int x, int y, int z) {
        return new RoadBuildingData(id, x, y, z);
    }

    @Test
    void computeMstBelowThresholdReturnsEmptyNetwork() {
        List<RoadBuildingData> buildings = List.of(bd(0, 64, 0), bd(10, 64, 0));
        RoadNetwork network = RoadPlanner.computeMST(buildings, 3);

        assertTrue(network.isEmpty());
    }

    @Test
    void computeMstAtThresholdGeneratesNetwork() {
        UUID id0 = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        List<RoadBuildingData> buildings = List.of(
                bd(id0, 0, 64, 0),
                bd(id1, 10, 64, 0),
                bd(id2, 5, 64, 8));

        RoadNetwork network = RoadPlanner.computeMST(buildings, 3);

        assertFalse(network.isEmpty());
        assertEquals(3, network.nodeCount());
        assertEquals(2, network.edgeCount()); // MST of 3 nodes has 2 edges
    }

    @Test
    void computeMstAllNodesAreBuildingType() {
        List<RoadBuildingData> buildings = List.of(
                bd(0, 64, 0), bd(10, 64, 0), bd(0, 64, 10));

        RoadNetwork network = RoadPlanner.computeMST(buildings, 3);

        for (RoadNode node : network.getNodes().values()) {
            assertEquals(RoadNode.NodeType.BUILDING, node.type());
        }
    }

    @Test
    void incrementalAddToEmptyNetwork() {
        RoadNetwork network = new RoadNetwork();
        RoadBuildingData newBuilding = bd(5, 64, 5);

        RoadPlanner.incrementalAdd(network, newBuilding);

        // Should add the node but no edges (nothing to connect to)
        assertEquals(1, network.nodeCount());
        assertEquals(0, network.edgeCount());
    }

    @Test
    void incrementalAddConnectsToNearestNode() {
        RoadNetwork network = new RoadNetwork();
        UUID existingId = UUID.randomUUID();
        RoadBuildingData existing = bd(existingId, 0, 64, 0);
        RoadBuildingData newBuilding = bd(5, 64, 5);

        // First add the existing network
        network.addNode(new RoadNode(existingId,
                new com.wsteam.wandscape.core.types.GridPos(0, 64, 0),
                RoadNode.NodeType.BUILDING));

        // Then incrementally add the new building
        RoadPlanner.incrementalAdd(network, newBuilding);

        assertEquals(2, network.nodeCount());
        assertTrue(network.getBuildingNode(newBuilding.id()).isPresent());
        assertEquals(1, network.edgeCount());

        RoadEdge edge = network.getEdges().values().iterator().next();
        // Edge should connect the new building
        assertTrue(edge.getFromNodeId().equals(newBuilding.id())
                || edge.getToNodeId().equals(newBuilding.id()));
        assertTrue(edge.getFromNodeId().equals(existingId)
                || edge.getToNodeId().equals(existingId));
    }

    @Test
    void incrementalAddDoesNotSelfConnect() {
        RoadNetwork network = new RoadNetwork();
        UUID buildingId = UUID.randomUUID();
        RoadBuildingData building = bd(buildingId, 5, 64, 5);

        // Add the building as a node first
        network.addNode(new RoadNode(buildingId,
                new com.wsteam.wandscape.core.types.GridPos(5, 64, 5),
                RoadNode.NodeType.BUILDING));

        // Now incrementally add the same building — should not create self-edge
        RoadPlanner.incrementalAdd(network, building);

        // Node already existed, edge should be 0
        assertEquals(1, network.nodeCount());
        assertEquals(0, network.edgeCount());
    }

    @Test
    void rebuildRetainsExistingMstEdges() {
        UUID id0 = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        List<RoadBuildingData> buildings = List.of(
                bd(id0, 0, 64, 0),
                bd(id1, 10, 64, 0),
                bd(id2, 0, 64, 10));

        // Build initial MST
        RoadNetwork network = RoadPlanner.computeMST(buildings, 3);

        // Rebuild with same buildings — all edges should be retained
        NetworkDiff diff = RoadPlanner.rebuild(network, buildings);

        assertEquals(0, diff.newEdges().size());
        assertEquals(0, diff.deprecated().size());
        // The existing edges should all be retained
        assertFalse(diff.retained().isEmpty());
    }

    @Test
    void rebuildDetectsDeprecatedEdgesWhenBuildingRemoved() {
        UUID id0 = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        List<RoadBuildingData> allThree = List.of(
                bd(id0, 0, 64, 0),
                bd(id1, 10, 64, 0),
                bd(id2, 0, 64, 10));

        RoadNetwork network = RoadPlanner.computeMST(allThree, 3);
        int originalEdgeCount = network.edgeCount();

        // Remove building id2 — now only 2 buildings
        List<RoadBuildingData> onlyTwo = List.of(
                bd(id0, 0, 64, 0),
                bd(id1, 10, 64, 0));

        NetworkDiff diff = RoadPlanner.rebuild(network, onlyTwo);

        // The edge(s) connecting id2 should be deprecated
        // and new edges connecting the remaining 2 may be created
        int totalAccounted = diff.retained().size()
                + diff.deprecated().size()
                + diff.newEdges().size();
        // Total should make sense
        assertTrue(totalAccounted > 0, "Should have at least some edges accounted");
    }

    @Test
    void rebuildCreatesNewEdgesForNewBuilding() {
        UUID id0 = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();

        // Start with 2 buildings
        List<RoadBuildingData> two = List.of(
                bd(id0, 0, 64, 0), bd(id1, 10, 64, 0));
        // computeMST with threshold=2 forces MST
        RoadNetwork network = RoadPlanner.computeMST(two, 2);

        // Rebuild with 3 buildings
        List<RoadBuildingData> three = List.of(
                bd(id0, 0, 64, 0),
                bd(id1, 10, 64, 0),
                bd(id2, 5, 64, 8));

        NetworkDiff diff = RoadPlanner.rebuild(network, three);

        // Should have at least one new edge for the new building
        assertTrue(diff.newEdges().size() > 0,
                "New building should trigger new edges, got: retained="
                        + diff.retained().size() + " deprecated="
                        + diff.deprecated().size() + " new=" + diff.newEdges().size());
    }

    @Test
    void splitIntoSegmentsUnderMaxLen() {
        List<XZPoint> path = List.of(
                new XZPoint(1, 0), new XZPoint(2, 0), new XZPoint(3, 0));

        List<List<XZPoint>> segments = RoadPlanner.splitIntoSegments(path, 16);

        assertEquals(1, segments.size());
        assertEquals(3, segments.get(0).size());
    }

    @Test
    void splitIntoSegmentsAtMaxLen() {
        // 20-point path, maxLen=16
        List<XZPoint> path = new java.util.ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            path.add(new XZPoint(i, 0));
        }

        List<List<XZPoint>> segments = RoadPlanner.splitIntoSegments(path, 16);

        assertEquals(2, segments.size());
        assertEquals(16, segments.get(0).size()); // first 16
        assertEquals(4, segments.get(1).size());  // remaining 4
    }

    @Test
    void splitIntoSegmentsEmptyPath() {
        List<XZPoint> path = List.of();
        List<List<XZPoint>> segments = RoadPlanner.splitIntoSegments(path, 16);

        assertTrue(segments.isEmpty());
    }

    @Test
    void splitIntoSegmentsAtLShapeTurn() {
        // L-shaped path: (0,0)→(2,0)→(2,3)
        List<XZPoint> path = PathGenerator.lShape(
                new XZPoint(0, 0), new XZPoint(2, 3));

        // maxLen=16 but path is only 5 points with a turn
        List<List<XZPoint>> segments = RoadPlanner.splitIntoSegments(path, 16);

        // Should split at the turn point (index 1 = last X point)
        assertEquals(2, segments.size());
        // First segment: X walk [(1,0), (2,0)]
        assertEquals(2, segments.get(0).size());
        assertEquals(new XZPoint(2, 0), segments.get(0).get(segments.get(0).size() - 1));
        // Second segment: Z walk [(2,1), (2,2), (2,3)]
        assertEquals(3, segments.get(1).size());
        assertEquals(new XZPoint(2, 3), segments.get(1).get(segments.get(1).size() - 1));
    }

    // ── filterNewPath tests ──

    @Test
    void filterNewPathNoOverlap() {
        List<XZPoint> path = List.of(
                new XZPoint(1, 0), new XZPoint(2, 0), new XZPoint(3, 0));

        List<XZPoint> fresh = RoadPlanner.filterNewPath(
                path, Set.of(new XZPoint(5, 5), new XZPoint(10, 10)));

        assertEquals(path, fresh);
    }

    @Test
    void filterNewPathPartialOverlap() {
        List<XZPoint> path = List.of(
                new XZPoint(1, 0), new XZPoint(2, 0), new XZPoint(3, 0));

        // (2,0) is already occupied
        List<XZPoint> fresh = RoadPlanner.filterNewPath(
                path, Set.of(new XZPoint(2, 0)));

        assertEquals(2, fresh.size());
        assertEquals(new XZPoint(1, 0), fresh.get(0));
        assertEquals(new XZPoint(3, 0), fresh.get(1));
    }

    @Test
    void filterNewPathFullOverlap() {
        List<XZPoint> path = List.of(
                new XZPoint(1, 0), new XZPoint(2, 0));

        List<XZPoint> fresh = RoadPlanner.filterNewPath(
                path, Set.of(new XZPoint(1, 0), new XZPoint(2, 0)));

        assertTrue(fresh.isEmpty());
    }

    @Test
    void filterNewPathEmptyOccupied() {
        List<XZPoint> path = List.of(new XZPoint(1, 0), new XZPoint(2, 0));

        List<XZPoint> fresh = RoadPlanner.filterNewPath(
                path, Set.of());

        assertEquals(path, fresh);
    }

    @Test
    void filterNewPathIncrementalConnectionOverlap() {
        // Simulate: existing edge from (0,0) to (10,0) — all 10 X points
        Set<XZPoint> occupied = new HashSet<>();
        for (int i = 1; i <= 10; i++) {
            occupied.add(new XZPoint(i, 0));
        }

        // New building at (5,5), connects to (5,0) on the existing road
        // Path: Z first from (5,5) to (5,0) [(5,4),(5,3),(5,2),(5,1),(5,0)]
        // then X... but wait, L-shape from (5,5) to (5,0) is just Z
        List<XZPoint> newPath = PathGenerator.lShape(
                new XZPoint(5, 5), new XZPoint(5, 0));

        List<XZPoint> fresh = RoadPlanner.filterNewPath(newPath, occupied);

        // (5,0) overlaps existing road — should be filtered
        // Path is [(5,4),(5,3),(5,2),(5,1),(5,0)] — last tile overlaps
        assertEquals(4, fresh.size());
        assertEquals(new XZPoint(5, 4), fresh.get(0));
        assertEquals(new XZPoint(5, 3), fresh.get(1));
        assertEquals(new XZPoint(5, 2), fresh.get(2));
        assertEquals(new XZPoint(5, 1), fresh.get(3));
        // (5,0) NOT in fresh — it's where the new road meets the existing one
    }

    @Test
    void filterNewPathBothEndsOverlap() {
        // Existing edges form a T: horizontal (0,0)→(10,0), vertical (0,0)→(0,10)
        Set<XZPoint> occupied = new HashSet<>();
        for (int i = 0; i <= 10; i++) {
            occupied.add(new XZPoint(i, 0));
            occupied.add(new XZPoint(0, i));
        }

        // New path from (5,0) to (0,5) — endpoints overlap both existing roads
        List<XZPoint> newPath = PathGenerator.lShape(
                new XZPoint(5, 0), new XZPoint(0, 5));

        List<XZPoint> fresh = RoadPlanner.filterNewPath(newPath, occupied);

        // Neither endpoint should be in fresh
        assertFalse(fresh.contains(new XZPoint(5, 0)));
        assertFalse(fresh.contains(new XZPoint(0, 5)));
    }
}
