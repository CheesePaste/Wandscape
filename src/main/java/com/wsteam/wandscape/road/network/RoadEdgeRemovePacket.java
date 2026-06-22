package com.wsteam.wandscape.road.network;

import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server packet requesting removal of a road edge.
 */
public record RoadEdgeRemovePacket(UUID edgeId) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<RoadEdgeRemovePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "road_edge_remove"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RoadEdgeRemovePacket> STREAM_CODEC =
            StreamCodec.of(RoadEdgeRemovePacket::write, RoadEdgeRemovePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handle on server: remove the edge and sync all editing clients. */
    public static void handleServer(RoadEdgeRemovePacket packet, ServerPlayer player) {
        LOGGER.info("[RoadEditor] RoadEdgeRemovePacket received: edgeId={} player={}",
                packet.edgeId, player.getGameProfile().getName());
        var roadApi = WandscapeApis.getRoadApi();
        if (roadApi == null) {
            LOGGER.warn("[RoadEditor] RoadApi is null — cannot remove edge");
            return;
        }

        roadApi.removeEdge(null, packet.edgeId);

        // Sync updated network to all editing players
        RoadEditorNetwork.sendSyncToEditing(player.server);
        LOGGER.info("[RoadEditor] Edge {} removed, synced to editing players", packet.edgeId);
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, RoadEdgeRemovePacket pkt) {
        buf.writeUUID(pkt.edgeId);
    }

    static RoadEdgeRemovePacket read(RegistryFriendlyByteBuf buf) {
        return new RoadEdgeRemovePacket(buf.readUUID());
    }
}
