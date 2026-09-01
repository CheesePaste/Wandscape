package com.wsteam.wandscape.content.colony.stats.network;

import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelState;
import com.wsteam.wandscape.content.colony.stats.data.ColonyStatsSummary;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Server→Client: Pushes a colony's aggregated stats summary to the panel.
 */
public record StatsSyncPacket(ColonyStatsSummary summary) implements CustomPacketPayload {

    public static final Type<StatsSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "stats_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StatsSyncPacket> STREAM_CODEC =
            StreamCodec.of(StatsSyncPacket::write, StatsSyncPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleClient(StatsSyncPacket packet) {
        ColonyStatsSummary s = packet.summary();
        WandscapePanelState.setStatsSummary(new WandscapePanelState.StatsSummary(
                s.currentDay(),
                s.touristsArrived(), s.touristsDeparted(),
                s.avgComfortRatio(), s.avgMagicRatio(), s.avgWonderRatio(),
                s.comfort(), s.magic(), s.wonder(),
                s.snapshotCount()));
    }

    // ── Serialization ──

    static void write(RegistryFriendlyByteBuf buf, StatsSyncPacket pkt) {
        ColonyStatsSummary s = pkt.summary();
        buf.writeLong(s.currentDay());
        buf.writeVarInt(s.touristsArrived());
        buf.writeVarInt(s.touristsDeparted());
        buf.writeVarInt(s.avgComfortRatio());
        buf.writeVarInt(s.avgMagicRatio());
        buf.writeVarInt(s.avgWonderRatio());
        buf.writeVarInt(s.comfort());
        buf.writeVarInt(s.magic());
        buf.writeVarInt(s.wonder());
        buf.writeVarInt(s.snapshotCount());
    }

    static StatsSyncPacket read(RegistryFriendlyByteBuf buf) {
        long currentDay = buf.readLong();
        int touristsArrived = buf.readVarInt();
        int touristsDeparted = buf.readVarInt();
        int avgComfortRatio = buf.readVarInt();
        int avgMagicRatio = buf.readVarInt();
        int avgWonderRatio = buf.readVarInt();
        int comfort = buf.readVarInt();
        int magic = buf.readVarInt();
        int wonder = buf.readVarInt();
        int snapshotCount = buf.readVarInt();

        return new StatsSyncPacket(new ColonyStatsSummary(
                currentDay,
                touristsArrived, touristsDeparted,
                avgComfortRatio, avgMagicRatio, avgWonderRatio,
                comfort, magic, wonder,
                snapshotCount));
    }
}
