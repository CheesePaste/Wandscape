package com.wsteam.wandscape.content.tutorial.network;

import com.wsteam.wandscape.content.tutorial.data.TutorialProgressSavedData;
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
public record TutorialProgressUpdatePacket(boolean dismissed) implements CustomPacketPayload {

    public static final Type<TutorialProgressUpdatePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "tutorial_progress_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TutorialProgressUpdatePacket> STREAM_CODEC =
            StreamCodec.of(TutorialProgressUpdatePacket::write, TutorialProgressUpdatePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(TutorialProgressUpdatePacket packet, ServerPlayer player) {
        TutorialProgressSavedData sd = TutorialProgressSavedData.get(player.serverLevel());
        TutorialProgressSavedData.TutorialProgress saved = sd.get(player.getUUID());
        sd.set(player.getUUID(), saved.stepIndex(), packet.dismissed());
    }

    static void write(RegistryFriendlyByteBuf buf, TutorialProgressUpdatePacket pkt) {
        buf.writeBoolean(pkt.dismissed);
    }

    static TutorialProgressUpdatePacket read(RegistryFriendlyByteBuf buf) {
        return new TutorialProgressUpdatePacket(buf.readBoolean());
    }
}
