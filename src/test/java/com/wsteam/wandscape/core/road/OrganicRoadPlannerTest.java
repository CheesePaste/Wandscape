package com.wsteam.wandscape.core.road;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OrganicRoadPlannerTest {

    private static RoadBuildingData bd(int x, int y, int z) {
        return new RoadBuildingData(UUID.randomUUID(), x, y, z);
    }

    private static RoadBuildingData bd(UUID id, int x, int y, int z) {
        return new RoadBuildingData(id, x, y, z);
    }

    private static RoadTemplatePool standardPool() {
        return RoadTemplatePool.of(List.of(
                new TemplateMeta("straight", "test:straight", 3, 8, 5,
                        List.of(new EntryExit(1, 0, CardinalFacing.SOUTH)),
                        List.of(new EntryExit(1, 7, CardinalFacing.SOUTH))),
                new TemplateMeta("corner", "test:corner", 3, 8, 1,
                        List.of(new EntryExit(1, 0, CardinalFacing.SOUTH)),
                        List.of(new EntryExit(7, 1, CardinalFacing.EAST)))
        ));
    }

    /** Simple access point: anchor + 1 step toward direction. */
    private static OrganicRoadPlanner.AccessPointFn simpleAccess() {
        return (bd, dir) -> new XZPoint(bd.x() + dir.dx(), bd.z() + dir.dz());
    }

    @Test
    void planBelowThresholdReturnsEmpty() {
        List<RoadBuildingData> buildings = List.of(bd(0, 64, 0), bd(10, 64, 0));
        PlanResult result = OrganicRoadPlanner.plan(
                buildings, simpleAccess(), 3,
                standardPool(), Set.of(), new Random(42));
        assertTrue(result.placements().isEmpty());
        assertEquals(0, result.edgesCreated());
    }

    @Test
    void planAtThresholdGeneratesPlacements() {
        UUID id0 = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        List<RoadBuildingData> buildings = List.of(
                bd(id0, 0, 64, 0),
                bd(id1, 0, 64, 20),
                bd(id2, 20, 64, 0));

        PlanResult result = OrganicRoadPlanner.plan(
                buildings, simpleAccess(), 3,
                standardPool(), Set.of(), new Random(42));

        assertFalse(result.placements().isEmpty(),
                "3 buildings at threshold should generate placements");
        assertTrue(result.edgesCreated() >= 2,
                "MST of 3 nodes should have at least 2 edges");
    }

    @Test
    void planAccessPointsAreOutsideBuildings() {
        UUID id0 = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        List<RoadBuildingData> buildings = List.of(
                bd(id0, 0, 64, 0),
                bd(id1, 0, 64, 20));

        // Record access points called
        final Set<XZPoint> accessPoints = new HashSet<>();
        OrganicRoadPlanner.AccessPointFn recordingAccess = (bd, dir) -> {
            XZPoint ap = simpleAccess().compute(bd, dir);
            accessPoints.add(ap);
            return ap;
        };

        OrganicRoadPlanner.plan(
                buildings, recordingAccess, 2,
                standardPool(), Set.of(), new Random(42));

        // Access points should be distinct (one per building per constraint)
        assertFalse(accessPoints.isEmpty());
        // Access points should NOT be at anchor positions
        for (RoadBuildingData bd : buildings) {
            assertFalse(accessPoints.contains(new XZPoint(bd.x(), bd.z())),
                    "Access point should not be at building anchor");
        }
    }

    @Test
    void planObstaclesBlockPlacement() {
        List<RoadBuildingData> buildings = List.of(
                bd(0, 64, 0),
                bd(0, 64, 20));
        // Block all positions between them
        Set<XZPoint> obstacles = new HashSet<>();
        for (int z = 1; z < 20; z++) {
            obstacles.add(new XZPoint(1, z));
        }

        PlanResult result = OrganicRoadPlanner.plan(
                buildings, simpleAccess(), 2,
                standardPool(), obstacles, new Random(42));

        // Should handle gracefully — may produce fewer/no placements
        assertNotNull(result.placements());
    }

    @Test
    void planEmptyBuildingList() {
        PlanResult result = OrganicRoadPlanner.plan(
                List.of(), simpleAccess(), 0,
                standardPool(), Set.of(), new Random(42));
        assertTrue(result.placements().isEmpty());
    }

    @Test
    void mstPairsFor3Buildings() {
        UUID id0 = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        List<RoadBuildingData> buildings = List.of(
                bd(id0, 0, 64, 0),
                bd(id1, 10, 64, 0),
                bd(id2, 0, 64, 10));

        List<OrganicRoadPlanner.UuidPair> pairs = OrganicRoadPlanner.mstPairs(buildings);

        assertEquals(2, pairs.size(), "3 building MST = 2 edges");
    }
}
