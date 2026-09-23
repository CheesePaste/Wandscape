package com.wsteam.wandscape.content.items.guidebook.network;

import com.wsteam.wandscape.foundation.networking.ClientPayloadDispatcher;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;


import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server -> Client packet: 请求客户端打开指南书阅读器。
 *
 * <p>负载是页名（条目 id / 别名 / {@code category:<分类>}，空串＝手册首页），客户端由
 * {@code GuideFacade} 路由到帕秋莉手册或兜底阅读器——服务端不读资源、不传大字符串。
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



    public static void handleClient(GuideBookOpenPacket packet) {
        ClientPayloadDispatcher.dispatch(packet);
    }
}
