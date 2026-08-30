package com.wsteam.wandscape.shared.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Consumer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * 魔法阵生成包。{@code casterUuid} 可选：非空时客户端法阵每 tick 跟随该实体位置
 * （脚下法阵随 NPC 走位移动）；为空则静态法阵（光束/仪式）。
 */
public record MagicCircleCastPacket(UUID effectId, Vec3 pos, Vec3 axis, String circleId,
                                    @Nullable UUID casterUuid)
        implements CustomPacketPayload {

    public static final Type<MagicCircleCastPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "magic_circle_cast"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MagicCircleCastPacket> STREAM_CODEC =
            StreamCodec.of(MagicCircleCastPacket::write, MagicCircleCastPacket::read);

    /** 静态法阵（不跟随实体）：光束/仪式用。 */
    public MagicCircleCastPacket(UUID effectId, Vec3 pos, Vec3 axis, String circleId) {
        this(effectId, pos, axis, circleId, null);
    }

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
        buf.writeBoolean(pkt.casterUuid() != null);
        if (pkt.casterUuid() != null) {
            buf.writeUUID(pkt.casterUuid());
        }
    }

    static MagicCircleCastPacket read(RegistryFriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        Vec3 pos = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        Vec3 axis = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        String circleId = buf.readUtf();
        UUID caster = buf.readBoolean() ? buf.readUUID() : null;
        return new MagicCircleCastPacket(id, pos, axis, circleId, caster);
    }
}
