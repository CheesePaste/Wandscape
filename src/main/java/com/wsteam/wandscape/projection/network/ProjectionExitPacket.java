package com.wsteam.wandscape.projection.network;

import com.wsteam.wandscape.shared.log.Log;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client→Server: Player exits soul projection mode.
 * Server removes the player from projecting tracking and restores abilities.
 */
public record ProjectionExitPacket() implements CustomPacketPayload {

    private static final String TAG = "ProjectionExitPacket";

    public static final Type<ProjectionExitPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "projection_exit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProjectionExitPacket> STREAM_CODEC =
            StreamCodec.of(ProjectionExitPacket::write, ProjectionExitPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Server handler ──

    public static void handleServer(ProjectionExitPacket packet, ServerPlayer player) {
        ProjectionNetwork.removeProjecting(player);
        Log.info(TAG, "[Projection] Player {} exited projection mode",
                player.getGameProfile().getName());
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, ProjectionExitPacket pkt) {
        // Empty payload
    }

    static ProjectionExitPacket read(RegistryFriendlyByteBuf buf) {
        return new ProjectionExitPacket();
    }
}
