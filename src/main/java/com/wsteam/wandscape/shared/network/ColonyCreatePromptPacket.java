package com.wsteam.wandscape.shared.network;

import java.util.function.Consumer;

import com.wsteam.wandscape.building.client.TownHallCreateScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Server→Client: ask the player to name their new colony after they
 * right-click an intact town hall that has no colony. The client opens
 * {@link TownHallCreateScreen}, whose confirm button sends a
 * {@link ColonyCreateRequestPacket} back to the server.
 */
public record ColonyCreatePromptPacket(BlockPos townHallAnchor)
        implements CustomPacketPayload {

    public static final Type<ColonyCreatePromptPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "colony_create_prompt"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ColonyCreatePromptPacket> STREAM_CODEC =
            StreamCodec.of(ColonyCreatePromptPacket::write, ColonyCreatePromptPacket::read);

    private static Consumer<ColonyCreatePromptPacket> clientHandler;

    public static void setClientHandler(Consumer<ColonyCreatePromptPacket> handler) {
        clientHandler = handler;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleClient(ColonyCreatePromptPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        }
    }

    static void write(RegistryFriendlyByteBuf buf, ColonyCreatePromptPacket pkt) {
        buf.writeLong(pkt.townHallAnchor.asLong());
    }

    static ColonyCreatePromptPacket read(RegistryFriendlyByteBuf buf) {
        return new ColonyCreatePromptPacket(BlockPos.of(buf.readLong()));
    }
}
