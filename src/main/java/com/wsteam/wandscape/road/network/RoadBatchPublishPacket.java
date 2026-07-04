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
 * Client→Server: Batch publish all queued road segments from road projection mode.
 *
 * <p>Carries a list of {@link SegmentData} (fromPos, toPos, width).
 * Server handler creates PLAYER nodes for each endpoint, generates L-shaped paths,
 * creates RoadEdges, and enqueues them all via {@link RoadEventListener#enqueueEdge}.
 *
 * <p>After processing, syncs the updated network to all editing players.
 */
public record RoadBatchPublishPacket(List<SegmentData> segments) implements CustomPacketPayload {

    private static final String TAG = "RoadBatchPublishPacket";

    public static final Type<RoadBatchPublishPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "road_batch_publish"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RoadBatchPublishPacket> STREAM_CODEC =
            StreamCodec.of(RoadBatchPublishPacket::write, RoadBatchPublishPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Inner data type ──

    /** A single road segment to publish: two ground positions + width. */
    public record SegmentData(BlockPos from, BlockPos to, int width) {}

    // ── Server handler ──

    public static void handleServer(RoadBatchPublishPacket packet, ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        RoadSavedData roadData = RoadSavedData.getOrCreate(level);
        RoadNetwork network = roadData.getNetwork();

        // Collect building bounds for collision avoidance
        BuildingSavedData buildingData = BuildingSavedData.get(level);
        var buildingBounds = new ArrayList<BoundingBox>();
        for (BuildingState bs : buildingData.getAllBuildings()) {
            buildingBounds.add(bs.getBounds());
        }

        // Collect already-occupied tiles from existing edges
        Set<PathPoint> occupiedTiles = new HashSet<>();
        for (RoadEdge e : network.getEdges().values()) {
            for (PathPoint pp : e.getPath()) {
                occupiedTiles.add(pp);
            }
        }

        int createdCount = 0;

        for (SegmentData seg : packet.segments) {
            int edgeWidth = seg.width > 0 ? seg.width : 3;
            int amplitude = edgeWidth * 2;

            // Create PLAYER nodes for both endpoints
            UUID fromId = ensureNode(network, seg.from);
            UUID toId = ensureNode(network, seg.to);

            // Check for existing edge between these nodes
            var existing = network.findEdgeBetween(fromId, toId);
            if (existing.isPresent()) {
                Log.info(TAG, "[RoadBatch] Skipping {}→{} — edge already exists",
                        fromId.toString().substring(0, 8), toId.toString().substring(0, 8));
                continue;
            }

            // Generate L-shaped path
            PathPoint startPt = new PathPoint(
                    seg.from.getX(), seg.from.getY(), seg.from.getZ());
            PathPoint endPt = new PathPoint(
                    seg.to.getX(), seg.to.getY(), seg.to.getZ());
            List<PathPoint> path = PathGenerator.lShape3D(startPt, endPt, amplitude);

            if (path.isEmpty()) {
                Log.warn(TAG, "[RoadBatch] Empty path for {}→{} — skipping",
                        fromId.toString().substring(0, 8), toId.toString().substring(0, 8));
                continue;
            }

            // Create edge
            RoadEdge edge = new RoadEdge(
                    UUID.randomUUID(), fromId, toId, "dirt", path);
            edge.setWidth(edgeWidth);
            network.addEdge(edge);

            // Enqueue for NPC construction
            RoadEventListener.enqueueEdge(edge, level, RoadConfig.getInstance(),
                    buildingBounds, occupiedTiles);

            // Track newly placed tiles for subsequent segments
            occupiedTiles.addAll(path);

            createdCount++;
            Log.info(TAG, "[RoadBatch] Created edge {} ({}→{}, {} pts, width={})",
                    edge.getEdgeId().toString().substring(0, 8),
                    fromId.toString().substring(0, 8),
                    toId.toString().substring(0, 8),
                    path.size(), edgeWidth);
        }

        roadData.markChanged();
        RoadEditorNetwork.sendSyncToEditing(player.server);

        Log.info(TAG, "[RoadBatch] Published {} road segments from {}. Network: {} nodes, {} edges",
                createdCount, player.getGameProfile().getName(),
                network.nodeCount(), network.edgeCount());
    }

    /**
     * Ensure a node exists in the network at the given position.
     * Creates a new PLAYER node if none exists at that exact position.
     */
    private static UUID ensureNode(RoadNetwork network, BlockPos pos) {
        // Check for existing node at same position
        for (RoadNode node : network.getNodes().values()) {
            if (node.pos().x() == pos.getX()
                    && node.pos().y() == pos.getY()
                    && node.pos().z() == pos.getZ()) {
                return node.nodeId();
            }
        }

        UUID id = UUID.randomUUID();
        RoadNode node = new RoadNode(id,
                new GridPos(pos.getX(), pos.getY(), pos.getZ()),
                RoadNode.NodeType.PLAYER);
        network.addNode(node);
        Log.info(TAG, "[RoadBatch] Created PLAYER node {} at ({},{},{})",
                id.toString().substring(0, 8), pos.getX(), pos.getY(), pos.getZ());
        return id;
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, RoadBatchPublishPacket pkt) {
        buf.writeVarInt(pkt.segments.size());
        for (SegmentData seg : pkt.segments) {
            buf.writeBlockPos(seg.from);
            buf.writeBlockPos(seg.to);
            buf.writeVarInt(seg.width);
        }
    }

    static RoadBatchPublishPacket read(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<SegmentData> segs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            segs.add(new SegmentData(
                    buf.readBlockPos(), buf.readBlockPos(), buf.readVarInt()));
        }
        return new RoadBatchPublishPacket(segs);
    }
}
