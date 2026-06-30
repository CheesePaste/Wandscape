package com.wsteam.wandscape.stats.network;

import java.util.HashMap;
import java.util.Map;

import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.ui.panel.WandscapePanelState;
import com.wsteam.wandscape.stats.data.ColonyStatsSummary;

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
                s.buildingsPaid(), s.buildingsShutdown(), s.buildingsRestarted(),
                s.touristsArrived(), s.touristsDeparted(), s.avgSatisfaction(),
                s.comfort(), s.magic(), s.wonder(),
                s.totalElementsConsumed(), s.snapshotCount()));
    }

    // ── Serialization ──

    static void write(RegistryFriendlyByteBuf buf, StatsSyncPacket pkt) {
        ColonyStatsSummary s = pkt.summary();
        buf.writeLong(s.currentDay());
        buf.writeVarInt(s.buildingsPaid());
        buf.writeVarInt(s.buildingsShutdown());
        buf.writeVarInt(s.buildingsRestarted());
        buf.writeVarInt(s.touristsArrived());
        buf.writeVarInt(s.touristsDeparted());
        buf.writeVarInt(s.avgSatisfaction());
        buf.writeVarInt(s.comfort());
        buf.writeVarInt(s.magic());
        buf.writeVarInt(s.wonder());
        buf.writeVarInt(s.snapshotCount());
        writeElementLongMap(buf, s.totalElementsConsumed());
    }

    static StatsSyncPacket read(RegistryFriendlyByteBuf buf) {
        long currentDay = buf.readLong();
        int buildingsPaid = buf.readVarInt();
        int buildingsShutdown = buf.readVarInt();
        int buildingsRestarted = buf.readVarInt();
        int touristsArrived = buf.readVarInt();
        int touristsDeparted = buf.readVarInt();
        int avgSatisfaction = buf.readVarInt();
        int comfort = buf.readVarInt();
        int magic = buf.readVarInt();
        int wonder = buf.readVarInt();
        int snapshotCount = buf.readVarInt();
        Map<ElementType, Long> totalConsumed = readElementLongMap(buf);

        return new StatsSyncPacket(new ColonyStatsSummary(
                currentDay,
                buildingsPaid, buildingsShutdown, buildingsRestarted,
                touristsArrived, touristsDeparted, avgSatisfaction,
                comfort, magic, wonder,
                totalConsumed, snapshotCount));
    }

    private static void writeElementLongMap(RegistryFriendlyByteBuf buf, Map<ElementType, Long> map) {
        buf.writeVarInt(map.size());
        for (var entry : map.entrySet()) {
            buf.writeUtf(entry.getKey().getId());
            buf.writeLong(entry.getValue());
        }
    }

    private static Map<ElementType, Long> readElementLongMap(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<ElementType, Long> map = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            ElementType elem = ElementType.fromId(buf.readUtf());
            map.put(elem, buf.readLong());
        }
        return map;
    }
}
