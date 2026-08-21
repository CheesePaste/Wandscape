package com.wsteam.wandscape.worldreloader.network;

import java.util.function.Consumer;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Packet sent from server to client to cancel/remove any active transformation preview.
 */
public record TransformPreviewCancelPacket() implements CustomPacketPayload {

    public static final Type<TransformPreviewCancelPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "transform_preview_cancel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TransformPreviewCancelPacket> STREAM_CODEC =
            StreamCodec.of((buf, pkt) -> {}, buf -> new TransformPreviewCancelPacket());

    private static Consumer<TransformPreviewCancelPacket> clientHandler = packet -> {};
    public static void setClientHandler(Consumer<TransformPreviewCancelPacket> handler) {
        clientHandler = handler;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(TransformPreviewCancelPacket packet) {
        clientHandler.accept(packet);
    }
}
