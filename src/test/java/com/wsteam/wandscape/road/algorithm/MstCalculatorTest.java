package com.wsteam.wandscape.road.algorithm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import com.wsteam.wandscape.road.algorithm.MstCalculator;
import com.wsteam.wandscape.road.algorithm.MstEdge;
import com.wsteam.wandscape.road.core.XZPoint;
import org.junit.jupiter.api.Test;

class MstCalculatorTest {

    @Test
    void twoPointsProducesOneEdge() {
        List<XZPoint> points = List.of(new XZPoint(0, 0), new XZPoint(5, 0));
        List<MstEdge> edges = MstCalculator.prim(points, XZPoint::manhattanTo);

        assertEquals(1, edges.size());
        assertEquals(5, edges.get(0).distance());
    }

    @Test
    void threePointsInLineProducesTwoEdges() {
        // Points at (0,0), (3,0), (10,0)
        List<XZPoint> points = List.of(
                new XZPoint(0, 0), new XZPoint(3, 0), new XZPoint(10, 0));
        List<MstEdge> edges = MstCalculator.prim(points, XZPoint::manhattanTo);

        assertEquals(2, edges.size());
        // Total distance should be 10 (0→3→10)
        int totalDist = edges.stream().mapToInt(MstEdge::distance).sum();
        assertEquals(10, totalDist);
    }

    @Test
    void threePointsTriangleProducesTwoShortestEdges() {
        // Triangle: (0,0), (3,0), (0,4). Distances: 3, 4, 7
        List<XZPoint> points = List.of(
                new XZPoint(0, 0), new XZPoint(3, 0), new XZPoint(0, 4));
        List<MstEdge> edges = MstCalculator.prim(points, XZPoint::manhattanTo);

        assertEquals(2, edges.size());
        int totalDist = edges.stream().mapToInt(MstEdge::distance).sum();
        // MST should pick the two shortest edges: 3 + 4 = 7, not 3 + 7 or 4 + 7
        assertEquals(7, totalDist);
    }

    @Test
    void fourPointsSquareProducesThreeEdges() {
        // Square corners: (0,0), (0,2), (2,0), (2,2)
        List<XZPoint> points = List.of(
                new XZPoint(0, 0), new XZPoint(0, 2),
                new XZPoint(2, 0), new XZPoint(2, 2));
        List<MstEdge> edges = MstCalculator.prim(points, XZPoint::manhattanTo);

        assertEquals(3, edges.size());
        // MST of a square has 3 edges with total distance = 2+2+2 = 6
        int totalDist = edges.stream().mapToInt(MstEdge::distance).sum();
        assertEquals(6, totalDist);
    }

    @Test
    void singlePointProducesEmptyEdgeList() {
        List<XZPoint> points = List.of(new XZPoint(5, 5));
        List<MstEdge> edges = MstCalculator.prim(points, XZPoint::manhattanTo);

        assertTrue(edges.isEmpty());
    }

    @Test
    void emptyListProducesEmptyEdgeList() {
        List<XZPoint> points = List.of();
        List<MstEdge> edges = MstCalculator.prim(points, XZPoint::manhattanTo);

        assertTrue(edges.isEmpty());
    }

    @Test
    void allPointsConnected() {
        // 5 scattered points — MST must connect all with n-1 edges
        List<XZPoint> points = List.of(
                new XZPoint(0, 0), new XZPoint(10, 0),
                new XZPoint(5, 5), new XZPoint(0, 10),
                new XZPoint(10, 10));
        List<MstEdge> edges = MstCalculator.prim(points, XZPoint::manhattanTo);

        assertEquals(4, edges.size()); // n-1 = 4

        // Verify all vertices are referenced
        boolean[] referenced = new boolean[5];
        for (MstEdge e : edges) {
            referenced[e.fromIndex()] = true;
            referenced[e.toIndex()] = true;
        }
        for (int i = 0; i < 5; i++) {
            assertTrue(referenced[i], "vertex " + i + " should be connected");
        }
    }
}
