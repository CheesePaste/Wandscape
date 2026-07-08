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
        if (packet.name == null || packet.name.isEmpty()) return;
        // Limit name length
        String name = packet.name.length() > 30 ? packet.name.substring(0, 30) : packet.name;
        var levelMgr = WandscapeEngine.getColonyLevelManager();
        if (levelMgr != null) {
            levelMgr.setColonyName(packet.colonyId, name);
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
