package com.wsteam.wandscape.shared.network;

import com.wsteam.wandscape.shared.data.GuideProgressSavedData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: The player dismissed the tutorial guide. Step index is
 * computed server-side, so the client only reports dismissal; the saved step is
 * kept.
 */
public record GuideProgressUpdatePacket(boolean dismissed) implements CustomPacketPayload {

    public static final Type<GuideProgressUpdatePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "guide_progress_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GuideProgressUpdatePacket> STREAM_CODEC =
            StreamCodec.of(GuideProgressUpdatePacket::write, GuideProgressUpdatePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(GuideProgressUpdatePacket packet, ServerPlayer player) {
        GuideProgressSavedData sd = GuideProgressSavedData.get(player.serverLevel());
        GuideProgressSavedData.GuideProgress saved = sd.get(player.getUUID());
        sd.set(player.getUUID(), saved.stepIndex(), packet.dismissed());
    }

    static void write(RegistryFriendlyByteBuf buf, GuideProgressUpdatePacket pkt) {
        buf.writeBoolean(pkt.dismissed);
    }

    static GuideProgressUpdatePacket read(RegistryFriendlyByteBuf buf) {
        return new GuideProgressUpdatePacket(buf.readBoolean());
    }
}
