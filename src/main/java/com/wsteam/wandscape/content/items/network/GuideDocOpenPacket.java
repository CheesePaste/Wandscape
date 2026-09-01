package com.wsteam.wandscape.content.items.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Consumer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server -> Client packet to open the Markdown guide test screen.
 */
public record GuideDocOpenPacket(String markdownContent) implements CustomPacketPayload {

    public static final Type<GuideDocOpenPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "guide_doc_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GuideDocOpenPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, GuideDocOpenPacket::markdownContent,
                    GuideDocOpenPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static Consumer<GuideDocOpenPacket> clientHandler;

    public static void setClientHandler(Consumer<GuideDocOpenPacket> handler) {
        clientHandler = handler;
    }

    public static void handleClient(GuideDocOpenPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        }
    }
}
