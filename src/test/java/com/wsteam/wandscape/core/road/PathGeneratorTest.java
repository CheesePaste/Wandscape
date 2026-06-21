package com.wsteam.wandscape.core.road;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class PathGeneratorTest {

    @Test
    void horizontalPathPositiveX() {
        List<XZPoint> path = PathGenerator.lShape(
                new XZPoint(0, 0), new XZPoint(3, 0));

        assertEquals(3, path.size());
        assertEquals(new XZPoint(1, 0), path.get(0));
        assertEquals(new XZPoint(2, 0), path.get(1));
        assertEquals(new XZPoint(3, 0), path.get(2));
    }

    @Test
    void horizontalPathNegativeX() {
        List<XZPoint> path = PathGenerator.lShape(
                new XZPoint(3, 0), new XZPoint(0, 0));

        assertEquals(3, path.size());
        assertEquals(new XZPoint(2, 0), path.get(0));
        assertEquals(new XZPoint(1, 0), path.get(1));
        assertEquals(new XZPoint(0, 0), path.get(2));
    }

    @Test
    void verticalPathPositiveZ() {
        List<XZPoint> path = PathGenerator.lShape(
                new XZPoint(5, 0), new XZPoint(5, 3));

        assertEquals(3, path.size());
        assertEquals(new XZPoint(5, 1), path.get(0));
        assertEquals(new XZPoint(5, 2), path.get(1));
        assertEquals(new XZPoint(5, 3), path.get(2));
    }

    @Test
    void diagonalPathXFirstThenZ() {
        List<XZPoint> path = PathGenerator.lShape(
                new XZPoint(0, 0), new XZPoint(2, 3));

        assertEquals(5, path.size());
        assertEquals(new XZPoint(1, 0), path.get(0));
        assertEquals(new XZPoint(2, 0), path.get(1));
        assertEquals(new XZPoint(2, 1), path.get(2));
        assertEquals(new XZPoint(2, 2), path.get(3));
        assertEquals(new XZPoint(2, 3), path.get(4));
    }

    @Test
    void negativeBothDirections() {
        List<XZPoint> path = PathGenerator.lShape(
                new XZPoint(5, 5), new XZPoint(2, 1));

        assertEquals(7, path.size());
        assertEquals(new XZPoint(4, 5), path.get(0));
        assertEquals(new XZPoint(3, 5), path.get(1));
        assertEquals(new XZPoint(2, 5), path.get(2));
        assertEquals(new XZPoint(2, 4), path.get(3));
        assertEquals(new XZPoint(2, 3), path.get(4));
        assertEquals(new XZPoint(2, 2), path.get(5));
        assertEquals(new XZPoint(2, 1), path.get(6));
    }

    @Test
    void samePointReturnsEmpty() {
        List<XZPoint> path = PathGenerator.lShape(
                new XZPoint(3, 3), new XZPoint(3, 3));

        assertTrue(path.isEmpty());
    }

    @Test
    void totalPathLengthEqualsManhattanDistance() {
        XZPoint from = new XZPoint(10, 20);
        XZPoint to = new XZPoint(25, 5);
        List<XZPoint> path = PathGenerator.lShape(from, to);

        int expectedLength = Math.abs(25 - 10) + Math.abs(5 - 20);
        assertEquals(expectedLength, path.size());
    }

    @Test
    void startExcludedEndIncluded() {
        XZPoint from = new XZPoint(0, 0);
        XZPoint to = new XZPoint(1, 0);
        List<XZPoint> path = PathGenerator.lShape(from, to);

        assertEquals(1, path.size());
        assertEquals(to, path.get(0));
        assertNotEquals(from, path.get(0));
    }

    @Test
    void turnIndicesForDiagonalPath() {
        XZPoint from = new XZPoint(0, 0);
        XZPoint to = new XZPoint(2, 3);
        List<XZPoint> path = PathGenerator.lShape(from, to);

        List<Integer> turns = PathGenerator.turnIndices(path);
        assertEquals(1, turns.size());
        assertEquals(1, turns.get(0));
    }

    @Test
    void turnIndicesForHorizontalOnly() {
        List<XZPoint> path = PathGenerator.lShape(
                new XZPoint(0, 0), new XZPoint(5, 0));
        List<Integer> turns = PathGenerator.turnIndices(path);
        assertTrue(turns.isEmpty());
    }

    // ── 3D path tests ──

    @Test
    void lShape3DflatSameY() {
        PathPoint from = new PathPoint(0, 64, 0);
        PathPoint to = new PathPoint(5, 64, 0);
        List<PathPoint> path = PathGenerator.lShape3D(from, to);

        assertEquals(5, path.size());
        for (PathPoint p : path) {
            assertEquals(64, p.y(), "Flat terrain — all Y should be 64");
        }
        assertEquals(new PathPoint(5, 64, 0), path.get(path.size() - 1));
    }

    @Test
    void lShape3DdescendingEvenly() {
        // 10 steps (X:5 + Z:5), ΔY = -10 → -1 per step
        PathPoint from = new PathPoint(0, 70, 0);
        PathPoint to = new PathPoint(5, 60, 5);
        List<PathPoint> path = PathGenerator.lShape3D(from, to);

        assertEquals(10, path.size());
        // Each step drops exactly 1
        for (int i = 0; i < path.size(); i++) {
            assertEquals(70 - (i + 1), path.get(i).y(),
                    "Step " + i + " Y should be " + (70 - i - 1));
        }
    }

    @Test
    void lShape3DascendingUnevenly() {
        // 7 steps (X:7, Z:0), ΔY = +10 → some +1, some +2
        PathPoint from = new PathPoint(0, 60, 0);
        PathPoint to = new PathPoint(7, 70, 0);
        List<PathPoint> path = PathGenerator.lShape3D(from, to);

        assertEquals(7, path.size());
        // First point Y = from.y + first step
        assertEquals(70, path.get(6).y(), "Last point should reach target Y=70");
        // Each step is at most 2 (10/7 rounds up occasionally)
        for (int i = 1; i < path.size(); i++) {
            int dy = Math.abs(path.get(i).y() - path.get(i - 1).y());
            assertTrue(dy <= 2, "Step " + i + " ΔY=" + dy + " exceeds 2");
        }
    }

    @Test
    void lShape3DsameXZempty() {
        PathPoint from = new PathPoint(5, 64, 5);
        PathPoint to = new PathPoint(5, 80, 5);
        List<PathPoint> path = PathGenerator.lShape3D(from, to);

        // Same XZ → 0 steps in XZ → empty even though Y differs
        // (vertical-only path is not supported by L-shape; handled at build level)
        assertTrue(path.isEmpty());
    }

    @Test
    void lShape3DxSegmentsHaveCorrectXZ() {
        PathPoint from = new PathPoint(0, 64, 0);
        PathPoint to = new PathPoint(3, 50, 2);
        List<PathPoint> path = PathGenerator.lShape3D(from, to);

        assertEquals(5, path.size());
        // First 3 points move in X: (1,*,0), (2,*,0), (3,*,0)
        for (int i = 0; i < 3; i++) {
            assertEquals(i + 1, path.get(i).x());
            assertEquals(0, path.get(i).z());
        }
        // Last 2 points move in Z: (3,*,1), (3,*,2)
        for (int i = 3; i < 5; i++) {
            assertEquals(3, path.get(i).x());
            assertEquals(i - 2, path.get(i).z());
        }
        // Final Y should be to.y
        assertEquals(50, path.get(4).y());
    }
}
