package com.wsteam.wandscape.content.building.network;
import com.wsteam.wandscape.content.colony.ColonySavedData;

import com.wsteam.wandscape.foundation.util.NameStyle;
import com.wsteam.wandscape.api.WandscapeApis;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Client→Server: switches the colony's character naming rule.
 * The player picks a style in the Town Hall screen → this packet carries it
 * to the server, where it is persisted per colony (ColonySavedData) and only
 * affects names generated afterwards.
 */
public record TownHallNameStylePacket(UUID colonyId, int namingStyle) implements CustomPacketPayload {

    public static final Type<TownHallNameStylePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "town_hall_name_style"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TownHallNameStylePacket> STREAM_CODEC =
            StreamCodec.of(TownHallNameStylePacket::write, TownHallNameStylePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleServer(TownHallNameStylePacket packet, ServerPlayer player) {
        if (packet.namingStyle < 0 || packet.namingStyle >= NameStyle.values().length) return;
        // 完全平行隔离：只能改自己小镇的起名风格。
        if (!com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.isOwn(packet.colonyId(), player)) {
            com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.deny(player, "小镇");
            return;
        }
        var colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi == null) return;
        colonyApi.setNamingStyle(packet.colonyId(), NameStyle.values()[packet.namingStyle]);
    }

    static void write(RegistryFriendlyByteBuf buf, TownHallNameStylePacket pkt) {
        buf.writeUUID(pkt.colonyId);
        buf.writeVarInt(pkt.namingStyle);
    }

    static TownHallNameStylePacket read(RegistryFriendlyByteBuf buf) {
        return new TownHallNameStylePacket(buf.readUUID(), buf.readVarInt());
    }
}
