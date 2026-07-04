package com.wsteam.wandscape.task.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.wsteam.wandscape.task.engine.dsl.BlueprintDefinition;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.shared.data.BlueprintInfo;
import com.wsteam.wandscape.shared.data.ParamTypeInfo;

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
        List<BlueprintInfo> blueprints = getAvailableBlueprints();
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

        List<BlueprintInfo> blueprints = getAvailableBlueprints();
        var packet = BlueprintListResponsePacket.from(blueprints);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, packet);
        }
    }

    private static List<BlueprintInfo> getAvailableBlueprints() {
        var loader = WandscapeEngine.getBlueprintConfigLoader();
        if (loader == null) return List.of();

        List<BlueprintInfo> result = new ArrayList<>();
        for (var entry : loader.getAll().entrySet()) {
            BlueprintDefinition def = entry.getValue();
            java.util.Map<String, ParamTypeInfo> paramInfos = new java.util.LinkedHashMap<>();
            if (def.params() != null) {
                for (var paramEntry : def.params().entrySet()) {
                    paramInfos.put(paramEntry.getKey(), ParamTypeInfo.fromCore(paramEntry.getValue()));
                }
            }
            String displayName = def.displayName() != null && !def.displayName().isEmpty()
                    ? def.displayName() : def.id();
            String description = def.description() != null ? def.description() : "";
            result.add(new BlueprintInfo(def.id(), displayName, description, paramInfos));
        }
        return Collections.unmodifiableList(result);
    }
}
