package com.wsteam.wandscape.content.building.projection.network;
import com.wsteam.wandscape.content.colony.network.ColonyStatsSyncPacket;

import com.wsteam.wandscape.content.building.data.BuildingPackage;
import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.content.building.projection.data.BuildingSlot;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.colony.ownership.ColonyOwnership;
import com.wsteam.wandscape.foundation.networking.Net;
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
            var deny = new ProjectionEnterResponsePacket(false, List.of(), List.of(), BlockPos.ZERO);
            sendResponse(player, deny);
            return;
        }

        // Grant entry
        ProjectionNetwork.addProjecting(player);
        // 完全平行隔离：投影/建筑槽永远只关联玩家**当前操作的小镇**（可切换，不再等于
        // 「我创始的那座」）；无当前镇则空（建镇引导态），绝不按位置就近解析。
        UUID colonyId = ColonyOwnership.activeColony(player);
        if (colonyId != null) {
            var metricsApi = WandscapeApis.getColonyStatusApiSilently();
            if (metricsApi != null) {
                // 用 Safe 变体：快照异常绝不能冒泡出包 handler（禁崩溃）。但这里 colonyId 已知非空，
                // 所以失败拿到的 EMPTY（colonyId = null）不是「引导态空包」而是「取数失败」——
                // 照发会把客户端顶栏与面板当前镇清空，因此只在确实取到该镇快照时才发。
                var snap = metricsApi.getSnapshotSafe(colonyId);
                if (snap.colonyId() != null) {
                    Net.toPlayer(player,
                            com.wsteam.wandscape.content.colony.network.ColonyStatsSyncPacket.fromSnapshot(snap));
                }
            }
        }
        List<BuildingSlot> slots = ProjectionNetwork.getAvailableBuildings(colonyId);
        BlockPos bodyAnchor = player.blockPosition();

        Log.info(TAG, "[Projection] Granting entry to {}: {} buildings available, body at {}",
                player.getGameProfile().getName(), slots.size(), bodyAnchor);

        List<BuildingPackage> pkgs = BuildingConfigLoader.getInstance().getAllPackages();
        var response = new ProjectionEnterResponsePacket(true, slots, pkgs, bodyAnchor);
        sendResponse(player, response);
    }

    private static void sendResponse(ServerPlayer player, ProjectionEnterResponsePacket response) {
        Net.toPlayer(player, response);
    }

    // ── StreamCodec ──

    static void write(RegistryFriendlyByteBuf buf, ProjectionEnterPacket pkt) {
        // Empty payload
    }

    static ProjectionEnterPacket read(RegistryFriendlyByteBuf buf) {
        return new ProjectionEnterPacket();
    }
}
