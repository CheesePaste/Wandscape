package com.wsteam.wandscape.shared.network;

import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import static com.wsteam.wandscape.Wandscape.MODID;

public record MagicCircleCastPacket(UUID effectId, Vec3 pos, Vec3 axis, String circleId)
        implements CustomPacketPayload {

    public static final Type<MagicCircleCastPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "magic_circle_cast"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MagicCircleCastPacket> STREAM_CODEC =
            StreamCodec.of(MagicCircleCastPacket::write, MagicCircleCastPacket::read);

    private static Consumer<MagicCircleCastPacket> clientHandler = packet -> {};
    public static void setClientHandler(Consumer<MagicCircleCastPacket> handler) { clientHandler = handler; }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(MagicCircleCastPacket packet) {
        clientHandler.accept(packet);
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
