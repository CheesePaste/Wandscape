package com.wsteam.wandscape.road.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.road.core.PathPoint;
import com.wsteam.wandscape.road.core.RoadEdge;
import com.wsteam.wandscape.road.core.RoadNetwork;
import com.wsteam.wandscape.road.core.RoadNode;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.road.client.RoadEditorClientState;
import com.wsteam.wandscape.road.client.RoadProjectionClientState;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Server→Client packet carrying the full road network state.
 * Sent when a player enters edit mode and after each network modification.
 *
 * <p>A null colonyId signals "exit edit mode" (client clears state).
 */
public record RoadNetworkSyncPacket(CompoundTag data) implements CustomPacketPayload {

    private static final String TAG = "RoadNetworkSyncPacket";

    public static final Type<RoadNetworkSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "road_network_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RoadNetworkSyncPacket> STREAM_CODEC =
            StreamCodec.of(RoadNetworkSyncPacket::write, RoadNetworkSyncPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Factory methods ──

    /** Build a sync packet from a live RoadNetwork. */
    public static RoadNetworkSyncPacket from(RoadNetwork network, UUID colonyId) {
        CompoundTag root = new CompoundTag();
        root.putBoolean("enterEdit", true);
        if (colonyId != null) {
            root.putUUID("colonyId", colonyId);
        }

        // Nodes
        ListTag nodeList = new ListTag();
        for (RoadNode node : network.getNodes().values()) {
            CompoundTag n = new CompoundTag();
            n.putUUID("nodeId", node.nodeId());
            n.putInt("x", node.pos().x());
            n.putInt("y", node.pos().y());
            n.putInt("z", node.pos().z());
            n.putString("type", node.type().name());
            nodeList.add(n);
        }
        root.put("nodes", nodeList);

        // Edges
        ListTag edgeList = new ListTag();
        for (RoadEdge edge : network.getEdges().values()) {
            CompoundTag e = new CompoundTag();
            e.putUUID("edgeId", edge.getEdgeId());
            e.putUUID("fromNodeId", edge.getFromNodeId());
            e.putUUID("toNodeId", edge.getToNodeId());
            e.putString("tier", edge.getTier());
            e.putString("status", edge.getStatus().name());

            ListTag pathTag = new ListTag();
            for (PathPoint p : edge.getPath()) {
                CompoundTag pt = new CompoundTag();
                pt.putInt("x", p.x());
                pt.putInt("y", p.y());
                pt.putInt("z", p.z());
                pathTag.add(pt);
            }
            e.put("path", pathTag);
            edgeList.add(e);
        }
        root.put("edges", edgeList);

        return new RoadNetworkSyncPacket(root);
    }

    /** Build an exit-edit-mode packet. */
    public static RoadNetworkSyncPacket exitPacket() {
        CompoundTag root = new CompoundTag();
        root.putBoolean("enterEdit", false);
        root.put("nodes", new ListTag());
        root.put("edges", new ListTag());
        return new RoadNetworkSyncPacket(root);
    }

    // ── Decoding (client-side) ──

    /** Whether this packet signals entering edit mode (explicit boolean flag). */
    public boolean isEnterEdit() {
        return data.getBoolean("enterEdit");
    }

    /** Reconstruct a RoadNetwork from the packet data. */
    public RoadNetwork toNetwork() {
        RoadNetwork net = new RoadNetwork();

        ListTag nodeList = data.getList("nodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < nodeList.size(); i++) {
            CompoundTag n = nodeList.getCompound(i);
            UUID nodeId = n.getUUID("nodeId");
            GridPos pos = new GridPos(n.getInt("x"), n.getInt("y"), n.getInt("z"));
            RoadNode.NodeType type;
            try {
                type = RoadNode.NodeType.valueOf(n.getString("type"));
            } catch (IllegalArgumentException ex) {
                type = RoadNode.NodeType.ORPHAN;
            }
            net.addNode(new RoadNode(nodeId, pos, type));
        }

        ListTag edgeList = data.getList("edges", Tag.TAG_COMPOUND);
        for (int i = 0; i < edgeList.size(); i++) {
            CompoundTag e = edgeList.getCompound(i);
            UUID edgeId = e.getUUID("edgeId");
            UUID fromId = e.getUUID("fromNodeId");
            UUID toId = e.getUUID("toNodeId");
            String tier = e.getString("tier");

            RoadEdge.EdgeStatus status;
            try {
                status = RoadEdge.EdgeStatus.valueOf(e.getString("status"));
            } catch (IllegalArgumentException ex) {
                status = RoadEdge.EdgeStatus.PLANNED;
            }

            List<PathPoint> path = new ArrayList<>();
            ListTag pathTag = e.getList("path", Tag.TAG_COMPOUND);
            for (int j = 0; j < pathTag.size(); j++) {
                CompoundTag pt = pathTag.getCompound(j);
                path.add(new PathPoint(pt.getInt("x"), pt.getInt("y"), pt.getInt("z")));
            }

            RoadEdge edge = new RoadEdge(edgeId, fromId, toId, tier, path);
            edge.setStatus(status);
            net.addEdge(edge);
        }

        return net;
    }

    // ── Client handler ──

    /** Handle on client: update editor state with network data.
     *  Routes to RoadProjectionClientState if the client is expecting a road projection sync
     *  (flag set by V-key handler before sending RoadEditorTogglePacket). */
    public static void handleClient(RoadNetworkSyncPacket packet) {
        // ── Road projection routing ──
        if (RoadProjectionClientState.isExpectingSync()) {
            RoadProjectionClientState.setExpectingSync(false);
            if (packet.isEnterEdit()) {
                RoadNetwork network = packet.toNetwork();
                Log.info(TAG, "[RoadProjection] Sync received: enterEdit=true, nodes={} edges={}",
                        network.nodeCount(), network.edgeCount());
                RoadProjectionClientState.enterProjection(network);
            } else {
                Log.info(TAG, "[RoadProjection] Sync received: enterEdit=false — exit");
                RoadProjectionClientState.exitProjection();
            }
            return;
        }

        // ── Original road editor path ──
        Log.info(TAG, "[RoadEditor] RoadNetworkSyncPacket received: enterEdit={} dataSize={}",
                packet.isEnterEdit(), packet.data.toString().length());
        if (packet.isEnterEdit()) {
            RoadNetwork network = packet.toNetwork();
            Log.info(TAG, "[RoadEditor] handleClient: parsed network nodes={} edges={}",
                    network.nodeCount(), network.edgeCount());
            RoadEditorClientState.setNetworkSnapshot(network);
            RoadEditorClientState.setEditMode(true);
        } else {
            Log.info(TAG, "[RoadEditor] handleClient: exit edit mode");
            RoadEditorClientState.clearSnapshot();
            RoadEditorClientState.setEditMode(false);
        }
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, RoadNetworkSyncPacket pkt) {
        buf.writeNbt(pkt.data);
    }

    static RoadNetworkSyncPacket read(RegistryFriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        return new RoadNetworkSyncPacket(tag != null ? tag : new CompoundTag());
    }
}
