package com.wsteam.wandscape.shared.network;

import java.util.ArrayList;
import java.util.List;

import com.wsteam.wandscape.road.core.PathPoint;
import com.wsteam.wandscape.road.core.RoadEdge;
import com.wsteam.wandscape.shared.api.RoadApi;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→Client: syncs every road edge currently under construction (status not
 * COMPLETE) so the client can render translucent construction ghosts, mirroring
 * {@link BuildingAreaSyncPacket}. Sent when the panel opens, when a road is
 * placed, and when a road completes.
 *
 * <p>Only non-COMPLETE edges are sent — the client cache reflects exactly what is
 * still being built. Tile positions come from {@link RoadEdge#getPlacedBlocks()}
 * (the road's planned footprint), so the client renders each cell and skips ones
 * whose target block is already in the world.
 */
public record RoadAreaSyncPacket(List<RoadEntry> roads) implements CustomPacketPayload {

    private static final String TAG = "RoadAreaSync";

    public static final Type<RoadAreaSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "road_area_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RoadAreaSyncPacket> STREAM_CODEC =
            StreamCodec.of(RoadAreaSyncPacket::write, RoadAreaSyncPacket::read);

    /** Client-side cache, updated each time a sync arrives. */
    private static volatile List<RoadEntry> cached = List.of();

    public static List<RoadEntry> getCached() {
        return cached;
    }

    /** Clear the client cache (e.g. when the client changes world/dimension). */
    public static void clearCache() {
        cached = List.of();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Client handler ──

    public static void handleClient(RoadAreaSyncPacket packet) {
        cached = packet.roads;
        Log.info(TAG, "[Road] Cached {} under-construction road(s)", packet.roads.size());
    }

    // ── Factory: server-side creation ──

    private static List<RoadEntry> buildEntries() {
        RoadApi roadApi = WandscapeApis.getRoadApi();
        if (roadApi == null) return List.of();
        List<RoadEntry> entries = new ArrayList<>();
        for (RoadEdge edge : roadApi.getEdges(null)) {
            if (edge.getStatus() == RoadEdge.EdgeStatus.COMPLETE) continue;
            entries.add(new RoadEntry(edge.getTier(), new ArrayList<>(edge.getPlacedBlocks())));
        }
        return entries;
    }

    /** Send the under-construction road sync to a single player. */
    public static void sendToPlayer(ServerPlayer player) {
        if (player == null || player.level() == null) return;
        PacketDistributor.sendToPlayer(player, new RoadAreaSyncPacket(buildEntries()));
    }

    /** Broadcast the under-construction road sync to every player on the server. */
    public static void broadcastToServer(MinecraftServer server) {
        if (server == null) return;
        RoadAreaSyncPacket packet = new RoadAreaSyncPacket(buildEntries());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(player, packet);
        }
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, RoadAreaSyncPacket pkt) {
        buf.writeVarInt(pkt.roads.size());
        for (RoadEntry entry : pkt.roads) {
            buf.writeUtf(entry.presetId());
            buf.writeVarInt(entry.tiles().size());
            for (PathPoint p : entry.tiles()) {
                buf.writeVarInt(p.x());
                buf.writeVarInt(p.y());
                buf.writeVarInt(p.z());
            }
        }
    }

    static RoadAreaSyncPacket read(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<RoadEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String presetId = buf.readUtf();
            int tileCount = buf.readVarInt();
            List<PathPoint> tiles = new ArrayList<>(tileCount);
            for (int t = 0; t < tileCount; t++) {
                tiles.add(new PathPoint(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
            }
            entries.add(new RoadEntry(presetId, tiles));
        }
        return new RoadAreaSyncPacket(entries);
    }

    // ── Data ──

    public record RoadEntry(String presetId, List<PathPoint> tiles) {}
}
