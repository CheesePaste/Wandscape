package com.wsteam.wandscape.building.network;

import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Server→client packet: opens the Town Hall info screen with colony name, level and experience.
 */
public record TownHallOpenPacket(BlockPos buildingPos, UUID colonyId,
                                 String colonyName, int level, int experience, int expToNext,
                                 String founderName)
        implements CustomPacketPayload {

    public static final Type<TownHallOpenPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "town_hall_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TownHallOpenPacket> STREAM_CODEC =
            StreamCodec.of(TownHallOpenPacket::write, TownHallOpenPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static Consumer<TownHallOpenPacket> clientHandler;

    public static void setClientHandler(Consumer<TownHallOpenPacket> handler) { clientHandler = handler; }

    public static void handleClient(TownHallOpenPacket packet) {
        if (clientHandler != null) clientHandler.accept(packet);
    }

    static void write(RegistryFriendlyByteBuf buf, TownHallOpenPacket pkt) {
        buf.writeLong(pkt.buildingPos.asLong());
        buf.writeUUID(pkt.colonyId);
        buf.writeUtf(pkt.colonyName != null ? pkt.colonyName : "");
        buf.writeVarInt(pkt.level);
        buf.writeVarInt(pkt.experience);
        buf.writeVarInt(pkt.expToNext);
        buf.writeUtf(pkt.founderName != null ? pkt.founderName : "");
    }

    static TownHallOpenPacket read(RegistryFriendlyByteBuf buf) {
        String founderName = buf.readUtf();
        return new TownHallOpenPacket(
                BlockPos.of(buf.readLong()),
                buf.readUUID(),
                buf.readUtf(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                founderName.isEmpty() ? null : founderName);
    }
}
