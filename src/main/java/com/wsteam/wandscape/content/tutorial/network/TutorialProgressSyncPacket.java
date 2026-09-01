package com.wsteam.wandscape.content.tutorial.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Consumer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server → Client: The player's saved tutorial progress, seeded when the panel
 * opens (or a colony is created) so completed/dismissed steps are not re-shown.
 * The client applies it via the handler registered in WandscapeClient.
 */
public record TutorialProgressSyncPacket(int stepIndex, boolean dismissed) implements CustomPacketPayload {

    public static final Type<TutorialProgressSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "tutorial_progress_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TutorialProgressSyncPacket> STREAM_CODEC =
            StreamCodec.of(TutorialProgressSyncPacket::write, TutorialProgressSyncPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static Consumer<TutorialProgressSyncPacket> clientHandler;

    public static void setClientHandler(Consumer<TutorialProgressSyncPacket> handler) {
        clientHandler = handler;
    }

    public static void handleClient(TutorialProgressSyncPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        }
    }

    static void write(RegistryFriendlyByteBuf buf, TutorialProgressSyncPacket pkt) {
        buf.writeVarInt(pkt.stepIndex);
        buf.writeBoolean(pkt.dismissed);
    }

    static TutorialProgressSyncPacket read(RegistryFriendlyByteBuf buf) {
        return new TutorialProgressSyncPacket(buf.readVarInt(), buf.readBoolean());
    }
}
