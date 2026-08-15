package com.wsteam.wandscape.shared.network;

import java.util.function.Consumer;

import com.wsteam.wandscape.Wandscape;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server→client packet：通知客户端小镇环境音状态。
 * 服务端 {@code ColonyAmbientTracker} 判断玩家是否进入任一建筑包围盒+20 格范围，
 * 进入/离开或昼夜相位切换时发送；客户端驱动循环环境音启停/切相位。
 *
 * @param playing true=应播放（玩家在城镇范围内），false=停止
 * @param day     相位：true=白天（人群），false=夜晚（森林）
 */
public record ColonyAmbientPacket(boolean playing, boolean day) implements CustomPacketPayload {

    public static final Type<ColonyAmbientPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, "colony_ambient"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ColonyAmbientPacket> STREAM_CODEC =
            StreamCodec.of(ColonyAmbientPacket::write, ColonyAmbientPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Client handler ──

    private static Consumer<ColonyAmbientPacket> clientHandler;

    public static void setClientHandler(Consumer<ColonyAmbientPacket> handler) {
        clientHandler = handler;
    }

    public static void handleClient(ColonyAmbientPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        }
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, ColonyAmbientPacket pkt) {
        buf.writeBoolean(pkt.playing);
        buf.writeBoolean(pkt.day);
    }

    static ColonyAmbientPacket read(RegistryFriendlyByteBuf buf) {
        return new ColonyAmbientPacket(buf.readBoolean(), buf.readBoolean());
    }
}
