package com.wsteam.wandscape.road.network;

import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server stub packet for player path planning between two nodes.
 * V1: logged but not implemented. Reserved for future custom routing.
 */
public record RoadEdgePlanPacket(UUID fromNodeId, UUID toNodeId) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<RoadEdgePlanPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "road_edge_plan"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RoadEdgePlanPacket> STREAM_CODEC =
            StreamCodec.of(RoadEdgePlanPacket::write, RoadEdgePlanPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handle on server: stub — log and ignore. */
    public static void handleServer(RoadEdgePlanPacket packet, ServerPlayer player) {
        LOGGER.info("[RoadEditor] Path planning stub: {} → {} (not yet implemented)",
                packet.fromNodeId.toString().substring(0, 8),
                packet.toNodeId.toString().substring(0, 8));
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, RoadEdgePlanPacket pkt) {
        buf.writeUUID(pkt.fromNodeId);
        buf.writeUUID(pkt.toNodeId);
    }

    static RoadEdgePlanPacket read(RegistryFriendlyByteBuf buf) {
        return new RoadEdgePlanPacket(buf.readUUID(), buf.readUUID());
    }
}
