package com.wsteam.wandscape.content.items.guidebook.network;

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
public record GuidebookDocOpenPacket(String markdownContent) implements CustomPacketPayload {

    public static final Type<GuidebookDocOpenPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "guidebook_doc_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GuidebookDocOpenPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, GuidebookDocOpenPacket::markdownContent,
                    GuidebookDocOpenPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static Consumer<GuidebookDocOpenPacket> clientHandler;

    public static void setClientHandler(Consumer<GuidebookDocOpenPacket> handler) {
        clientHandler = handler;
    }

    public static void handleClient(GuidebookDocOpenPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        }
    }
}
