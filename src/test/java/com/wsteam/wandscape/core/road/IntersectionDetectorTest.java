package com.wsteam.wandscape.core.road;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class IntersectionDetectorTest {

    @Test
    void crossingPathsDetectsIntersection() {
        // Path A: (0,0) → (2,0) → (2,2)  = [(1,0),(2,0),(2,1),(2,2)]
        List<XZPoint> pathA = List.of(
                new XZPoint(1, 0), new XZPoint(2, 0),
                new XZPoint(2, 1), new XZPoint(2, 2));
        // Path B: (1,-1) → (1,1)  = [(1,0),(1,1)]
        List<XZPoint> pathB = List.of(new XZPoint(1, 0), new XZPoint(1, 1));

        Set<XZPoint> intersections = IntersectionDetector.detect(pathA, pathB);

        assertEquals(1, intersections.size());
        assertTrue(intersections.contains(new XZPoint(1, 0)));
    }

    @Test
    void parallelPathsNoIntersection() {
        List<XZPoint> pathA = List.of(
                new XZPoint(0, 0), new XZPoint(0, 1), new XZPoint(0, 2));
        List<XZPoint> pathB = List.of(
                new XZPoint(2, 0), new XZPoint(2, 1), new XZPoint(2, 2));

        Set<XZPoint> intersections = IntersectionDetector.detect(pathA, pathB);

        assertTrue(intersections.isEmpty());
    }

    @Test
    void perpendicularCrossingDetectsIntersection() {
        // Horizontal: (0,2)→(4,2)
        List<XZPoint> pathA = List.of(
                new XZPoint(1, 2), new XZPoint(2, 2),
                new XZPoint(3, 2), new XZPoint(4, 2));
        // Vertical: (2,0)→(2,4)
        List<XZPoint> pathB = List.of(
                new XZPoint(2, 1), new XZPoint(2, 2),
                new XZPoint(2, 3), new XZPoint(2, 4));

        Set<XZPoint> intersections = IntersectionDetector.detect(pathA, pathB);

        assertEquals(1, intersections.size());
        assertTrue(intersections.contains(new XZPoint(2, 2)));
    }

    @Test
    void endpointTouchIsDetected() {
        // Path A ends where path B begins
        List<XZPoint> pathA = List.of(
                new XZPoint(0, 0), new XZPoint(1, 0), new XZPoint(2, 0));
        List<XZPoint> pathB = List.of(
                new XZPoint(2, 0), new XZPoint(2, 1), new XZPoint(2, 2));

        Set<XZPoint> intersections = IntersectionDetector.detect(pathA, pathB);

        assertEquals(1, intersections.size());
        assertTrue(intersections.contains(new XZPoint(2, 0)));
    }

    @Test
    void emptyPathsReturnEmpty() {
        List<XZPoint> pathA = List.of(new XZPoint(1, 1));
        List<XZPoint> pathB = List.of();

        assertTrue(IntersectionDetector.detect(pathA, pathB).isEmpty());
        assertTrue(IntersectionDetector.detect(pathB, pathA).isEmpty());
    }

    @Test
    void detectAllFindsIntersectionsAcrossMultipleEdges() {
        RoadEdge edge1 = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(new XZPoint(0, 0), new XZPoint(1, 0),
                        new XZPoint(2, 0), new XZPoint(2, 1)));
        RoadEdge edge2 = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(new XZPoint(1, -1), new XZPoint(1, 0),
                        new XZPoint(1, 1)));
        RoadEdge edge3 = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(new XZPoint(5, 5), new XZPoint(5, 6)));

        Set<XZPoint> intersections = IntersectionDetector.detectAll(
                List.of(edge1, edge2, edge3));

        assertEquals(1, intersections.size());
        assertTrue(intersections.contains(new XZPoint(1, 0)));
    }

    @Test
    void detectAllNoIntersectionsReturnsEmpty() {
        RoadEdge edge1 = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(new XZPoint(0, 0), new XZPoint(1, 0)));
        RoadEdge edge2 = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(new XZPoint(5, 5), new XZPoint(5, 6)));

        Set<XZPoint> intersections = IntersectionDetector.detectAll(
                List.of(edge1, edge2));

        assertTrue(intersections.isEmpty());
    }
}
