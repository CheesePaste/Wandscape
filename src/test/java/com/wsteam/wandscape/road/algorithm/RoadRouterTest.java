package com.wsteam.wandscape.road.algorithm;

import com.wsteam.wandscape.road.core.PathPoint;
import com.wsteam.wandscape.road.core.RoadEdge;
import com.wsteam.wandscape.road.core.RoadNetwork;
import com.wsteam.wandscape.road.core.SplineModel;
import com.wsteam.wandscape.road.core.SplineVec3;
import com.wsteam.wandscape.road.core.TransportRoute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RoadRouterTest {

    @Test
    @DisplayName("Empty network should produce direct route")
    void testEmptyNetworkProducesDirectRoute() {
        PathPoint start = new PathPoint(0, 64, 0);
        PathPoint end = new PathPoint(50, 64, 50);

        TransportRoute route = RoadRouter.plan(null, start, end);
        assertNotNull(route);
        assertEquals(1, route.legs().size());
        assertTrue(route.legs().get(0).offRoad());
        assertTrue(route.totalDuration(2, 4) > 0);
    }

    @Test
    @DisplayName("Short distance should produce direct route directly without road search")
    void testShortDistanceProducesDirectRoute() {
        RoadNetwork network = new RoadNetwork();
        PathPoint start = new PathPoint(0, 64, 0);
        PathPoint end = new PathPoint(2, 64, 2);

        TransportRoute route = RoadRouter.plan(network, start, end);
        assertNotNull(route);
        assertEquals(1, route.legs().size());
        assertTrue(route.legs().get(0).offRoad());
    }

    @Test
    @DisplayName("Route along single road edge should cruise on road")
    void testSingleRoadEdgeRoute() {
        RoadNetwork network = new RoadNetwork();

        SplineModel spline = new SplineModel();
        spline.addPoint(new SplineVec3(0, 64, 0));
        spline.addPoint(new SplineVec3(50, 64, 0));
        spline.addPoint(new SplineVec3(100, 64, 0));

        RoadEdge edge = new RoadEdge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "stone", spline);
        edge.setStatus(RoadEdge.EdgeStatus.COMPLETE);
        network.addEdge(edge);

        PathPoint start = new PathPoint(10, 64, 2); // 2 blocks away from road
        PathPoint end = new PathPoint(90, 64, 2);

        TransportRoute route = RoadRouter.plan(network, start, end);
        assertNotNull(route);
        assertFalse(route.isEmpty());

        // Should have on-road leg
        boolean hasOnRoad = route.legs().stream().anyMatch(leg -> !leg.offRoad());
        assertTrue(hasOnRoad, "Route should contain on-road cruising segment");

        // Total duration should be faster than purely off-road direct duration
        int roadDuration = route.totalDuration(2, 4);
        int directDuration = TransportRoute.direct(start, end).totalDuration(2, 4);
        assertTrue(roadDuration <= directDuration, "On-road travel should be faster than direct off-road");
    }

    @Test
    @DisplayName("Route across two connected road edges should navigate junction")
    void testTwoConnectedRoadEdgesRoute() {
        RoadNetwork network = new RoadNetwork();

        // Edge 1: (0, 64, 0) -> (50, 64, 0)
        SplineModel spline1 = new SplineModel();
        spline1.addPoint(new SplineVec3(0, 64, 0));
        spline1.addPoint(new SplineVec3(50, 64, 0));
        RoadEdge edge1 = new RoadEdge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "stone", spline1);
        edge1.setStatus(RoadEdge.EdgeStatus.COMPLETE);
        network.addEdge(edge1);

        // Edge 2: (50, 64, 0) -> (50, 64, 60)
        SplineModel spline2 = new SplineModel();
        spline2.addPoint(new SplineVec3(50, 64, 0));
        spline2.addPoint(new SplineVec3(50, 64, 60));
        RoadEdge edge2 = new RoadEdge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "stone", spline2);
        edge2.setStatus(RoadEdge.EdgeStatus.COMPLETE);
        network.addEdge(edge2);

        PathPoint start = new PathPoint(5, 64, 1);
        PathPoint end = new PathPoint(51, 64, 55);

        TransportRoute route = RoadRouter.plan(network, start, end);
        assertNotNull(route);
        assertFalse(route.isEmpty());

        boolean hasOnRoad = route.legs().stream().anyMatch(leg -> !leg.offRoad());
        assertTrue(hasOnRoad, "Cross-edge route should cruise on road");
    }

    @Test
    @DisplayName("Distant disconnected road should gracefully fall back to direct")
    void testFarAwayRoadFallsBackToDirect() {
        RoadNetwork network = new RoadNetwork();

        // Road is 200 blocks away
        SplineModel spline = new SplineModel();
        spline.addPoint(new SplineVec3(200, 64, 200));
        spline.addPoint(new SplineVec3(300, 64, 200));
        RoadEdge edge = new RoadEdge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "stone", spline);
        edge.setStatus(RoadEdge.EdgeStatus.COMPLETE);
        network.addEdge(edge);

        PathPoint start = new PathPoint(0, 64, 0);
        PathPoint end = new PathPoint(20, 64, 0);

        TransportRoute route = RoadRouter.plan(network, start, end);
        assertNotNull(route);
        assertEquals(1, route.legs().size());
        assertTrue(route.legs().get(0).offRoad(), "Should fall back to direct flight when roads are too far");
    }

    @Test
    @DisplayName("Linear Replace tool road edge should support road cruising")
    void testLinearReplaceToolRoadRoute() {
        RoadNetwork network = new RoadNetwork();

        // Straight linear road from (10, 64, 10) to (10, 64, 90) placed by Replace tool
        SplineModel linearSpline = new SplineModel();
        linearSpline.addPoint(new SplineVec3(10.5, 64.5, 10.5));
        linearSpline.addPoint(new SplineVec3(10.5, 64.5, 90.5));

        RoadEdge edge = new RoadEdge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "dirt", linearSpline);
        edge.setStatus(RoadEdge.EdgeStatus.COMPLETE);
        edge.setWidth(3);
        network.addEdge(edge);

        PathPoint start = new PathPoint(8, 64, 12);
        PathPoint end = new PathPoint(11, 64, 85);

        TransportRoute route = RoadRouter.plan(network, start, end);
        assertNotNull(route);
        assertFalse(route.isEmpty());

        boolean hasOnRoad = route.legs().stream().anyMatch(leg -> !leg.offRoad());
        assertTrue(hasOnRoad, "Linear Replace-mode road should support on-road cruising");
    }

    @Test
    @DisplayName("Hybrid junction (Replace tool straight road + Spline curved road) should navigate seamlessly")
    void testHybridRoadJunctionRoute() {
        RoadNetwork network = new RoadNetwork();

        // Road 1: Straight line from (0, 64, 0) to (50, 64, 0)
        SplineModel linear = new SplineModel();
        linear.addPoint(new SplineVec3(0.5, 64.5, 0.5));
        linear.addPoint(new SplineVec3(50.5, 64.5, 0.5));
        RoadEdge edge1 = new RoadEdge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "dirt", linear);
        edge1.setStatus(RoadEdge.EdgeStatus.COMPLETE);
        network.addEdge(edge1);

        // Road 2: Spline curve starting at (50, 64, 0) to (100, 64, 50)
        SplineModel curved = new SplineModel();
        curved.addPoint(new SplineVec3(50.5, 64.5, 0.5));
        curved.addPoint(new SplineVec3(75.5, 64.5, 20.5));
        curved.addPoint(new SplineVec3(100.5, 64.5, 50.5));
        RoadEdge edge2 = new RoadEdge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "stone", curved);
        edge2.setStatus(RoadEdge.EdgeStatus.COMPLETE);
        network.addEdge(edge2);

        PathPoint start = new PathPoint(2, 64, 1);
        PathPoint end = new PathPoint(98, 64, 48);

        TransportRoute route = RoadRouter.plan(network, start, end);
        assertNotNull(route);
        assertFalse(route.isEmpty());

        long onRoadLegs = route.legs().stream().filter(leg -> !leg.offRoad()).count();
        assertTrue(onRoadLegs >= 1, "Should seamlessly cross from straight road onto curved spline road");
    }

    @Test
    @DisplayName("T-Junction: Side road connecting to middle of main road should navigate seamlessly")
    void testTJunctionRoute() {
        RoadNetwork network = new RoadNetwork();

        // Main road: (0, 64, 0) to (100, 64, 0)
        SplineModel mainSpline = new SplineModel();
        mainSpline.addPoint(new SplineVec3(0.5, 64.5, 0.5));
        mainSpline.addPoint(new SplineVec3(100.5, 64.5, 0.5));
        RoadEdge mainEdge = new RoadEdge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "stone", mainSpline);
        mainEdge.setStatus(RoadEdge.EdgeStatus.COMPLETE);
        network.addEdge(mainEdge);

        // Side road: (50, 64, 0) to (50, 64, 60) — attached at the middle of main road!
        SplineModel sideSpline = new SplineModel();
        sideSpline.addPoint(new SplineVec3(50.5, 64.5, 0.5));
        sideSpline.addPoint(new SplineVec3(50.5, 64.5, 60.5));
        RoadEdge sideEdge = new RoadEdge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "dirt", sideSpline);
        sideEdge.setStatus(RoadEdge.EdgeStatus.COMPLETE);
        network.addEdge(sideEdge);

        PathPoint start = new PathPoint(5, 64, 1);
        PathPoint end = new PathPoint(51, 64, 55);

        TransportRoute route = RoadRouter.plan(network, start, end);
        assertNotNull(route);
        assertFalse(route.isEmpty());

        long onRoadLegs = route.legs().stream().filter(leg -> !leg.offRoad()).count();
        assertTrue(onRoadLegs >= 2, "Should cruise on main road, turn into side road at T-junction, and cruise on side road");

        int roadDuration = route.totalDuration(2, 4);
        int directDuration = TransportRoute.direct(start, end).totalDuration(2, 4);
        assertTrue(roadDuration <= directDuration, "T-junction road route should be faster than direct flight");
    }

    @Test
    @DisplayName("Off-road gap (野路 - road - 野路 - road - 野路) should hop between disconnected roads")
    void testOffRoadHopRoute() {
        RoadNetwork network = new RoadNetwork();

        // Road 1: (0, 64, 0) to (40, 64, 0)
        SplineModel spline1 = new SplineModel();
        spline1.addPoint(new SplineVec3(0.5, 64.5, 0.5));
        spline1.addPoint(new SplineVec3(40.5, 64.5, 0.5));
        RoadEdge edge1 = new RoadEdge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "stone", spline1);
        edge1.setStatus(RoadEdge.EdgeStatus.COMPLETE);
        network.addEdge(edge1);

        // Gap of 20 blocks between (40, 64, 0) and (60, 64, 0)

        // Road 2: (60, 64, 0) to (100, 64, 0)
        SplineModel spline2 = new SplineModel();
        spline2.addPoint(new SplineVec3(60.5, 64.5, 0.5));
        spline2.addPoint(new SplineVec3(100.5, 64.5, 0.5));
        RoadEdge edge2 = new RoadEdge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "stone", spline2);
        edge2.setStatus(RoadEdge.EdgeStatus.COMPLETE);
        network.addEdge(edge2);

        PathPoint start = new PathPoint(2, 64, 1);
        PathPoint end = new PathPoint(98, 64, 1);

        TransportRoute route = RoadRouter.plan(network, start, end);
        assertNotNull(route);
        assertFalse(route.isEmpty());

        long onRoadLegs = route.legs().stream().filter(leg -> !leg.offRoad()).count();
        long offRoadLegs = route.legs().stream().filter(leg -> leg.offRoad()).count();

        assertTrue(onRoadLegs >= 2, "Route should cruise on Road 1 and Road 2");
        assertTrue(offRoadLegs >= 2, "Route should contain off-road hop across the gap and start/end legs");

        int roadDuration = route.totalDuration(2, 4);
        int directDuration = TransportRoute.direct(start, end).totalDuration(2, 4);
        assertTrue(roadDuration <= directDuration, "Road-assisted hopping route should be faster than purely direct flight");
    }

    @Test
    @DisplayName("Performance stress test: 50 road edges with 20 T-junctions executes in under 5ms")
    void testPerformanceWithManyEdges() {
        RoadNetwork network = new RoadNetwork();

        for (int i = 0; i < 20; i++) {
            SplineModel mainSpline = new SplineModel();
            mainSpline.addPoint(new SplineVec3(0, 64, i * 20));
            mainSpline.addPoint(new SplineVec3(200, 64, i * 20));
            RoadEdge e = new RoadEdge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "stone", mainSpline);
            e.setStatus(RoadEdge.EdgeStatus.COMPLETE);
            network.addEdge(e);
        }

        for (int i = 0; i < 30; i++) {
            SplineModel crossSpline = new SplineModel();
            crossSpline.addPoint(new SplineVec3(i * 6, 64, 0));
            crossSpline.addPoint(new SplineVec3(i * 6, 64, 400));
            RoadEdge e = new RoadEdge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "dirt", crossSpline);
            e.setStatus(RoadEdge.EdgeStatus.COMPLETE);
            network.addEdge(e);
        }

        PathPoint start = new PathPoint(2, 64, 2);
        PathPoint end = new PathPoint(180, 64, 380);

        long t0 = System.nanoTime();
        TransportRoute route = RoadRouter.plan(network, start, end);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertNotNull(route);
        assertFalse(route.isEmpty());
        assertTrue(elapsedMs < 15, "Routing over 50 edges grid should take < 15ms (actual: " + elapsedMs + "ms)");
    }
}
