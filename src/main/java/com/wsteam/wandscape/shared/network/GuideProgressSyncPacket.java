package com.wsteam.wandscape.shared.network;

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
public record GuideProgressSyncPacket(int stepIndex, boolean dismissed) implements CustomPacketPayload {

    public static final Type<GuideProgressSyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "guide_progress_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GuideProgressSyncPacket> STREAM_CODEC =
            StreamCodec.of(GuideProgressSyncPacket::write, GuideProgressSyncPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static Consumer<GuideProgressSyncPacket> clientHandler;

    public static void setClientHandler(Consumer<GuideProgressSyncPacket> handler) {
        clientHandler = handler;
    }

    public static void handleClient(GuideProgressSyncPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        }
    }

    static void write(RegistryFriendlyByteBuf buf, GuideProgressSyncPacket pkt) {
        buf.writeVarInt(pkt.stepIndex);
        buf.writeBoolean(pkt.dismissed);
    }

    static GuideProgressSyncPacket read(RegistryFriendlyByteBuf buf) {
        return new GuideProgressSyncPacket(buf.readVarInt(), buf.readBoolean());
    }
}
