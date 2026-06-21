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
        // X segment first
        assertEquals(new XZPoint(1, 0), path.get(0));
        assertEquals(new XZPoint(2, 0), path.get(1));
        // Then Z segment from final X position
        assertEquals(new XZPoint(2, 1), path.get(2));
        assertEquals(new XZPoint(2, 2), path.get(3));
        assertEquals(new XZPoint(2, 3), path.get(4));
    }

    @Test
    void negativeBothDirections() {
        List<XZPoint> path = PathGenerator.lShape(
                new XZPoint(5, 5), new XZPoint(2, 1));

        assertEquals(7, path.size());
        // X segment (negative direction)
        assertEquals(new XZPoint(4, 5), path.get(0));
        assertEquals(new XZPoint(3, 5), path.get(1));
        assertEquals(new XZPoint(2, 5), path.get(2));
        // Z segment (negative direction) from final X
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

        int expectedLength = Math.abs(25 - 10) + Math.abs(5 - 20); // 15 + 15 = 30
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
        // from (0,0) to (2,3): path = [(1,0), (2,0), (2,1), (2,2), (2,3)]
        // turn at index 1 (last X-walk point)
        XZPoint from = new XZPoint(0, 0);
        XZPoint to = new XZPoint(2, 3);
        List<XZPoint> path = PathGenerator.lShape(from, to);

        List<Integer> turns = PathGenerator.turnIndices(path);
        assertEquals(1, turns.size());
        assertEquals(1, turns.get(0)); // index of (2,0) — last X point
    }

    @Test
    void turnIndicesForHorizontalOnly() {
        List<XZPoint> path = PathGenerator.lShape(
                new XZPoint(0, 0), new XZPoint(5, 0));
        // No Z movement, so no real turn
        List<Integer> turns = PathGenerator.turnIndices(path);
        assertTrue(turns.isEmpty());
    }
}
