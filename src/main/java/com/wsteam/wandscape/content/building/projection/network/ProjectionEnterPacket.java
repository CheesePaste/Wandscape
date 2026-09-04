package com.wsteam.wandscape.content.building.projection.network;
import com.wsteam.wandscape.content.colony.network.ColonyStatsSyncPacket;

import com.wsteam.wandscape.content.building.projection.data.BuildingSlot;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

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
            player.displayClientMessage(I18n.name("message.wandscape.projection.enter_failed",
                    "[Projection] %s", error), false);
            return;
        }

        // Already projecting? Toggle off.
        if (ProjectionNetwork.isProjecting(player)) {
            ProjectionNetwork.removeProjecting(player);
            // Restore abilities — player should return to anchor at last known body pos
            // The client handles the teleport + ability restore on receive of denied response
            var deny = new ProjectionEnterResponsePacket(false, List.of(), BlockPos.ZERO);
            sendResponse(player, deny);
            return;
        }

        // Grant entry
        ProjectionNetwork.addProjecting(player);
        UUID colonyId = null;
        var colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi != null) {
            // 完全平行隔离：投影/建筑槽永远只关联玩家自己的小镇；无镇则空（建镇引导态）。
            colonyId = colonyApi.getColonyByFounder(player.getUUID());
        }
        if (colonyId != null) {
            var metricsApi = WandscapeApis.getColonyStatusApiSilently();
            if (metricsApi != null) {
                var snap = metricsApi.getSnapshot(colonyId);
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        com.wsteam.wandscape.content.colony.network.ColonyStatsSyncPacket.fromSnapshot(snap));
            }
        }
        List<BuildingSlot> slots = ProjectionNetwork.getAvailableBuildings(colonyId);
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
