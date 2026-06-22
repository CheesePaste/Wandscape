package com.wsteam.wandscape.task.network;

import java.util.List;

import com.wsteam.wandscape.shared.data.BlueprintInfo;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Static helper for task editor network communication.
 * Mirrors the pattern of {@link com.wsteam.wandscape.road.network.RoadEditorNetwork}.
 */
public final class TaskNetworkHandler {

    private TaskNetworkHandler() {}

    /**
     * Send the current list of available blueprints to a player.
     */
    public static void sendBlueprintList(ServerPlayer player) {
        var taskApi = WandscapeApis.getTaskApi();
        if (taskApi == null) {
            return;
        }

        List<BlueprintInfo> blueprints = taskApi.getAvailableBlueprints();
        var packet = BlueprintListResponsePacket.from(blueprints);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, packet);
    }

    /**
     * Send the blueprint list to all online players (e.g. after data reload).
     */
    public static void sendBlueprintListToAll() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        ServerLevel level = server.overworld();
        if (level == null) return;

        var taskApi = WandscapeApis.getTaskApi();
        if (taskApi == null) return;

        List<BlueprintInfo> blueprints = taskApi.getAvailableBlueprints();
        var packet = BlueprintListResponsePacket.from(blueprints);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, packet);
        }
    }
}
