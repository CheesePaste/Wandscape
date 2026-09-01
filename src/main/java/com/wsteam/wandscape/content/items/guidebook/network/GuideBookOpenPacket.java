package com.wsteam.wandscape.content.items.guidebook.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Consumer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server -> Client packet: 请求客户端打开指南书阅读器。
 *
 * <p>负载是文档路径（如 {@code index_guide}），客户端用 {@code DocumentLoader}
 * 按当前语言加载，再交给 {@code GuidebookScreen} 渲染——服务端不读资源、不传大字符串。
 */
public record GuideBookOpenPacket(String docPath) implements CustomPacketPayload {

    public static final Type<GuideBookOpenPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "guide_book_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GuideBookOpenPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, GuideBookOpenPacket::docPath,
                    GuideBookOpenPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static Consumer<GuideBookOpenPacket> clientHandler;

    public static void setClientHandler(Consumer<GuideBookOpenPacket> handler) {
        clientHandler = handler;
    }

    public static void handleClient(GuideBookOpenPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        }
    }
}
