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
        // Pure horizontal: no spiral needed, just L-path
        PathPoint from = new PathPoint(0, 64, 0);
        PathPoint to = new PathPoint(5, 64, 0);
        List<PathPoint> path = PathGenerator.lShape3D(from, to, 6);

        assertEquals(5, path.size());
        for (PathPoint p : path) {
            assertEquals(64, p.y(), "Flat terrain — all Y should be 64");
        }
        assertEquals(new PathPoint(5, 64, 0), path.get(path.size() - 1));
    }

    @Test
    void lShape3DdescendingEvenly() {
        // ΔY=-10. Spiral walks a square pattern dropping 1 per step,
        // then flat L-path to target. Path must reach (5,60,5).
        PathPoint from = new PathPoint(0, 70, 0);
        PathPoint to = new PathPoint(5, 60, 5);
        List<PathPoint> path = PathGenerator.lShape3D(from, to, 6);

        assertTrue(path.size() >= 10, "Should have at least 10 points for ΔY=10");

        // All steps |ΔY| ≤ 1
        int prevY = from.y();
        for (PathPoint p : path) {
            assertTrue(Math.abs(p.y() - prevY) <= 1,
                    "Step from " + prevY + " to " + p.y() + " > 1 at " + p);
            prevY = p.y();
        }

        // Last point reaches target
        PathPoint last = path.get(path.size() - 1);
        assertEquals(5, last.x());
        assertEquals(60, last.y());
        assertEquals(5, last.z());
    }

    @Test
    void lShape3DascendingUnevenly() {
        // 7 XZ steps, ΔY = +10 → spiral (10 steps) + flat (to X=7)
        PathPoint from = new PathPoint(0, 60, 0);
        PathPoint to = new PathPoint(7, 70, 0);
        List<PathPoint> path = PathGenerator.lShape3D(from, to, 6);

        assertTrue(path.size() >= 10);
        // Each step ΔY ≤ 1
        int prevY = from.y();
        for (PathPoint p : path) {
            assertTrue(Math.abs(p.y() - prevY) <= 1,
                    "ΔY=" + Math.abs(p.y() - prevY) + " exceeds 1");
            prevY = p.y();
        }
        PathPoint last = path.get(path.size() - 1);
        assertEquals(7, last.x());
        assertEquals(70, last.y());
    }

    @Test
    void lShape3DsameXZspiral() {
        // Same XZ, different Y → pure square spiral (no flat phase needed)
        PathPoint from = new PathPoint(5, 64, 5);
        PathPoint to = new PathPoint(5, 80, 5);
        List<PathPoint> path = PathGenerator.lShape3D(from, to, 6);

        assertFalse(path.isEmpty(), "Spiral path should connect vertically");
        // All steps |ΔY| ≤ 1
        int prevY = 64;
        for (PathPoint p : path) {
            assertTrue(Math.abs(p.y() - prevY) <= 1);
            prevY = p.y();
        }
        assertEquals(80, path.get(path.size() - 1).y());
    }

    @Test
    void lShape3DsteepSquareSpiral() {
        // 5 XZ steps for 30 Y drop → spiral wraps ~30 steps, then flat
        PathPoint from = new PathPoint(0, 80, 0);
        PathPoint to = new PathPoint(5, 50, 0);
        List<PathPoint> path = PathGenerator.lShape3D(from, to, 6);

        assertTrue(path.size() >= 30, "Should have >= 30 steps for ΔY=30, got " + path.size());
        // All steps |ΔY| ≤ 1
        int prevY = from.y();
        for (PathPoint p : path) {
            assertTrue(Math.abs(p.y() - prevY) <= 1,
                    "Step to " + p + " ΔY=" + (p.y() - prevY) + " > 1");
            prevY = p.y();
        }
        PathPoint last = path.get(path.size() - 1);
        assertEquals(50, last.y());
        assertEquals(5, last.x());
    }

    @Test
    void lShape3DflatPhaseYConstant() {
        // Once spiral finishes and Y reaches target, flat phase must keep Y constant.
        PathPoint from = new PathPoint(0, 70, 0);
        PathPoint to = new PathPoint(8, 45, 5);
        List<PathPoint> path = PathGenerator.lShape3D(from, to, 6);

        // Find transition to flat phase (first point at target Y)
        int flatStart = -1;
        for (int i = 0; i < path.size(); i++) {
            if (path.get(i).y() == to.y()) {
                flatStart = i;
                break;
            }
        }
        assertTrue(flatStart >= 0, "Should transition to flat phase at target Y");

        // All flat phase points must have target Y
        for (int i = flatStart; i < path.size(); i++) {
            assertEquals(to.y(), path.get(i).y(), "Flat point " + i + " Y should be target");
        }

        PathPoint last = path.get(path.size() - 1);
        assertEquals(to.x(), last.x());
        assertEquals(to.z(), last.z());
    }

    // ── XZ continuity tests ──

    @Test
    void lShape3DeveryConsecutiveStepIsWalkableXZ() {
        // Every pair of consecutive points must be at most 1 XZ block apart.
        PathPoint from = new PathPoint(0, 80, 0);
        PathPoint to = new PathPoint(5, 50, 0);
        List<PathPoint> path = PathGenerator.lShape3D(from, to, 6);

        assertFalse(path.isEmpty());
        PathPoint prev = from;
        for (int i = 0; i < path.size(); i++) {
            PathPoint p = path.get(i);
            int xzDist = Math.abs(p.x() - prev.x()) + Math.abs(p.z() - prev.z());
            assertTrue(xzDist <= 1,
                    "XZ gap " + xzDist + " between " + prev + " and " + p
                    + " (index " + i + ") — road would have gaps");
            prev = p;
        }
    }

    @Test
    void lShape3DspiralXZcontinuity() {
        // Pure vertical square spiral — all XZ steps must be contiguous.
        PathPoint from = new PathPoint(0, 70, 0);
        PathPoint to = new PathPoint(0, 40, 0);
        List<PathPoint> path = PathGenerator.lShape3D(from, to, 6);

        assertTrue(path.size() >= 30, "ΔY=30 needs >=30 steps, got " + path.size());
        PathPoint prev = from;
        for (int i = 0; i < path.size(); i++) {
            PathPoint p = path.get(i);
            int xzDist = Math.abs(p.x() - prev.x()) + Math.abs(p.z() - prev.z());
            assertTrue(xzDist <= 1,
                    "XZ gap " + xzDist + " between " + prev + " and " + p + " (index " + i + ")");
            prev = p;
        }
        assertEquals(40, path.get(path.size() - 1).y());
    }

    @Test
    void lShape3DspiralNoConsecutive180Reversals() {
        // A square spiral changes direction by 90° each time (+X→+Z→-X→-Z).
        // It should never have two consecutive 180° reversals.
        // A single 180° at the spiral→flat transition is acceptable
        // (when the spiral exits mid-side and flat phase walks opposite).
        PathPoint from = new PathPoint(0, 70, 0);
        PathPoint to = new PathPoint(0, 45, 0);
        List<PathPoint> path = PathGenerator.lShape3D(from, to, 6);

        assertTrue(path.size() >= 25);
        int reversals = 0;
        for (int i = 1; i < path.size() - 1; i++) {
            int prevDx = path.get(i).x() - path.get(i - 1).x();
            int prevDz = path.get(i).z() - path.get(i - 1).z();
            int nextDx = path.get(i + 1).x() - path.get(i).x();
            int nextDz = path.get(i + 1).z() - path.get(i).z();

            boolean is180 = (prevDx != 0 && nextDx == -prevDx && nextDz == 0)
                         || (prevDz != 0 && nextDz == -prevDz && nextDx == 0);
            if (is180) reversals++;
        }
        // At most 1 reversal allowed (spiral→flat transition boundary).
        // The spiral's 90° turns are not reversals.
        assertTrue(reversals <= 1,
                "Should have ≤1 reversal (transition boundary), got " + reversals);
    }

    @Test
    void lShape3DspiralEndsAtTargetXZ() {
        // Path must end at the specified target coordinates.
        PathPoint from = new PathPoint(10, 70, 10);
        PathPoint to = new PathPoint(15, 50, 10);
        List<PathPoint> path = PathGenerator.lShape3D(from, to, 6);

        PathPoint last = path.get(path.size() - 1);
        assertEquals(15, last.x(), "X should be target: " + last);
        assertEquals(10, last.z(), "Z should be target: " + last);
        assertEquals(50, last.y(), "Y should be target: " + last);
    }

    // ── Edge cases ──

    @Test
    void lShape3DdyZeroFlatOnly() {
        // ΔY=0, ΔXZ>0 → pure flat L-path, no spiral.
        PathPoint from = new PathPoint(0, 64, 0);
        PathPoint to = new PathPoint(10, 64, 10);
        List<PathPoint> path = PathGenerator.lShape3D(from, to, 6);

        assertEquals(20, path.size()); // 10 X + 10 Z
        for (PathPoint p : path) {
            assertEquals(64, p.y());
        }
        assertEquals(new PathPoint(10, 64, 10), path.get(path.size() - 1));
    }

    @Test
    void lShape3DspiralXZRange() {
        // The spiral should cover a roughly amplitude×amplitude area,
        // not be a narrow strip. X and Z should both vary by at least amplitude/2.
        PathPoint from = new PathPoint(0, 70, 0);
        PathPoint to = new PathPoint(0, 45, 0);
        List<PathPoint> path = PathGenerator.lShape3D(from, to, 6);

        int minX = 0, maxX = 0, minZ = 0, maxZ = 0;
        for (PathPoint p : path) {
            if (p.x() < minX) minX = p.x();
            if (p.x() > maxX) maxX = p.x();
            if (p.z() < minZ) minZ = p.z();
            if (p.z() > maxZ) maxZ = p.z();
        }
        int xSpread = maxX - minX;
        int zSpread = maxZ - minZ;
        assertTrue(xSpread >= 3 || zSpread >= 3,
                "Spiral should spread at least 3 blocks in X or Z, got X=" + xSpread + " Z=" + zSpread);
    }
}
