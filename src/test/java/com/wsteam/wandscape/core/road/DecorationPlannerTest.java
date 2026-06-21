package com.wsteam.wandscape.core.road;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DecorationPlannerTest {

    // ---- Helpers ----

    private static PathPoint pt(int x, int y, int z) {
        return new PathPoint(x, y, z);
    }

    private static RoadEdge edge(PathPoint from, PathPoint to) {
        List<PathPoint> path = PathGenerator.lShape3D(from, to);
        return new RoadEdge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "dirt", path);
    }

    // ---- planForEdge ----

    @Test
    void lampsAtEvery8thBlock() {
        // Path length 17 (manhattan=17), i=0 skipped → distances 1..16
        // → lamps at dist 8 and 16
        RoadEdge e = edge(pt(0, 64, 0), pt(17, 64, 0));
        List<DecorationPoint> pts = DecorationPlanner.planForEdge(e, 8, 0, 1);

        assertEquals(2, pts.size());
        assertEquals("lamp", pts.get(0).type());
        assertEquals("lamp", pts.get(1).type());
        // First lamp at step index 8 (path[8] = (9,64,0) since index 0 = (1,64,0))
        PathPoint p8 = e.getPath().get(8);
        assertEquals(p8.x(), pts.get(0).x(), 0);
    }

    @Test
    void benchesOnly() {
        RoadEdge e = edge(pt(0, 64, 0), pt(10, 64, 0));
        List<DecorationPoint> pts = DecorationPlanner.planForEdge(e, 0, 24, 1);

        // 9 steps checked (i=0 skipped), spacing 24 → no matches
        assertTrue(pts.isEmpty());
    }

    @Test
    void lampsAndBenchesNoOverlap() {
        // Path len 17 → distances 1..16 → lamps at 8, 16 (lamp priority over bench)
        RoadEdge e = edge(pt(0, 64, 0), pt(17, 64, 0));
        List<DecorationPoint> pts = DecorationPlanner.planForEdge(e, 8, 8, 1);

        assertEquals(2, pts.size());
        for (DecorationPoint p : pts) {
            assertEquals("lamp", p.type());
        }
    }

    @Test
    void benchWhenLampDisabled() {
        RoadEdge e = edge(pt(0, 64, 0), pt(9, 64, 0));
        List<DecorationPoint> pts = DecorationPlanner.planForEdge(e, 0, 8, 1);

        // 8 points checked (i=0 skipped, 9 total), dist=8 → bench
        assertEquals(1, pts.size());
        assertEquals("bench", pts.get(0).type());
    }

    @Test
    void emptyWhenBothDisabled() {
        RoadEdge e = edge(pt(0, 64, 0), pt(10, 64, 0));
        List<DecorationPoint> pts = DecorationPlanner.planForEdge(e, 0, 0, 1);

        assertTrue(pts.isEmpty());
    }

    @Test
    void sidesAlternate() {
        // Long enough path to see side alternation: path len 21, i=0 skipped → 20 checked
        // lamp spacing 4 → lamps at dist 4, 8, 12, 16, 20 → 5 lamps
        RoadEdge e = edge(pt(0, 64, 0), pt(21, 64, 0));
        List<DecorationPoint> pts = DecorationPlanner.planForEdge(e, 4, 0, 1);

        assertTrue(pts.size() >= 3, "Need at least 3 lamps to check alternation");
        // Neighbouring lamps should be on opposite sides (Z alternates ±2)
        int side1 = pts.get(0).z();
        int side2 = pts.get(1).z();
        assertNotEquals(side1, side2, "Neighbouring lamps should be on opposite sides");
    }

    @Test
    void decorationFacesRoad() {
        RoadEdge e = edge(pt(0, 64, 0), pt(9, 64, 0));
        List<DecorationPoint> pts = DecorationPlanner.planForEdge(e, 8, 0, 1);

        assertEquals(1, pts.size());
        DecorationPoint dp = pts.get(0);
        assertFalse(dp.facing().isBlank());
    }

    @Test
    void offsetMatchesHalfWidth() {
        RoadEdge e = edge(pt(0, 64, 0), pt(9, 64, 0));
        List<DecorationPoint> pts = DecorationPlanner.planForEdge(e, 8, 0, 1);

        // Road along +X → perp ±Z. halfWidth=1 → offset=2 from center.
        DecorationPoint dp = pts.get(0);
        assertEquals(2, Math.abs(dp.z()), "Should be offset 2 from road center");
    }

    @Test
    void shortPathNoDecorations() {
        RoadEdge e = edge(pt(0, 64, 0), pt(1, 64, 0));
        List<DecorationPoint> pts = DecorationPlanner.planForEdge(e, 8, 24, 1);

        // path.size() = 1 → planForEdge returns empty (< 2 check)
        assertTrue(pts.isEmpty());
    }

    // ---- facingFromDelta ----

    @Test
    void facingEast() {
        assertEquals("east", DecorationPlanner.facingFromDelta(1, 0));
    }

    @Test
    void facingWest() {
        assertEquals("west", DecorationPlanner.facingFromDelta(-1, 0));
    }

    @Test
    void facingSouth() {
        assertEquals("south", DecorationPlanner.facingFromDelta(0, 1));
    }

    @Test
    void facingNorth() {
        assertEquals("north", DecorationPlanner.facingFromDelta(0, -1));
    }

    @Test
    void facingDiagonalDominant() {
        assertEquals("east", DecorationPlanner.facingFromDelta(3, 1));
        assertEquals("south", DecorationPlanner.facingFromDelta(1, 3));
    }
}
