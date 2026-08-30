package com.wsteam.wandscape.shared.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.function.Consumer;

import static com.wsteam.wandscape.Wandscape.MODID;
/**
 * Server→Client: transient action feedback for the player.
 *
 * <p>The client shows it where the player is looking: if a {@code MedievalScreen}
 * is open it is drawn as a toast on that screen, otherwise it falls back to the
 * action bar. Used instead of chat so normal gameplay feedback never spams chat.
 * The {@link Component} is translatable and resolves in the client's locale.
 */
public record ScreenFeedbackPacket(Component message, boolean isError)
        implements CustomPacketPayload {

    public static final Type<ScreenFeedbackPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "screen_feedback"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenFeedbackPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ComponentSerialization.STREAM_CODEC, ScreenFeedbackPacket::message,
                    ByteBufCodecs.BOOL, ScreenFeedbackPacket::isError,
                    ScreenFeedbackPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Server helper: send transient feedback to a player (screen toast or action bar). */
    public static void send(ServerPlayer player, Component message, boolean isError) {
        if (player != null && !player.isRemoved()) {
            PacketDistributor.sendToPlayer(player, new ScreenFeedbackPacket(message, isError));
        }
    }

    // ── Client handler (injected by WandscapeClient) ──

    private static Consumer<ScreenFeedbackPacket> clientHandler;

    public static void setClientHandler(Consumer<ScreenFeedbackPacket> handler) {
        clientHandler = handler;
    }

    public static void handleClient(ScreenFeedbackPacket packet) {
        if (clientHandler != null) {
            clientHandler.accept(packet);
        }
    }
}
