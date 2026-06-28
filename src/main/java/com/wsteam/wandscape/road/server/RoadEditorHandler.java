package com.wsteam.wandscape.road.server;

import java.util.Set;
import java.util.UUID;

import com.wsteam.wandscape.core.road.PathPoint;
import com.wsteam.wandscape.core.road.RoadEdge;
import com.wsteam.wandscape.core.road.RoadNetwork;
import com.wsteam.wandscape.core.road.RoadNode;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Server-side road editor operations.
 *
 * <p>Handles edge removal: clears all road-placed blocks from the world
 * using the per-edge block position record, then removes the edge from
 * the network data.
 *
 * <p>TODO: Block clearing should be dispatched as NPC demolition tasks
 * rather than executed immediately server-side.
 */
public final class RoadEditorHandler {

    private static final String TAG = "RoadEditorHandler";

    private RoadEditorHandler() {}

    /**
     * Remove a road edge from the network and clear its recorded blocks from the world.
     *
     * <p>TODO: Move the block-clearing to NPC task execution — demolition should
     * be an NPC job, not an immediate server-side operation.
     *
     * @param level   the server level
     * @param network the road network (mutated in-place)
     * @param edgeId  the edge to remove
     */
    public static void removeEdge(ServerLevel level, RoadNetwork network, UUID edgeId) {
        RoadEdge edge = network.getEdge(edgeId);
        if (edge == null) {
            Log.warn(TAG, "[RoadEditor] removeEdge: edge {} not found", edgeId);
            return;
        }

        UUID fromNodeId = edge.getFromNodeId();
        UUID toNodeId = edge.getToNodeId();
        Set<PathPoint> placedBlocks = edge.getPlacedBlocks();

        Log.info(TAG, "[RoadEditor] Removing edge {} ({}→{}, {} path points, {} recorded blocks, status={})",
                edgeId.toString().substring(0, 8),
                fromNodeId.toString().substring(0, 8),
                toNodeId.toString().substring(0, 8),
                edge.getPath().size(), placedBlocks.size(), edge.getStatus());

        // ── Phase 1: Clear all recorded blocks ──
        int blocksCleared = 0;
        for (PathPoint p : placedBlocks) {
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            if (!level.getBlockState(pos).isAir()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                blocksCleared++;
            }
        }

        Log.info(TAG, "[RoadEditor] Cleared {} blocks ({} total recorded) for edge {}",
                blocksCleared, placedBlocks.size(), edgeId.toString().substring(0, 8));

        // ── Phase 2: Remove edge from network ──
        network.removeEdge(edgeId);

        // ── Phase 3: Revalidate nodes ──
        revalidateNode(network, fromNodeId);
        revalidateNode(network, toNodeId);

        Log.info(TAG, "[RoadEditor] Edge {} removed. Network: {} nodes, {} edges",
                edgeId.toString().substring(0, 8),
                network.nodeCount(), network.edgeCount());
    }

    /**
     * Revalidate a node after an edge is removed.
     * BUILDING nodes with no remaining edges become ORPHAN.
     * INTERSECTION nodes with no remaining edges are removed entirely.
     */
    private static void revalidateNode(RoadNetwork network, UUID nodeId) {
        int edgeCount = network.getEdgeCountForNode(nodeId);
        RoadNode node = network.getNode(nodeId);
        if (node == null) return;

        if (edgeCount == 0) {
            if (node.type() == RoadNode.NodeType.INTERSECTION
                    || node.type() == RoadNode.NodeType.PLAYER) {
                network.removeNode(nodeId);
                Log.info(TAG, "[RoadEditor] Removed orphan {} node {}",
                        node.type(), nodeId);
            } else if (node.type() == RoadNode.NodeType.BUILDING) {
                network.updateNodeType(nodeId, RoadNode.NodeType.ORPHAN);
                Log.info(TAG, "[RoadEditor] Marked building node {} as ORPHAN (no edges remain)", nodeId);
            }
            // ORPHAN nodes stay — they can be reused by future planning
        }
    }
}
