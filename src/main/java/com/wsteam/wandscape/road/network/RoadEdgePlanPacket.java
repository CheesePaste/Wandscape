package com.wsteam.wandscape.road.network;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.road.algorithm.PathGenerator;
import com.wsteam.wandscape.road.core.PathPoint;
import com.wsteam.wandscape.road.core.RoadEdge;
import com.wsteam.wandscape.road.core.RoadNetwork;
import com.wsteam.wandscape.road.core.RoadNode;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.road.engine.RoadConfig;
import com.wsteam.wandscape.road.engine.RoadEventListener;
import com.wsteam.wandscape.road.engine.RoadSavedData;
import com.wsteam.wandscape.road.server.RoadEditorHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import static com.wsteam.wandscape.Wandscape.MODID;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Client→Server packet for player-initiated road path planning.
 *
 * <p>Carries two node endpoints (with positions for dumb PLAYER nodes),
 * optional intermediate waypoints, and a {@code force} flag.
 *
 * <p>Server handler:
 * <ol>
 *   <li>Creates PLAYER nodes for any endpoint UUIDs not in the network</li>
 *   <li>Checks for existing edge between the two nodes</li>
 *   <li>If conflict and forced → removes old edge + replaces</li>
 *   <li>Generates path: from → waypoints → to via {@link PathGenerator#lShape3D}</li>
 *   <li>Adds edge to network + enqueues NPC build tasks</li>
 *   <li>Syncs updated network to all editing players</li>
 * </ol>
 */
public record RoadEdgePlanPacket(
        UUID fromNodeId,
        BlockPos fromPos,
        UUID toNodeId,
        BlockPos toPos,
        List<BlockPos> waypoints,
        boolean force,
        int width) implements CustomPacketPayload {

    private static final String TAG = "RoadEdgePlanPacket";

    public static final Type<RoadEdgePlanPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "road_edge_plan"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RoadEdgePlanPacket> STREAM_CODEC =
            StreamCodec.of(RoadEdgePlanPacket::write, RoadEdgePlanPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Server handler ──

    /** Handle on server: ensure nodes exist, plan path, enqueue NPC build tasks. */
    public static void handleServer(RoadEdgePlanPacket packet, ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        RoadSavedData roadData = RoadSavedData.getOrCreate(level);
        RoadNetwork network = roadData.getNetwork();

        // ── Phase 0: Auto-create PLAYER nodes for unknown endpoints ──
        ensureNode(network, packet.fromNodeId, packet.fromPos);
        ensureNode(network, packet.toNodeId, packet.toPos);

        RoadNode fromNode = network.getNode(packet.fromNodeId);
        RoadNode toNode = network.getNode(packet.toNodeId);

        if (fromNode == null || toNode == null) {
            Log.warn(TAG, "[RoadPlan] Node creation failed: from={} to={}",
                    packet.fromNodeId, packet.toNodeId);
            return;
        }

        Log.info(TAG, "[RoadPlan] Request: {}→{} waypoints={} force={}",
                packet.fromNodeId.toString().substring(0, 8),
                packet.toNodeId.toString().substring(0, 8),
                packet.waypoints.size(), packet.force);

        // ── Phase 1: Conflict detection ──
        var existing = network.findEdgeBetween(packet.fromNodeId, packet.toNodeId);
        if (existing.isPresent()) {
            UUID oldEdgeId = existing.get();
            Log.info(TAG, "[RoadPlan] Edge already exists: {} — removing old edge", oldEdgeId);
            RoadEditorHandler.removeEdge(level, network, oldEdgeId);
        }

        // ── Phase 2: Build path ──
        int edgeWidth = packet.width > 0 ? packet.width : 3;
        int amplitude = edgeWidth * 2;
        PathPoint cursor = new PathPoint(
                fromNode.pos().x(), fromNode.pos().y(), fromNode.pos().z());
        List<PathPoint> fullPath = new ArrayList<>();

        for (BlockPos wp : packet.waypoints) {
            PathPoint wpPt = new PathPoint(wp.getX(), wp.getY(), wp.getZ());
            List<PathPoint> segment = PathGenerator.lShape3D(cursor, wpPt, amplitude);
            if (!segment.isEmpty()) {
                fullPath.addAll(segment);
            }
            cursor = wpPt;
        }

        PathPoint toPt = new PathPoint(
                toNode.pos().x(), toNode.pos().y(), toNode.pos().z());
        List<PathPoint> lastSegment = PathGenerator.lShape3D(cursor, toPt, amplitude);
        if (!lastSegment.isEmpty()) {
            fullPath.addAll(lastSegment);
        }

        if (fullPath.isEmpty()) {
            Log.warn(TAG, "[RoadPlan] Generated empty path for {}→{}",
                    packet.fromNodeId, packet.toNodeId);
            return;
        }

        Log.info(TAG, "[RoadPlan] Path generated: {} points ({}→{} via {} waypoints)",
                fullPath.size(),
                packet.fromNodeId.toString().substring(0, 8),
                packet.toNodeId.toString().substring(0, 8),
                packet.waypoints.size());

        // ── Phase 3: Create edge and enqueue ──
        RoadEdge edge = new RoadEdge(
                UUID.randomUUID(),
                packet.fromNodeId, packet.toNodeId,
                "dirt", fullPath);
        edge.setWidth(edgeWidth);
        network.addEdge(edge);

        // Collect building bounds for collision avoidance
        BuildingSavedData buildingData = BuildingSavedData.get(level);
        var buildingBounds = new ArrayList<BoundingBox>();
        for (BuildingState bs : buildingData.getAllBuildings()) {
            buildingBounds.add(bs.getBounds());
        }

        // Collect already-occupied tiles from other edges
        Set<PathPoint> occupiedTiles = new HashSet<>();
        for (RoadEdge e : network.getEdges().values()) {
            if (!e.getEdgeId().equals(edge.getEdgeId())) {
                for (PathPoint pp : e.getPath()) {
                    occupiedTiles.add(pp);
                }
            }
        }

        RoadEventListener.enqueueEdge(edge, level, RoadConfig.getInstance(),
                buildingBounds, occupiedTiles);

        roadData.markChanged();
        RoadEditorNetwork.sendSyncToEditing(player.server);

        Log.info(TAG, "[RoadPlan] Edge {} enqueued. Network: {} nodes, {} edges",
                edge.getEdgeId().toString().substring(0, 8),
                network.nodeCount(), network.edgeCount());
    }

    /**
     * Ensure a node exists in the network. If the nodeId is unknown,
     * create a new PLAYER node at the given position.
     */
    private static void ensureNode(RoadNetwork network, UUID nodeId, BlockPos pos) {
        if (network.getNode(nodeId) != null) return;
        RoadNode node = new RoadNode(nodeId,
                new GridPos(pos.getX(), pos.getY(), pos.getZ()),
                RoadNode.NodeType.PLAYER);
        network.addNode(node);
        Log.info(TAG, "[RoadPlan] Created PLAYER node {} at ({},{},{})",
                nodeId.toString().substring(0, 8), pos.getX(), pos.getY(), pos.getZ());
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, RoadEdgePlanPacket pkt) {
        buf.writeUUID(pkt.fromNodeId);
        buf.writeBlockPos(pkt.fromPos);
        buf.writeUUID(pkt.toNodeId);
        buf.writeBlockPos(pkt.toPos);
        buf.writeVarInt(pkt.waypoints.size());
        for (BlockPos wp : pkt.waypoints) {
            buf.writeBlockPos(wp);
        }
        buf.writeBoolean(pkt.force);
        buf.writeVarInt(pkt.width);
    }

    static RoadEdgePlanPacket read(RegistryFriendlyByteBuf buf) {
        UUID from = buf.readUUID();
        BlockPos fromPos = buf.readBlockPos();
        UUID to = buf.readUUID();
        BlockPos toPos = buf.readBlockPos();
        int count = buf.readVarInt();
        List<BlockPos> wps = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            wps.add(buf.readBlockPos());
        }
        boolean force = buf.readBoolean();
        int width = buf.readVarInt();
        return new RoadEdgePlanPacket(from, fromPos, to, toPos, wps, force, width);
    }
}
