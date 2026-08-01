package com.wsteam.wandscape.shared.network;

import java.util.UUID;

import com.wsteam.wandscape.magic.client.MagicCircleEmitter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * 服务端→客户端：通知客户端开始渲染一座魔法阵。
 *
 * @param effectId 本次施放唯一 id（emitter 用它管理生命周期）
 * @param pos      法阵中心世界坐标
 * @param axis     法阵平面法线（法杖朝向），覆盖 spec 元素 axis，使法阵垂直于法杖
 * @param circleId 魔法阵 spec id（data/wandscape/magic_circles/）
 */
public record MagicCircleCastPacket(UUID effectId, Vec3 pos, Vec3 axis, String circleId)
        implements CustomPacketPayload {

    public static final Type<MagicCircleCastPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "magic_circle_cast"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MagicCircleCastPacket> STREAM_CODEC =
            StreamCodec.of(MagicCircleCastPacket::write, MagicCircleCastPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(MagicCircleCastPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level instanceof ClientLevel cl) {
            MagicCircleEmitter.add(cl, packet.effectId(), packet.pos(), packet.axis(), packet.circleId());
        }
    }

    static void write(RegistryFriendlyByteBuf buf, MagicCircleCastPacket pkt) {
        buf.writeUUID(pkt.effectId());
        buf.writeDouble(pkt.pos().x);
        buf.writeDouble(pkt.pos().y);
        buf.writeDouble(pkt.pos().z);
        buf.writeDouble(pkt.axis().x);
        buf.writeDouble(pkt.axis().y);
        buf.writeDouble(pkt.axis().z);
        buf.writeUtf(pkt.circleId());
    }

    static MagicCircleCastPacket read(RegistryFriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        Vec3 pos = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        Vec3 axis = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        return new MagicCircleCastPacket(id, pos, axis, buf.readUtf());
    }
}
