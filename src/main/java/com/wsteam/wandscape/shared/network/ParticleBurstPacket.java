package com.wsteam.wandscape.shared.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

import static com.wsteam.wandscape.Wandscape.MODID;

public record ParticleBurstPacket(Vec3 pos, float r, float g, float b,
                                  int count, float size, int lifetime,
                                  boolean vertical) implements CustomPacketPayload {

    public static final Type<ParticleBurstPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "particle_burst"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ParticleBurstPacket> STREAM_CODEC =
            StreamCodec.of(ParticleBurstPacket::write, ParticleBurstPacket::read);

    private static Consumer<ParticleBurstPacket> clientHandler = packet -> {};
    public static void setClientHandler(Consumer<ParticleBurstPacket> handler) { clientHandler = handler; }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(ParticleBurstPacket packet) {
        clientHandler.accept(packet);
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
