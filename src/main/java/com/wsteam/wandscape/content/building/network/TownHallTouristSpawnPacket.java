package com.wsteam.wandscape.content.building.network;
import com.wsteam.wandscape.content.colony.ColonySavedData;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Client→Server: toggles the colony's town hall 「生成游客」setting.
 * The player flips the button in the Town Hall screen → this packet carries the
 * new state to the server, where it is persisted per colony (ColonySavedData).
 */
public record TownHallTouristSpawnPacket(UUID colonyId, boolean enabled) implements CustomPacketPayload {

    public static final Type<TownHallTouristSpawnPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "town_hall_tourist_spawn"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TownHallTouristSpawnPacket> STREAM_CODEC =
            StreamCodec.of(TownHallTouristSpawnPacket::write, TownHallTouristSpawnPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleServer(TownHallTouristSpawnPacket packet, ServerPlayer player) {
        if (packet.colonyId == null) return;
        player.getServer().execute(() -> {
            ColonySavedData csd = ColonySavedData.getOrCreate(player.serverLevel());
            csd.setTouristSpawningEnabled(packet.colonyId, packet.enabled);
        });
    }

    static void write(RegistryFriendlyByteBuf buf, TownHallTouristSpawnPacket pkt) {
        buf.writeUUID(pkt.colonyId);
        buf.writeBoolean(pkt.enabled);
    }

    static TownHallTouristSpawnPacket read(RegistryFriendlyByteBuf buf) {
        return new TownHallTouristSpawnPacket(buf.readUUID(), buf.readBoolean());
    }
}
