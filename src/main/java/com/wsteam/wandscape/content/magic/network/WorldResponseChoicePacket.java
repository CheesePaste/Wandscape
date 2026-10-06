package com.wsteam.wandscape.content.magic.network;

import com.wsteam.wandscape.content.magic.worldresponse.WorldResponseManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server：玩家在《世界应答》轮盘上选定的回应；{@code responseId} 为空串表示取消（关掉轮盘）。
 *
 * <p>只是「我的选择」——能不能生效由 {@link WorldResponseManager} 按 pending/冷却/合法性判定，
 * 客户端不持有任何权威状态。
 */
public record WorldResponseChoicePacket(String responseId) implements CustomPacketPayload {

    public static final Type<WorldResponseChoicePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "world_response_choice"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WorldResponseChoicePacket> STREAM_CODEC =
            StreamCodec.of(WorldResponseChoicePacket::write, WorldResponseChoicePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(WorldResponseChoicePacket packet, ServerPlayer player) {
        player.getServer().execute(() -> WorldResponseManager.choose(player, packet.responseId()));
    }

    static void write(RegistryFriendlyByteBuf buf, WorldResponseChoicePacket pkt) {
        buf.writeUtf(pkt.responseId == null ? "" : pkt.responseId);
    }

    static WorldResponseChoicePacket read(RegistryFriendlyByteBuf buf) {
        return new WorldResponseChoicePacket(buf.readUtf());
    }
}
