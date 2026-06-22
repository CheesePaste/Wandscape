package com.wsteam.wandscape.road.network;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.core.road.PathGenerator;
import com.wsteam.wandscape.core.road.PathPoint;
import com.wsteam.wandscape.core.road.RoadEdge;
import com.wsteam.wandscape.core.road.RoadNetwork;
import com.wsteam.wandscape.core.road.RoadNode;
import com.wsteam.wandscape.core.road.XZPoint;
import com.wsteam.wandscape.engine.road.RoadConfig;
import com.wsteam.wandscape.engine.road.RoadEventListener;
import com.wsteam.wandscape.engine.road.RoadSavedData;
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

/**
 * Client→Server packet for player-initiated road path planning.
 *
 * <p>Carries two node endpoints, optional intermediate waypoints
 * (for custom route shaping), and a {@code force} flag that
 * skips conflict confirmation when an edge already exists.
 *
 * <p>Server handler:
 * <ol>
 *   <li>Checks for existing edge between the two nodes</li>
 *   <li>If conflict and not forced → removes old edge + replaces</li>
 *   <li>Generates path: from → waypoints → to via {@link PathGenerator#lShape3D}</li>
 *   <li>Adds edge to network + enqueues NPC build tasks</li>
 *   <li>Syncs updated network to all editing players</li>
 * </ol>
 */
public record RoadEdgePlanPacket(
        UUID fromNodeId,
        UUID toNodeId,
        List<BlockPos> waypoints,
        boolean force) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<RoadEdgePlanPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "road_edge_plan"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RoadEdgePlanPacket> STREAM_CODEC =
            StreamCodec.of(RoadEdgePlanPacket::write, RoadEdgePlanPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Server handler ──

    /** Handle on server: plan path, check conflicts, enqueue NPC build tasks. */
    public static void handleServer(RoadEdgePlanPacket packet, ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        RoadSavedData roadData = RoadSavedData.getOrCreate(level);
        RoadNetwork network = roadData.getNetwork();

        RoadNode fromNode = network.getNode(packet.fromNodeId);
        RoadNode toNode = network.getNode(packet.toNodeId);

        if (fromNode == null || toNode == null) {
            LOGGER.warn("[RoadPlan] Unknown node(s): from={} to={}",
                    packet.fromNodeId, packet.toNodeId);
            return;
        }

        LOGGER.info("[RoadPlan] Request: {}→{} waypoints={} force={}",
                packet.fromNodeId.toString().substring(0, 8),
                packet.toNodeId.toString().substring(0, 8),
                packet.waypoints.size(), packet.force);

        // ── Phase 1: Conflict detection ──
        var existing = network.findEdgeBetween(packet.fromNodeId, packet.toNodeId);
        if (existing.isPresent()) {
            UUID oldEdgeId = existing.get();
            LOGGER.info("[RoadPlan] Edge already exists: {} — removing old edge", oldEdgeId);
            RoadEditorHandler.removeEdge(level, network, oldEdgeId);
        }

        // ── Phase 2: Build path ──
        PathPoint cursor = new PathPoint(
                fromNode.pos().x(), fromNode.pos().y(), fromNode.pos().z());
        List<PathPoint> fullPath = new ArrayList<>();

        for (BlockPos wp : packet.waypoints) {
            PathPoint wpPt = new PathPoint(wp.getX(), wp.getY(), wp.getZ());
            List<PathPoint> segment = PathGenerator.lShape3D(cursor, wpPt);
            if (!segment.isEmpty()) {
                fullPath.addAll(segment);
            }
            cursor = wpPt;
        }

        PathPoint toPt = new PathPoint(
                toNode.pos().x(), toNode.pos().y(), toNode.pos().z());
        List<PathPoint> lastSegment = PathGenerator.lShape3D(cursor, toPt);
        if (!lastSegment.isEmpty()) {
            fullPath.addAll(lastSegment);
        }

        if (fullPath.isEmpty()) {
            LOGGER.warn("[RoadPlan] Generated empty path for {}→{}",
                    packet.fromNodeId, packet.toNodeId);
            return;
        }

        LOGGER.info("[RoadPlan] Path generated: {} points ({}→{} via {} waypoints)",
                fullPath.size(),
                packet.fromNodeId.toString().substring(0, 8),
                packet.toNodeId.toString().substring(0, 8),
                packet.waypoints.size());

        // ── Phase 3: Create edge and enqueue ──
        RoadEdge edge = new RoadEdge(
                UUID.randomUUID(),
                packet.fromNodeId, packet.toNodeId,
                "dirt", fullPath);
        network.addEdge(edge);

        // Collect building bounds for collision avoidance
        BuildingSavedData buildingData = BuildingSavedData.get(level);
        var buildingBounds = new ArrayList<BoundingBox>();
        for (BuildingState bs : buildingData.getAllBuildings()) {
            buildingBounds.add(bs.getBounds());
        }

        // Collect already-occupied tiles from other edges
        Set<XZPoint> occupiedTiles = new HashSet<>();
        for (RoadEdge e : network.getEdges().values()) {
            if (!e.getEdgeId().equals(edge.getEdgeId())) {
                for (PathPoint pp : e.getPath()) {
                    occupiedTiles.add(pp.xz());
                }
            }
        }

        RoadEventListener.enqueueEdge(edge, level, RoadConfig.getInstance(),
                buildingBounds, occupiedTiles);

        edge.incrementPendingSegments(1); // will be refined by enqueueEdge
        roadData.markChanged();
        RoadEditorNetwork.sendSyncToEditing(player.server);

        LOGGER.info("[RoadPlan] Edge {} enqueued. Network: {} nodes, {} edges",
                edge.getEdgeId().toString().substring(0, 8),
                network.nodeCount(), network.edgeCount());
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, RoadEdgePlanPacket pkt) {
        buf.writeUUID(pkt.fromNodeId);
        buf.writeUUID(pkt.toNodeId);
        buf.writeVarInt(pkt.waypoints.size());
        for (BlockPos wp : pkt.waypoints) {
            buf.writeBlockPos(wp);
        }
        buf.writeBoolean(pkt.force);
    }

    static RoadEdgePlanPacket read(RegistryFriendlyByteBuf buf) {
        UUID from = buf.readUUID();
        UUID to = buf.readUUID();
        int count = buf.readVarInt();
        List<BlockPos> wps = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            wps.add(buf.readBlockPos());
        }
        boolean force = buf.readBoolean();
        return new RoadEdgePlanPacket(from, to, wps, force);
    }
}
