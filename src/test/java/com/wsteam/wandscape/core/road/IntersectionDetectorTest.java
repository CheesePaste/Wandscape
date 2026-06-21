package com.wsteam.wandscape.core.road;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class IntersectionDetectorTest {

    private static PathPoint pp(int x, int z) { return new PathPoint(x, 64, z); }

    @Test
    void crossingPathsDetectsIntersection() {
        List<XZPoint> pathA = List.of(
                new XZPoint(1, 0), new XZPoint(2, 0),
                new XZPoint(2, 1), new XZPoint(2, 2));
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
        List<XZPoint> pathA = List.of(
                new XZPoint(1, 2), new XZPoint(2, 2),
                new XZPoint(3, 2), new XZPoint(4, 2));
        List<XZPoint> pathB = List.of(
                new XZPoint(2, 1), new XZPoint(2, 2),
                new XZPoint(2, 3), new XZPoint(2, 4));

        Set<XZPoint> intersections = IntersectionDetector.detect(pathA, pathB);
        assertEquals(1, intersections.size());
        assertTrue(intersections.contains(new XZPoint(2, 2)));
    }

    @Test
    void endpointTouchIsDetected() {
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
                "dirt", List.of(pp(0, 0), pp(1, 0), pp(2, 0), pp(2, 1)));
        RoadEdge edge2 = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(pp(1, -1), pp(1, 0), pp(1, 1)));
        RoadEdge edge3 = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(pp(5, 5), pp(5, 6)));

        Set<XZPoint> intersections = IntersectionDetector.detectAll(
                List.of(edge1, edge2, edge3));

        assertEquals(1, intersections.size());
        assertTrue(intersections.contains(new XZPoint(1, 0)));
    }

    @Test
    void detectAllNoIntersectionsReturnsEmpty() {
        RoadEdge edge1 = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(pp(0, 0), pp(1, 0)));
        RoadEdge edge2 = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(pp(5, 5), pp(5, 6)));

        Set<XZPoint> intersections = IntersectionDetector.detectAll(
                List.of(edge1, edge2));
        assertTrue(intersections.isEmpty());
    }

    @Test
    void detectAllCollinearXOverlapNotIntersection() {
        RoadEdge edgeA = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(pp(1, 0), pp(2, 0), pp(3, 0), pp(4, 0), pp(5, 0)));
        RoadEdge edgeB = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", PathGenerator.lShape3D(
                        new PathPoint(2, 64, 0), new PathPoint(10, 64, 10)));

        Set<XZPoint> intersections = IntersectionDetector.detectAll(
                List.of(edgeA, edgeB));
        assertTrue(intersections.isEmpty(),
                "Collinear X overlap should not be detected as intersection");
    }

    @Test
    void detectAllCrossingIsIntersection() {
        RoadEdge edgeH = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(pp(1, 2), pp(2, 2), pp(3, 2), pp(4, 2)));
        RoadEdge edgeV = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(pp(2, 1), pp(2, 2), pp(2, 3), pp(2, 4)));

        Set<XZPoint> intersections = IntersectionDetector.detectAll(
                List.of(edgeH, edgeV));

        assertEquals(1, intersections.size());
        assertTrue(intersections.contains(new XZPoint(2, 2)));
    }

    @Test
    void detectAllTjunctionIsIntersection() {
        RoadEdge edgeH = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(pp(1, 0), pp(2, 0), pp(3, 0), pp(4, 0), pp(5, 0)));
        RoadEdge edgeV = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(pp(3, 1), pp(3, 2), pp(3, 3), pp(3, 4)));

        Set<XZPoint> intersections = IntersectionDetector.detectAll(
                List.of(edgeH, edgeV));
        assertTrue(intersections.isEmpty());
    }

    @Test
    void detectAllTjunctionEndpointOnPath() {
        RoadEdge edgeH = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(pp(1, 0), pp(2, 0), pp(3, 0), pp(4, 0), pp(5, 0)));
        RoadEdge edgeV = new RoadEdge(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "dirt", List.of(pp(3, 0), pp(3, 1), pp(3, 2)));

        Set<XZPoint> intersections = IntersectionDetector.detectAll(
                List.of(edgeH, edgeV));

        assertEquals(1, intersections.size());
        assertTrue(intersections.contains(new XZPoint(3, 0)));
    }
}
