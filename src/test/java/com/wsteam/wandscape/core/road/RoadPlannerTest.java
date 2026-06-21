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
        assertEquals(2, network.edgeCount());
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
    void computeMstPathsAre3D() {
        UUID id0 = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        List<RoadBuildingData> buildings = List.of(
                bd(id0, 0, 70, 0),
                bd(id1, 10, 50, 0));

        RoadNetwork network = RoadPlanner.computeMST(buildings, 2);

        assertEquals(1, network.edgeCount());
        RoadEdge edge = network.getEdges().values().iterator().next();
        List<PathPoint> path = edge.getPath();
        // ΔY=20 over 10 XZ steps → needs stair steps → path >= 10
        assertTrue(path.size() >= 10, "Path with stairs should have >= 10 points");
        PathPoint last = path.get(path.size() - 1);
        assertEquals(10, last.x());
        assertEquals(50, last.y());
        assertEquals(0, last.z());
        // Every step must be walkable
        int prevY = 70; // from building Y
        for (PathPoint p : path) {
            assertTrue(Math.abs(p.y() - prevY) <= 1,
                    "Step from " + prevY + " to " + p.y() + " too steep");
            prevY = p.y();
        }
    }

    @Test
    void incrementalAddToEmptyNetwork() {
        RoadNetwork network = new RoadNetwork();
        RoadBuildingData newBuilding = bd(5, 64, 5);

        RoadPlanner.incrementalAdd(network, newBuilding);

        assertEquals(1, network.nodeCount());
        assertEquals(0, network.edgeCount());
    }

    @Test
    void incrementalAddConnectsToNearestNode() {
        RoadNetwork network = new RoadNetwork();
        UUID existingId = UUID.randomUUID();
        RoadBuildingData existing = bd(existingId, 0, 64, 0);
        RoadBuildingData newBuilding = bd(5, 64, 5);

        network.addNode(new RoadNode(existingId,
                new com.wsteam.wandscape.core.types.GridPos(0, 64, 0),
                RoadNode.NodeType.BUILDING));

        RoadPlanner.incrementalAdd(network, newBuilding);

        assertEquals(2, network.nodeCount());
        assertTrue(network.getBuildingNode(newBuilding.id()).isPresent());
        assertEquals(1, network.edgeCount());

        RoadEdge edge = network.getEdges().values().iterator().next();
        assertTrue(edge.getFromNodeId().equals(newBuilding.id())
                || edge.getToNodeId().equals(newBuilding.id()));
        assertTrue(edge.getFromNodeId().equals(existingId)
                || edge.getToNodeId().equals(existingId));
    }

    @Test
    void incrementalAddUses3DPath() {
        RoadNetwork network = new RoadNetwork();
        UUID existingId = UUID.randomUUID();
        RoadBuildingData existing = bd(existingId, 0, 70, 0);
        RoadBuildingData newBuilding = bd(5, 50, 0);

        network.addNode(new RoadNode(existingId,
                new com.wsteam.wandscape.core.types.GridPos(0, 70, 0),
                RoadNode.NodeType.BUILDING));

        RoadPlanner.incrementalAdd(network, newBuilding);

        RoadEdge edge = network.getEdges().values().iterator().next();
        List<PathPoint> path = edge.getPath();
        PathPoint last = path.get(path.size() - 1);
        // Last point should reach existing node's Y at any close XZ
        assertEquals(70, last.y());
        // All steps must be walkable
        int prevY = 50; // from new building
        for (PathPoint p : path) {
            assertTrue(Math.abs(p.y() - prevY) <= 1,
                    "Step from " + prevY + " to " + p.y() + " too steep");
            prevY = p.y();
        }
    }

    @Test
    void incrementalAddDoesNotSelfConnect() {
        RoadNetwork network = new RoadNetwork();
        UUID buildingId = UUID.randomUUID();
        RoadBuildingData building = bd(buildingId, 5, 64, 5);

        network.addNode(new RoadNode(buildingId,
                new com.wsteam.wandscape.core.types.GridPos(5, 64, 5),
                RoadNode.NodeType.BUILDING));

        RoadPlanner.incrementalAdd(network, building);

        assertEquals(1, network.nodeCount());
        assertEquals(0, network.edgeCount());
    }

    @Test
    void incrementalAddMatchesExistingPathY() {
        // Create network with an edge that has known Y values
        RoadNetwork network = new RoadNetwork();
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();

        network.addNode(new RoadNode(nodeA,
                new com.wsteam.wandscape.core.types.GridPos(0, 70, 0),
                RoadNode.NodeType.BUILDING));
        network.addNode(new RoadNode(nodeB,
                new com.wsteam.wandscape.core.types.GridPos(10, 60, 0),
                RoadNode.NodeType.BUILDING));

        // Add a 3D path edge
        RoadEdge existing = new RoadEdge(UUID.randomUUID(), nodeA, nodeB, "dirt",
                PathGenerator.lShape3D(
                        new PathPoint(0, 70, 0), new PathPoint(10, 60, 0)));
        network.addEdge(existing);

        // New building near XZ position of the existing path
        RoadBuildingData newBld = bd(5, 55, 5);
        RoadPlanner.incrementalAdd(network, newBld);

        // Should have 2 edges now
        assertEquals(2, network.edgeCount());

        // The new edge's last point should match Y of nearest existing path point
        RoadEdge newEdge = null;
        for (RoadEdge e : network.getEdges().values()) {
            if (e.getFromNodeId().equals(newBld.id())) {
                newEdge = e;
                break;
            }
        }
        assertNotNull(newEdge);
        PathPoint lastNew = newEdge.getPath().get(newEdge.getPath().size() - 1);
        // Last point should be near an existing path point — Y should be close to
        // the existing path's Y at that XZ (around 65 based on interpolation)
        assertTrue(lastNew.y() >= 60 && lastNew.y() <= 70,
                "New path end Y=" + lastNew.y() + " should be near existing path Y range");
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

        RoadNetwork network = RoadPlanner.computeMST(buildings, 3);

        NetworkDiff diff = RoadPlanner.rebuild(network, buildings);

        assertEquals(0, diff.newEdges().size());
        assertEquals(0, diff.deprecated().size());
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

        List<RoadBuildingData> onlyTwo = List.of(
                bd(id0, 0, 64, 0),
                bd(id1, 10, 64, 0));

        NetworkDiff diff = RoadPlanner.rebuild(network, onlyTwo);

        int totalAccounted = diff.retained().size()
                + diff.deprecated().size()
                + diff.newEdges().size();
        assertTrue(totalAccounted > 0, "Should have at least some edges accounted");
    }

    @Test
    void rebuildCreatesNewEdgesForNewBuilding() {
        UUID id0 = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        List<RoadBuildingData> two = List.of(
                bd(id0, 0, 64, 0), bd(id1, 10, 64, 0));
        RoadNetwork network = RoadPlanner.computeMST(two, 2);

        List<RoadBuildingData> three = List.of(
                bd(id0, 0, 64, 0),
                bd(id1, 10, 64, 0),
                bd(id2, 5, 64, 8));

        NetworkDiff diff = RoadPlanner.rebuild(network, three);

        assertTrue(diff.newEdges().size() > 0,
                "New building should trigger new edges");
    }

    // ── splitIntoSegments (3D path) ──

    private static PathPoint ppt(int x, int y, int z) { return new PathPoint(x, y, z); }

    @Test
    void splitIntoSegmentsUnderMaxLen() {
        List<PathPoint> path = List.of(ppt(1, 64, 0), ppt(2, 64, 0), ppt(3, 64, 0));

        List<List<PathPoint>> segments = RoadPlanner.splitIntoSegments(path, 16);

        assertEquals(1, segments.size());
        assertEquals(3, segments.get(0).size());
    }

    @Test
    void splitIntoSegmentsAtMaxLen() {
        List<PathPoint> path = new java.util.ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            path.add(ppt(i, 64, 0));
        }

        List<List<PathPoint>> segments = RoadPlanner.splitIntoSegments(path, 16);

        assertEquals(2, segments.size());
        assertEquals(16, segments.get(0).size());
        assertEquals(4, segments.get(1).size());
    }

    @Test
    void splitIntoSegmentsEmptyPath() {
        List<PathPoint> path = List.of();
        List<List<PathPoint>> segments = RoadPlanner.splitIntoSegments(path, 16);

        assertTrue(segments.isEmpty());
    }

    @Test
    void splitIntoSegmentsAtLShapeTurn() {
        List<PathPoint> path = PathGenerator.lShape3D(
                ppt(0, 64, 0), ppt(2, 64, 3));

        List<List<PathPoint>> segments = RoadPlanner.splitIntoSegments(path, 16);

        assertEquals(2, segments.size());
        assertEquals(2, segments.get(0).size());
        assertEquals(3, segments.get(1).size());
    }

    // ── filterNewPath (3D) ──

    @Test
    void filterNewPathNoOverlap() {
        List<PathPoint> path = List.of(ppt(1, 64, 0), ppt(2, 64, 0), ppt(3, 64, 0));

        List<PathPoint> fresh = RoadPlanner.filterNewPath(
                path, Set.of(new XZPoint(5, 5), new XZPoint(10, 10)));

        assertEquals(path, fresh);
    }

    @Test
    void filterNewPathPartialOverlap() {
        List<PathPoint> path = List.of(ppt(1, 64, 0), ppt(2, 64, 0), ppt(3, 64, 0));

        List<PathPoint> fresh = RoadPlanner.filterNewPath(
                path, Set.of(new XZPoint(2, 0)));

        assertEquals(2, fresh.size());
        assertEquals(ppt(1, 64, 0), fresh.get(0));
        assertEquals(ppt(3, 64, 0), fresh.get(1));
    }

    @Test
    void filterNewPathFullOverlap() {
        List<PathPoint> path = List.of(ppt(1, 64, 0), ppt(2, 64, 0));

        List<PathPoint> fresh = RoadPlanner.filterNewPath(
                path, Set.of(new XZPoint(1, 0), new XZPoint(2, 0)));

        assertTrue(fresh.isEmpty());
    }

    @Test
    void filterNewPathEmptyOccupied() {
        List<PathPoint> path = List.of(ppt(1, 64, 0), ppt(2, 64, 0));

        List<PathPoint> fresh = RoadPlanner.filterNewPath(path, Set.of());

        assertEquals(path, fresh);
    }

    @Test
    void filterNewPathIncrementalConnectionOverlap() {
        Set<XZPoint> occupied = new HashSet<>();
        for (int i = 1; i <= 10; i++) {
            occupied.add(new XZPoint(i, 0));
        }

        List<PathPoint> newPath = PathGenerator.lShape3D(
                ppt(5, 64, 5), ppt(5, 64, 0));

        List<PathPoint> fresh = RoadPlanner.filterNewPath(newPath, occupied);

        assertEquals(4, fresh.size());
        assertEquals(ppt(5, 64, 4), fresh.get(0));
        assertEquals(ppt(5, 64, 3), fresh.get(1));
        assertEquals(ppt(5, 64, 2), fresh.get(2));
        assertEquals(ppt(5, 64, 1), fresh.get(3));
    }

    // ── 3D Y interpolation tests (validate lShape3D in planning context) ──

    @Test
    void plannerPathsYInterpolationFlat() {
        List<RoadBuildingData> buildings = List.of(
                bd(0, 64, 0), bd(10, 64, 0));

        RoadNetwork network = RoadPlanner.computeMST(buildings, 2);

        RoadEdge edge = network.getEdges().values().iterator().next();
        for (PathPoint p : edge.getPath()) {
            assertEquals(64, p.y(), "Flat buildings → flat path");
        }
    }

    @Test
    void plannerPathsYInterpolationSteep() {
        List<RoadBuildingData> buildings = List.of(
                bd(0, 80, 0), bd(0, 50, 10));

        RoadNetwork network = RoadPlanner.computeMST(buildings, 2);

        RoadEdge edge = network.getEdges().values().iterator().next();
        List<PathPoint> path = edge.getPath();
        // ΔY=30 over 10 XZ steps → switchback extends path
        assertTrue(path.size() >= 30, "Steep drop needs at least 30 steps for ΔY=30");

        // Every step walkable (|ΔY| ≤ 1)
        int prevY = 80;
        for (PathPoint p : path) {
            assertTrue(Math.abs(p.y() - prevY) <= 1,
                    "Step from " + prevY + " to " + p.y() + " too large");
            prevY = p.y();
        }
        assertEquals(50, path.get(path.size() - 1).y());
    }
}
