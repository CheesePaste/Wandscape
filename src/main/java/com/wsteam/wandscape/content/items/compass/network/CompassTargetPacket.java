package com.wsteam.wandscape.compass.network;

import com.wsteam.wandscape.compass.client.CompassTargetClientCache;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

import static com.wsteam.wandscape.Wandscape.MODID;

/** Server→Client: 玩家自己殖民地的市政厅坐标（用作魔法指南针指向目标）。无市政厅时 hasTarget=false。 */
public record CompassTargetPacket(boolean hasTarget, @Nullable GlobalPos target) implements CustomPacketPayload {

    public static final Type<CompassTargetPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "compass_target"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CompassTargetPacket> STREAM_CODEC =
            StreamCodec.of(CompassTargetPacket::write, CompassTargetPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(CompassTargetPacket packet) {
        CompassTargetClientCache.set(packet.hasTarget ? packet.target : null);
    }

    private static void write(RegistryFriendlyByteBuf buf, CompassTargetPacket packet) {
        buf.writeBoolean(packet.hasTarget);
        if (packet.hasTarget && packet.target != null) {
            GlobalPos.STREAM_CODEC.encode(buf, packet.target);
        }
    }

    private static CompassTargetPacket read(RegistryFriendlyByteBuf buf) {
        boolean hasTarget = buf.readBoolean();
        GlobalPos target = hasTarget ? GlobalPos.STREAM_CODEC.decode(buf) : null;
        return new CompassTargetPacket(hasTarget, target);
    }
}
