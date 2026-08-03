package com.wsteam.wandscape.shared.network;

import com.wsteam.wandscape.magic.client.MagicCircleDotParticle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * 服务端→客户端：通知客户端在某处撒一批可染色运动粒子（爆花/光柱/星光）。
 *
 * <p>原版 {@code SimpleParticleType} 无法携带颜色，染色特效统一走本包 +
 * {@link MagicCircleDotParticle#spawnMoving}。vertical=true 表示竖直光柱
 * （奇观金柱），false 表示球形爆花（烟花/满意度星光/杖尖爆闪）。
 */
public record ParticleBurstPacket(Vec3 pos, float r, float g, float b,
                                  int count, float size, int lifetime,
                                  boolean vertical) implements CustomPacketPayload {

    public static final Type<ParticleBurstPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "particle_burst"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ParticleBurstPacket> STREAM_CODEC =
            StreamCodec.of(ParticleBurstPacket::write, ParticleBurstPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(ParticleBurstPacket packet) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.level instanceof ClientLevel cl)) return;
        RandomSource rand = cl.random;
        for (int i = 0; i < packet.count(); i++) {
            double ox, oy, oz, vx, vy, vz;
            if (packet.vertical()) {
                // 竖直光柱：中心 ±0.3 内、高 3.5 内随机，缓慢上升
                ox = (rand.nextDouble() - 0.5) * 0.6;
                oy = rand.nextDouble() * 3.5;
                oz = (rand.nextDouble() - 0.5) * 0.6;
                vx = 0;
                vy = 0.03 + rand.nextDouble() * 0.05;
                vz = 0;
            } else {
                // 球形爆花：球面随机方向速度 + 轻微向上偏移
                double theta = rand.nextDouble() * Math.PI * 2;
                double phi = Math.acos(2 * rand.nextDouble() - 1);
                double sp = 0.1 + rand.nextDouble() * 0.3;
                ox = (rand.nextDouble() - 0.5) * 0.4;
                oy = (rand.nextDouble() - 0.5) * 0.4;
                oz = (rand.nextDouble() - 0.5) * 0.4;
                vx = Math.sin(phi) * Math.cos(theta) * sp;
                vy = Math.cos(phi) * sp * 0.5 + 0.03;
                vz = Math.sin(phi) * Math.sin(theta) * sp;
            }
            MagicCircleDotParticle.spawnMoving(cl, packet.pos().x + ox, packet.pos().y + oy, packet.pos().z + oz,
                    vx, vy, vz, packet.r(), packet.g(), packet.b(),
                    packet.size(), 0.9f, packet.lifetime());
        }
    }

    static void write(RegistryFriendlyByteBuf buf, ParticleBurstPacket pkt) {
        buf.writeDouble(pkt.pos().x);
        buf.writeDouble(pkt.pos().y);
        buf.writeDouble(pkt.pos().z);
        buf.writeFloat(pkt.r());
        buf.writeFloat(pkt.g());
        buf.writeFloat(pkt.b());
        buf.writeVarInt(pkt.count());
        buf.writeFloat(pkt.size());
        buf.writeVarInt(pkt.lifetime());
        buf.writeBoolean(pkt.vertical());
    }

    static ParticleBurstPacket read(RegistryFriendlyByteBuf buf) {
        Vec3 pos = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        float r = buf.readFloat();
        float g = buf.readFloat();
        float b = buf.readFloat();
        int count = buf.readVarInt();
        float size = buf.readFloat();
        int lifetime = buf.readVarInt();
        boolean vertical = buf.readBoolean();
        return new ParticleBurstPacket(pos, r, g, b, count, size, lifetime, vertical);
    }
}
