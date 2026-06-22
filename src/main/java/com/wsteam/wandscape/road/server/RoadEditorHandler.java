package com.wsteam.wandscape.road.server;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.road.PathPoint;
import com.wsteam.wandscape.core.road.RoadEdge;
import com.wsteam.wandscape.core.road.RoadNetwork;
import com.wsteam.wandscape.core.road.RoadNode;
import com.wsteam.wandscape.engine.road.RoadConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Server-side road editor operations.
 *
 * <p>Handles demolition: removes an edge from the road network
 * and clears its physical blocks from the world. The demolition
 * footprint mirrors what {@code RoadBuilder.buildTiles()} would have
 * created — surface + headroom clearance + fill, expanded to road width.
 */
public final class RoadEditorHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private RoadEditorHandler() {}

    /**
     * Remove a road edge from the network and clear its blocks from the world.
     *
     * @param level   the server level
     * @param network the road network (mutated in-place)
     * @param edgeId  the edge to remove
     */
    public static void removeEdge(ServerLevel level, RoadNetwork network, UUID edgeId) {
        RoadEdge edge = network.getEdge(edgeId);
        if (edge == null) {
            LOGGER.warn("[RoadEditor] removeEdge: edge {} not found", edgeId);
            return;
        }

        UUID fromNodeId = edge.getFromNodeId();
        UUID toNodeId = edge.getToNodeId();
        List<PathPoint> path = edge.getPath();

        LOGGER.info("[RoadEditor] Removing edge {} ({}→{}, {} path points, status={})",
                edgeId.toString().substring(0, 8),
                fromNodeId.toString().substring(0, 8),
                toNodeId.toString().substring(0, 8),
                path.size(), edge.getStatus());

        // ── Phase 1: Clear physical blocks ──
        RoadConfig config = RoadConfig.getInstance();
        int halfWidth = config.getDefaultWidth() / 2;
        int blocksCleared = 0;

        int prevPerpDx = 0, prevPerpDz = 1;
        int n = path.size();

        for (int i = 0; i < n; i++) {
            PathPoint p = path.get(i);

            // Compute perpendicular direction (same logic as RoadBuilder)
            int perpDx = 0, perpDz = 0;
            if (n == 1) {
                perpDz = 1;
            } else {
                boolean moveX = false, moveZ = false;
                if (i > 0) {
                    PathPoint prev = path.get(i - 1);
                    if (prev.x() != p.x()) moveX = true;
                    if (prev.z() != p.z()) moveZ = true;
                }
                if (i < n - 1) {
                    PathPoint next = path.get(i + 1);
                    if (next.x() != p.x()) moveX = true;
                    if (next.z() != p.z()) moveZ = true;
                }
                if (moveX && !moveZ) {
                    perpDz = 1;
                } else if (moveZ && !moveX) {
                    perpDx = 1;
                } else {
                    perpDx = prevPerpDx;
                    perpDz = prevPerpDz;
                }
            }
            prevPerpDx = perpDx;
            prevPerpDz = perpDz;

            for (int w = -halfWidth; w <= halfWidth; w++) {
                int tx = p.x() + perpDx * w;
                int tz = p.z() + perpDz * w;
                int roadY = p.y();

                // ── Surface block (roadY) ──
                BlockPos surfacePos = new BlockPos(tx, roadY, tz);
                blocksCleared += clearIfRoadBlock(level, surfacePos);

                // ── Headroom clearance (roadY+1 to roadY+3) ──
                for (int hy = roadY + 1; hy <= roadY + 3; hy++) {
                    BlockPos headPos = new BlockPos(tx, hy, tz);
                    BlockState headState = level.getBlockState(headPos);
                    if (headState.isAir() || headState.is(Blocks.BEDROCK)) continue;
                    level.setBlock(headPos, Blocks.AIR.defaultBlockState(), 3);
                    blocksCleared++;
                }

                // ── Fill blocks below (roadY-1 to roadY-6) ──
                // Only clear non-natural fill (dirt placed by road, non-terrain)
                for (int fy = roadY - 1; fy >= roadY - 6; fy--) {
                    BlockPos fillPos = new BlockPos(tx, fy, tz);
                    BlockState fillState = level.getBlockState(fillPos);
                    if (fillState.isAir() || fillState.is(Blocks.BEDROCK)) continue;
                    if (isTerrainBlock(fillState)) break; // hit natural terrain, stop clearing below
                    level.setBlock(fillPos, Blocks.AIR.defaultBlockState(), 3);
                    blocksCleared++;
                }
            }
        }

        LOGGER.info("[RoadEditor] Cleared {} blocks for edge {}", blocksCleared,
                edgeId.toString().substring(0, 8));

        // ── Phase 2: Remove edge from network ──
        network.removeEdge(edgeId);

        // ── Phase 3: Revalidate nodes ──
        revalidateNode(network, fromNodeId);
        revalidateNode(network, toNodeId);

        LOGGER.info("[RoadEditor] Edge {} removed. Network: {} nodes, {} edges",
                edgeId.toString().substring(0, 8),
                network.nodeCount(), network.edgeCount());
    }

    /**
     * Clear a block if it looks like a road surface block (from palette, planks, or dirt_path).
     * Does NOT clear natural terrain blocks.
     *
     * @return 1 if a block was cleared, 0 otherwise
     */
    private static int clearIfRoadBlock(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.is(Blocks.BEDROCK)) return 0;

        // Don't clear natural terrain
        if (isTerrainBlock(state)) return 0;

        // Clear any non-natural, non-terrain block at road surface level
        // (includes palette blocks, planks, dirt_path, dirt fill, etc.)
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        return 1;
    }

    /**
     * Check if a block state is natural terrain (should not be removed).
     */
    private static boolean isTerrainBlock(BlockState state) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.SAND)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.TUFF)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.DIORITE)
                || state.is(Blocks.GRANITE)
                || state.is(Blocks.WATER)
                || state.is(Blocks.LAVA)
                || state.is(Blocks.SNOW)
                || state.is(Blocks.ICE)
                || state.is(Blocks.PACKED_ICE)
                || state.is(Blocks.MOSS_BLOCK)
                || state.is(Blocks.MOSSY_COBBLESTONE)
                || state.is(Blocks.MUD)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT);
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
            if (node.type() == RoadNode.NodeType.INTERSECTION) {
                network.removeNode(nodeId);
                LOGGER.info("[RoadEditor] Removed orphan intersection node {}", nodeId);
            } else if (node.type() == RoadNode.NodeType.BUILDING) {
                network.updateNodeType(nodeId, RoadNode.NodeType.ORPHAN);
                LOGGER.info("[RoadEditor] Marked building node {} as ORPHAN (no edges remain)", nodeId);
            }
            // ORPHAN nodes stay — they can be reused by future planning
        }
    }
}
