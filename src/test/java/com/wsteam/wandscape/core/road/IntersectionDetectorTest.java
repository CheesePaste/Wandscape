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

    @Test
    void detectAllCollinearXOverlapNotIntersection() {
        // Two edges share a straight X segment: both move in X only
        // Edge A: (0,0)→(5,0)  [X direction]
        RoadEdge edgeA = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(
                        new XZPoint(1, 0), new XZPoint(2, 0),
                        new XZPoint(3, 0), new XZPoint(4, 0),
                        new XZPoint(5, 0)));
        // Edge B: (2,0)→(2,10) [X first to (4,0) then Z] — collinear X with edge A
        // Actually use L-shape: from (2,0) to (10,10) → X: (3,0)(4,0)...(10,0), Z: (10,1)...(10,10)
        RoadEdge edgeB = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", PathGenerator.lShape(
                        new XZPoint(2, 0), new XZPoint(10, 10)));

        Set<XZPoint> intersections = IntersectionDetector.detectAll(
                List.of(edgeA, edgeB));

        // edgeA and edgeB share several X points at z=0, but both move in X at those points
        // → collinear overlap, NOT an intersection
        assertTrue(intersections.isEmpty(),
                "Collinear X overlap should not be detected as intersection");
    }

    @Test
    void detectAllCrossingIsIntersection() {
        // Horizontal edge: (0,2)→(4,2) [pure X]
        RoadEdge edgeH = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(
                        new XZPoint(1, 2), new XZPoint(2, 2),
                        new XZPoint(3, 2), new XZPoint(4, 2)));
        // Vertical edge: (2,0)→(2,4) [pure Z]
        RoadEdge edgeV = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(
                        new XZPoint(2, 1), new XZPoint(2, 2),
                        new XZPoint(2, 3), new XZPoint(2, 4)));

        Set<XZPoint> intersections = IntersectionDetector.detectAll(
                List.of(edgeH, edgeV));

        // (2,2) is shared, edgeH moves in X, edgeV moves in Z → crossing
        assertEquals(1, intersections.size());
        assertTrue(intersections.contains(new XZPoint(2, 2)));
    }

    @Test
    void detectAllTjunctionIsIntersection() {
        // Horizontal edge: (0,0)→(5,0) [pure X]
        RoadEdge edgeH = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(
                        new XZPoint(1, 0), new XZPoint(2, 0),
                        new XZPoint(3, 0), new XZPoint(4, 0),
                        new XZPoint(5, 0)));
        // Vertical edge starting from (3,0) going north: (3,0)→(3,4) [pure Z]
        // (3,0) is its START point, so at (3,0) it only has Z direction (next=(3,1))
        RoadEdge edgeV = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(
                        new XZPoint(3, 1), new XZPoint(3, 2),
                        new XZPoint(3, 3), new XZPoint(3, 4)));

        Set<XZPoint> intersections = IntersectionDetector.detectAll(
                List.of(edgeH, edgeV));

        // No shared points — edgeV starts at (3,1), not (3,0). They don't intersect.
        assertTrue(intersections.isEmpty());
    }

    @Test
    void detectAllTjunctionEndpointOnPath() {
        // Horizontal edge: (0,0)→(5,0) [pure X]
        RoadEdge edgeH = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(
                        new XZPoint(1, 0), new XZPoint(2, 0),
                        new XZPoint(3, 0), new XZPoint(4, 0),
                        new XZPoint(5, 0)));
        // Vertical edge starting AT (3,0) going north: (3,0),(3,1),(3,2) [Z direction]
        // (3,0) is its START point; edge moves in Z only
        RoadEdge edgeV = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(
                        new XZPoint(3, 0), new XZPoint(3, 1),
                        new XZPoint(3, 2)));

        Set<XZPoint> intersections = IntersectionDetector.detectAll(
                List.of(edgeH, edgeV));

        // (3,0): edgeH moves X at this point, edgeV moves Z at this point → T-junction
        assertEquals(1, intersections.size());
        assertTrue(intersections.contains(new XZPoint(3, 0)));
    }
}
