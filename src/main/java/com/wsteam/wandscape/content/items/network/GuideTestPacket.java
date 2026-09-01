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
public record GuideTestPacket(String markdownContent) implements CustomPacketPayload {

    public static final Type<GuideTestPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "guide_test"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GuideTestPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, GuideTestPacket::markdownContent,
                    GuideTestPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static Consumer<GuideTestPacket> clientHandler;

    public static void setClientHandler(Consumer<GuideTestPacket> handler) {
        clientHandler = handler;
    }

    public static void handleClient(GuideTestPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        }
    }
}
