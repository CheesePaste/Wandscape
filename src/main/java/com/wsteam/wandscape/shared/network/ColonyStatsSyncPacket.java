package com.wsteam.wandscape.shared.network;

import com.wsteam.wandscape.shared.data.ColonyMetricsSnapshot;
import com.wsteam.wandscape.shared.ui.panel.WandscapePanelState;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Server→Client: Syncs colony evaluation values + panel HUD data to the client.
 */
public record ColonyStatsSyncPacket(
        UUID colonyId,
        int comfort, int magic, int wonder,
        String colonyName, int colonyLevel, int colonyExperience,
        int touristCount,
        int overnightStayerCount,
        int shutdownCount,
        int npcIdleCount, int npcTotalCount,
        int earthAmount, int woodAmount, int waterAmount, int fireAmount, int windAmount,
        int metalAmount, int darkAmount,
        List<String> shutdownBuildingNames,
        List<UUID> shutdownBuildingIds,
        int brokenCount,
        List<UUID> brokenBuildingIds,
        List<String> brokenBuildingNames
) implements CustomPacketPayload {

    public static final Type<ColonyStatsSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "colony_stats_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ColonyStatsSyncPacket> STREAM_CODEC =
            StreamCodec.of(ColonyStatsSyncPacket::write, ColonyStatsSyncPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static ColonyStatsSyncPacket fromSnapshot(ColonyMetricsSnapshot snap) {
        return new ColonyStatsSyncPacket(
                snap.colonyId(), snap.comfort(), snap.magic(), snap.wonder(),
                snap.colonyName(), snap.colonyLevel(), snap.colonyExperience(),
                snap.touristCount(), snap.overnightStayerCount(), snap.shutdownCount(),
                snap.npcIdleCount(), snap.npcTotalCount(),
                snap.earthAmount(), snap.woodAmount(), snap.waterAmount(),
                snap.fireAmount(), snap.windAmount(), snap.metalAmount(), snap.darkAmount(),
                snap.shutdownBuildingNames(), snap.shutdownBuildingIds(),
                snap.brokenCount(), snap.brokenBuildingIds(), snap.brokenBuildingNames());
    }

    public static void handleClient(ColonyStatsSyncPacket packet) {
        WandscapePanelState.setColonyStats(
                packet.colonyId, packet.comfort, packet.magic, packet.wonder,
                packet.colonyName, packet.colonyLevel, packet.colonyExperience,
                packet.touristCount, packet.overnightStayerCount, packet.shutdownCount,
                packet.npcIdleCount, packet.npcTotalCount,
                packet.earthAmount, packet.woodAmount, packet.waterAmount, packet.fireAmount, packet.windAmount,
                packet.metalAmount, packet.darkAmount,
                packet.shutdownBuildingNames, packet.shutdownBuildingIds,
                packet.brokenCount, packet.brokenBuildingIds, packet.brokenBuildingNames);
    }

    static void write(RegistryFriendlyByteBuf buf, ColonyStatsSyncPacket pkt) {
        buf.writeUUID(pkt.colonyId);
        buf.writeVarInt(pkt.comfort);
        buf.writeVarInt(pkt.magic);
        buf.writeVarInt(pkt.wonder);
        buf.writeUtf(pkt.colonyName != null ? pkt.colonyName : "");
        buf.writeVarInt(pkt.colonyLevel);
        buf.writeVarInt(pkt.colonyExperience);
        buf.writeVarInt(pkt.touristCount);
        buf.writeVarInt(pkt.overnightStayerCount);
        buf.writeVarInt(pkt.shutdownCount);
        buf.writeVarInt(pkt.npcIdleCount);
        buf.writeVarInt(pkt.npcTotalCount);
        buf.writeVarInt(pkt.earthAmount);
        buf.writeVarInt(pkt.woodAmount);
        buf.writeVarInt(pkt.waterAmount);
        buf.writeVarInt(pkt.fireAmount);
        buf.writeVarInt(pkt.windAmount);
        buf.writeVarInt(pkt.metalAmount);
        buf.writeVarInt(pkt.darkAmount);
        buf.writeCollection(pkt.shutdownBuildingNames, (b, s) -> b.writeUtf(s));
        buf.writeCollection(pkt.shutdownBuildingIds, (b, id) -> b.writeUUID(id));
        buf.writeVarInt(pkt.brokenCount);
        buf.writeCollection(pkt.brokenBuildingIds, (b, id) -> b.writeUUID(id));
        buf.writeCollection(pkt.brokenBuildingNames, (b, s) -> b.writeUtf(s));
    }

    static ColonyStatsSyncPacket read(RegistryFriendlyByteBuf buf) {
        return new ColonyStatsSyncPacket(
                buf.readUUID(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readUtf(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), // touristCount
                buf.readVarInt(), // overnightStayerCount
                buf.readVarInt(), // shutdownCount
                buf.readVarInt(), buf.readVarInt(), // npcIdle, npcTotal
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), // 5 elements
                buf.readVarInt(), buf.readVarInt(), // metal, dark
                buf.readList(b -> b.readUtf()), // shutdownBuildingNames
                buf.readList(b -> b.readUUID()), // shutdownBuildingIds
                buf.readVarInt(), // brokenCount
                buf.readList(b -> b.readUUID()), // brokenBuildingIds
                buf.readList(b -> b.readUtf()) // brokenBuildingNames
        );
    }
}
