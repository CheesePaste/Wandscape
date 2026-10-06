package com.wsteam.wandscape.content.magic.network;

import com.wsteam.wandscape.foundation.networking.ClientPayloadDispatcher;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Server→Client：请玩家在《世界应答》的回应里挑一个（打开轮盘）。
 *
 * <p>回应的 id 由服务端下发而不是客户端写死——服务端永远只列出它当下允许的回应，
 * 将来做解锁/条件门控（某回应要仪式完成后才出现）时不用改客户端。
 */
public record WorldResponseOpenPacket(List<String> responseIds) implements CustomPacketPayload {

    public static final Type<WorldResponseOpenPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "world_response_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WorldResponseOpenPacket> STREAM_CODEC =
            StreamCodec.of(WorldResponseOpenPacket::write, WorldResponseOpenPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(WorldResponseOpenPacket packet) {
        ClientPayloadDispatcher.dispatch(packet);
    }

    static void write(RegistryFriendlyByteBuf buf, WorldResponseOpenPacket pkt) {
        buf.writeVarInt(pkt.responseIds.size());
        for (String id : pkt.responseIds) {
            buf.writeUtf(id);
        }
    }

    static WorldResponseOpenPacket read(RegistryFriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<String> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add(buf.readUtf());
        }
        return new WorldResponseOpenPacket(ids);
    }
}
