package com.wsteam.wandscape.shared.network;

import com.wsteam.wandscape.shared.data.GuideProgressSavedData;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Player's tutorial progress changed (step advanced or guide
 * dismissed). Server persists it per-player in {@link GuideProgressSavedData}.
 */
public record GuideProgressUpdatePacket(int stepIndex, boolean dismissed) implements CustomPacketPayload {

    public static final Type<GuideProgressUpdatePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "guide_progress_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GuideProgressUpdatePacket> STREAM_CODEC =
            StreamCodec.of(GuideProgressUpdatePacket::write, GuideProgressUpdatePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(GuideProgressUpdatePacket packet, ServerPlayer player) {
        GuideProgressSavedData.get(player.serverLevel())
                .set(player.getUUID(), packet.stepIndex, packet.dismissed);
    }

    static void write(RegistryFriendlyByteBuf buf, GuideProgressUpdatePacket pkt) {
        buf.writeVarInt(pkt.stepIndex);
        buf.writeBoolean(pkt.dismissed);
    }

    static GuideProgressUpdatePacket read(RegistryFriendlyByteBuf buf) {
        return new GuideProgressUpdatePacket(buf.readVarInt(), buf.readBoolean());
    }
}
