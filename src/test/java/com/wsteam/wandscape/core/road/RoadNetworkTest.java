package com.wsteam.wandscape.core.road;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wsteam.wandscape.core.types.GridPos;

class RoadNetworkTest {

    @Test
    void findNearestNodeReturnsCorrectNode() {
        RoadNetwork network = new RoadNetwork();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        network.addNode(new RoadNode(id1, new GridPos(0, 64, 0),
                RoadNode.NodeType.BUILDING));
        network.addNode(new RoadNode(id2, new GridPos(10, 64, 10),
                RoadNode.NodeType.BUILDING));

        // Point closer to id1
        RoadNode nearest = network.findNearestNode(new XZPoint(2, 2));
        assertNotNull(nearest);
        assertEquals(id1, nearest.nodeId());

        // Point closer to id2
        nearest = network.findNearestNode(new XZPoint(9, 9));
        assertEquals(id2, nearest.nodeId());
    }

    @Test
    void findNearestNodeWithMultipleNodes() {
        RoadNetwork network = new RoadNetwork();
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        UUID idC = UUID.randomUUID();

        network.addNode(new RoadNode(idA, new GridPos(0, 64, 0),
                RoadNode.NodeType.BUILDING));
        network.addNode(new RoadNode(idB, new GridPos(5, 64, 0),
                RoadNode.NodeType.BUILDING));
        network.addNode(new RoadNode(idC, new GridPos(0, 64, 10),
                RoadNode.NodeType.BUILDING));

        // Point at (0,5) — distance to A=5, B=10, C=5 → tie between A and C
        // Both are equally close; implementation picks first encountered
        RoadNode nearest = network.findNearestNode(new XZPoint(0, 5));
        assertNotNull(nearest);
        assertTrue(nearest.nodeId().equals(idA) || nearest.nodeId().equals(idC));
    }

    @Test
    void getBuildingNodeReturnsCorrectNode() {
        RoadNetwork network = new RoadNetwork();
        UUID id = UUID.randomUUID();

        network.addNode(new RoadNode(id, new GridPos(10, 64, 10),
                RoadNode.NodeType.BUILDING));

        assertTrue(network.getBuildingNode(id).isPresent());
        assertFalse(network.getBuildingNode(UUID.randomUUID()).isPresent());
    }

    @Test
    void addEdgeThenRetrieve() {
        RoadNetwork network = new RoadNetwork();
        UUID edgeId = UUID.randomUUID();
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();

        RoadEdge edge = new RoadEdge(edgeId, fromId, toId,
                "dirt", List.of(new XZPoint(1, 0), new XZPoint(2, 0)));

        network.addEdge(edge);
        assertEquals(1, network.edgeCount());
        assertNotNull(network.getEdge(edgeId));
        assertEquals("dirt", network.getEdge(edgeId).getTier());
    }

    @Test
    void emptyNetwork() {
        RoadNetwork network = new RoadNetwork();
        assertTrue(network.isEmpty());
        assertEquals(0, network.nodeCount());
        assertEquals(0, network.edgeCount());
        assertNull(network.findNearestNode(new XZPoint(0, 0)));
    }

    @Test
    void networkWithNodesAndEdges() {
        RoadNetwork network = new RoadNetwork();
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();

        network.addNode(new RoadNode(nodeA, new GridPos(0, 64, 0),
                RoadNode.NodeType.BUILDING));
        network.addNode(new RoadNode(nodeB, new GridPos(5, 64, 0),
                RoadNode.NodeType.BUILDING));

        RoadEdge edge = new RoadEdge(UUID.randomUUID(), nodeA, nodeB,
                "dirt", PathGenerator.lShape(new XZPoint(0, 0), new XZPoint(5, 0)));
        network.addEdge(edge);

        assertFalse(network.isEmpty());
        assertEquals(2, network.nodeCount());
        assertEquals(1, network.edgeCount());
    }
}
