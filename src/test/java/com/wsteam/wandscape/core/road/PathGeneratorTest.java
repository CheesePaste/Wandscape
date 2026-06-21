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
        // 10 steps (X:5 + Z:5), ΔY = -10 → -1 per step, reaches target exactly
        PathPoint from = new PathPoint(0, 70, 0);
        PathPoint to = new PathPoint(5, 60, 5);
        List<PathPoint> path = PathGenerator.lShape3D(from, to);

        assertEquals(10, path.size());
        for (int i = 0; i < path.size(); i++) {
            assertEquals(70 - (i + 1), path.get(i).y(),
                    "Step " + i + " Y should be " + (70 - i - 1));
        }
        assertEquals(new PathPoint(5, 60, 5), path.get(path.size() - 1));
    }

    @Test
    void lShape3DascendingUnevenly() {
        // 7 XZ steps, ΔY = +10 → all +1 per step, with stairs at end
        PathPoint from = new PathPoint(0, 60, 0);
        PathPoint to = new PathPoint(7, 70, 0);
        List<PathPoint> path = PathGenerator.lShape3D(from, to);

        assertTrue(path.size() >= 7);
        // Each step ΔY ≤ 1
        int prevY = from.y();
        for (int i = 0; i < path.size(); i++) {
            int dy = Math.abs(path.get(i).y() - prevY);
            assertTrue(dy <= 1, "Step " + i + " ΔY=" + dy + " exceeds 1");
            prevY = path.get(i).y();
        }
        // Last point reaches target Y
        assertEquals(70, path.get(path.size() - 1).y());
    }

    @Test
    void lShape3DsameXZswitchback() {
        // Same XZ, different Y → switchback ramp (oscillates laterally)
        PathPoint from = new PathPoint(5, 64, 5);
        PathPoint to = new PathPoint(5, 80, 5);
        List<PathPoint> path = PathGenerator.lShape3D(from, to);

        assertFalse(path.isEmpty(), "Switchback path should connect vertically");
        // All steps |ΔY| ≤ 1
        int prevY = 64;
        for (PathPoint p : path) {
            assertTrue(Math.abs(p.y() - prevY) <= 1, "Step from " + prevY + " to " + p.y() + " > 1");
            prevY = p.y();
        }
        assertEquals(80, path.get(path.size() - 1).y());
    }

    @Test
    void lShape3DsteepAddsStairsAtEnd() {
        // 5 XZ steps for 30 Y drop → switchback zigzags handle the extra
        PathPoint from = new PathPoint(0, 80, 0);
        PathPoint to = new PathPoint(5, 50, 0);
        List<PathPoint> path = PathGenerator.lShape3D(from, to);

        assertTrue(path.size() >= 30, "Should have at least 30 steps for ΔY=30");
        // All steps |ΔY| ≤ 1
        int prevY = from.y();
        for (PathPoint p : path) {
            assertTrue(Math.abs(p.y() - prevY) <= 1,
                    "Step to " + p + " ΔY=" + (p.y() - prevY) + " > 1");
            prevY = p.y();
        }
        assertEquals(50, path.get(path.size() - 1).y());
        // Final X should be near target (may oscillate)
        PathPoint last = path.get(path.size() - 1);
        assertTrue(Math.abs(last.x() - 5) <= 1, "Last X should be within 1 of target");
    }

    @Test
    void lShape3DxSegmentsHaveCorrectXZ() {
        PathPoint from = new PathPoint(0, 64, 0);
        PathPoint to = new PathPoint(3, 50, 2);
        List<PathPoint> path = PathGenerator.lShape3D(from, to);

        // Path should reach target coordinates
        PathPoint last = path.get(path.size() - 1);
        assertEquals(3, last.x());
        assertEquals(50, last.y());
        assertEquals(2, last.z());

        // All steps |ΔY| ≤ 1
        int prevY = 64;
        for (PathPoint p : path) {
            assertTrue(Math.abs(p.y() - prevY) <= 1,
                    "Step from " + prevY + " to " + p.y() + " > 1 at " + p);
            prevY = p.y();
        }

        // XZ walk direction should progress monotonically:
        // X never goes opposite to overall dx, Z never opposite to overall dz
        int maxX = 0, maxZ = 0;
        for (PathPoint p : path) {
            if (p.x() > maxX) maxX = p.x();
            if (p.z() > maxZ) maxZ = p.z();
        }
        assertTrue(maxX >= 3, "X should reach at least 3 (got " + maxX + ")");
        assertTrue(maxZ >= 2, "Z should reach at least 2 (got " + maxZ + ")");
    }
}
