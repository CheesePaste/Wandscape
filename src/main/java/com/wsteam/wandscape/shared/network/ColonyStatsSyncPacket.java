package com.wsteam.wandscape.shared.network;

import com.wsteam.wandscape.shared.ui.panel.WandscapePanelState;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Server→Client: Syncs colony evaluation values (comfort/magic/wonder) + name/level/exp to the panel.
 */
public record ColonyStatsSyncPacket(UUID colonyId, int comfort, int magic, int wonder,
                                    String colonyName, int colonyLevel, int colonyExperience)
        implements CustomPacketPayload {

    public static final Type<ColonyStatsSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "colony_stats_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ColonyStatsSyncPacket> STREAM_CODEC =
            StreamCodec.of(ColonyStatsSyncPacket::write, ColonyStatsSyncPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleClient(ColonyStatsSyncPacket packet) {
        WandscapePanelState.setColonyStats(
                packet.colonyId, packet.comfort, packet.magic, packet.wonder,
                packet.colonyName, packet.colonyLevel, packet.colonyExperience);
    }

    static void write(RegistryFriendlyByteBuf buf, ColonyStatsSyncPacket pkt) {
        buf.writeUUID(pkt.colonyId);
        buf.writeVarInt(pkt.comfort);
        buf.writeVarInt(pkt.magic);
        buf.writeVarInt(pkt.wonder);
        buf.writeUtf(pkt.colonyName != null ? pkt.colonyName : "");
        buf.writeVarInt(pkt.colonyLevel);
        buf.writeVarInt(pkt.colonyExperience);
    }

    static ColonyStatsSyncPacket read(RegistryFriendlyByteBuf buf) {
        return new ColonyStatsSyncPacket(
                buf.readUUID(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readUtf(), buf.readVarInt(), buf.readVarInt());
    }
}
