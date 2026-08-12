package com.wsteam.wandscape.shared.network;

import java.util.UUID;

import com.wsteam.wandscape.engine.WandscapeEngine;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Client→Server: Updates the colony display name.
 * Player types a new name in the Town Hall screen → this packet carries it to the server.
 */
public record ColonyNameUpdatePacket(UUID colonyId, String name) implements CustomPacketPayload {

    public static final Type<ColonyNameUpdatePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "colony_name_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ColonyNameUpdatePacket> STREAM_CODEC =
            StreamCodec.of(ColonyNameUpdatePacket::write, ColonyNameUpdatePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleServer(ColonyNameUpdatePacket packet, ServerPlayer player) {
        if (packet.name() == null || packet.name().isBlank()) return;
        var levelMgr = com.wsteam.wandscape.engine.WandscapeEngine.getColonyLevelManager();
        if (levelMgr == null) return;
        String name = packet.name().trim();
        if (name.length() > 30) name = name.substring(0, 30);
        levelMgr.setColonyName(packet.colonyId(), name);

        var metricsApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getColonyMetricsApiSilently();
        if (metricsApi != null) {
            var snap = metricsApi.getSnapshot(packet.colonyId());
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                    ColonyStatsSyncPacket.fromSnapshot(snap));
        }
    }

    static void write(RegistryFriendlyByteBuf buf, ColonyNameUpdatePacket pkt) {
        buf.writeUUID(pkt.colonyId);
        buf.writeUtf(pkt.name != null ? pkt.name : "");
    }

    static ColonyNameUpdatePacket read(RegistryFriendlyByteBuf buf) {
        return new ColonyNameUpdatePacket(buf.readUUID(), buf.readUtf());
    }
}
