package com.wsteam.wandscape.projection.network;

import java.util.List;

import com.wsteam.wandscape.projection.data.BuildingSlot;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import static com.wsteam.wandscape.Wandscape.MODID;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Client→Server: Player requests entry into soul projection mode.
 * Server validates (wand, colony), builds building slot list,
 * and replies with {@link ProjectionEnterResponsePacket}.
 */
public record ProjectionEnterPacket() implements CustomPacketPayload {

    private static final String TAG = "ProjectionEnterPacket";

    public static final Type<ProjectionEnterPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "projection_enter"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProjectionEnterPacket> STREAM_CODEC =
            StreamCodec.of(ProjectionEnterPacket::write, ProjectionEnterPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── Server handler ──

    public static void handleServer(ProjectionEnterPacket packet, ServerPlayer player) {
        // Validate
        String error = ProjectionNetwork.validateEntry(player);
        if (error != null) {
            player.displayClientMessage(Component.literal("[Projection] " + error), false);
            return;
        }

        // Already projecting? Toggle off.
        if (ProjectionNetwork.isProjecting(player)) {
            ProjectionNetwork.removeProjecting(player);
            player.displayClientMessage(Component.literal("[Projection] Exited projection mode"), false);
            // Restore abilities — player should return to anchor at last known body pos
            // The client handles the teleport + ability restore on receive of denied response
            var deny = new ProjectionEnterResponsePacket(false, List.of(), BlockPos.ZERO);
            sendResponse(player, deny);
            return;
        }

        // Grant entry
        ProjectionNetwork.addProjecting(player);
        List<BuildingSlot> slots = ProjectionNetwork.getAvailableBuildings();
        BlockPos bodyAnchor = player.blockPosition();

        Log.info(TAG, "[Projection] Granting entry to {}: {} buildings available, body at {}",
                player.getGameProfile().getName(), slots.size(), bodyAnchor);

        var response = new ProjectionEnterResponsePacket(true, slots, bodyAnchor);
        sendResponse(player, response);
    }

    private static void sendResponse(ServerPlayer player, ProjectionEnterResponsePacket response) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, response);
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, ProjectionEnterPacket pkt) {
        // Empty payload
    }

    static ProjectionEnterPacket read(RegistryFriendlyByteBuf buf) {
        return new ProjectionEnterPacket();
    }
}
