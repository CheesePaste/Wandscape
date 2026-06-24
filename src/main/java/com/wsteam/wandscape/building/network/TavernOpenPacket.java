package com.wsteam.wandscape.building.network;

import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→client packet: opens the Tavern GUI with building context.
 */
public record TavernOpenPacket(BlockPos buildingPos, UUID colonyId)
        implements CustomPacketPayload {

    public static final Type<TavernOpenPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "tavern_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TavernOpenPacket> STREAM_CODEC =
            StreamCodec.of(TavernOpenPacket::write, TavernOpenPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Client handler ──

    private static Consumer<TavernOpenPacket> clientHandler;

    public static void setClientHandler(Consumer<TavernOpenPacket> handler) {
        clientHandler = handler;
    }

    public static void handleClient(TavernOpenPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        }
    }

    // ── StreamCodec helpers ──

    static void write(RegistryFriendlyByteBuf buf, TavernOpenPacket pkt) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("pos", pkt.buildingPos.asLong());
        tag.putUUID("colony", pkt.colonyId);
        buf.writeNbt(tag);
    }

    static TavernOpenPacket read(RegistryFriendlyByteBuf buf) {
        CompoundTag tag = buf.readNbt();
        if (tag == null) {
            return new TavernOpenPacket(BlockPos.ZERO, new UUID(0, 0));
        }
        return new TavernOpenPacket(
                BlockPos.of(tag.getLong("pos")),
                tag.getUUID("colony"));
    }
}
