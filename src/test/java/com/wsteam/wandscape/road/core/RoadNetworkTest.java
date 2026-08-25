package com.wsteam.wandscape.road.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Guards {@link RoadNetwork#findEdgeAt}: the position→edge reverse lookup used by
 * the crosshair to identify which under-construction road to target. Under-construction
 * (non-COMPLETE) edges must be preferred over completed ones on the same cell.
 */
class RoadNetworkTest {

    private static RoadEdge edge(UUID id, RoadEdge.EdgeStatus status, PathPoint... tiles) {
        RoadEdge e = new RoadEdge(id, UUID.randomUUID(), UUID.randomUUID(), "stone", null);
        e.setStatus(status);
        e.addPlacedBlocks(List.of(tiles));
        return e;
    }

    @Test
    void findsUnderConstructionEdgeAtPlacedCell() {
        RoadNetwork n = new RoadNetwork();
        PathPoint cell = new PathPoint(1, 2, 3);
        RoadEdge e = edge(UUID.randomUUID(), RoadEdge.EdgeStatus.BUILDING, cell);
        n.addEdge(e);

        assertEquals(e, n.findEdgeAt(cell));
    }

    @Test
    void prefersUnderConstructionOverCompletedOnSameCell() {
        RoadNetwork n = new RoadNetwork();
        PathPoint cell = new PathPoint(0, 64, 0);
        RoadEdge building = edge(UUID.randomUUID(), RoadEdge.EdgeStatus.BUILDING, cell);
        RoadEdge done = edge(UUID.randomUUID(), RoadEdge.EdgeStatus.COMPLETE, cell);
        n.addEdge(done);
        n.addEdge(building);

        assertEquals(building, n.findEdgeAt(cell));
    }

    @Test
    void returnsCompletedEdgeWhenOnlyOnePresent() {
        RoadNetwork n = new RoadNetwork();
        PathPoint cell = new PathPoint(5, 5, 5);
        RoadEdge done = edge(UUID.randomUUID(), RoadEdge.EdgeStatus.COMPLETE, cell);
        n.addEdge(done);

        assertEquals(done, n.findEdgeAt(cell));
    }

    @Test
    void returnsNullWhenNoEdgeOccupiesCell() {
        RoadNetwork n = new RoadNetwork();
        n.addEdge(edge(UUID.randomUUID(), RoadEdge.EdgeStatus.BUILDING, new PathPoint(1, 1, 1)));

        assertNull(n.findEdgeAt(new PathPoint(9, 9, 9)));
    }
}
