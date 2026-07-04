package com.wsteam.wandscape.road.network;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.wsteam.wandscape.road.core.RoadNetwork;
import com.wsteam.wandscape.road.engine.RoadSavedData;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Manages the set of players currently in road edit mode
 * and handles syncing network state to them.
 */
public final class RoadEditorNetwork {

    private static final String TAG = "RoadEditorNetwork";

    /** Server-side set of editing player UUIDs. */
    private static final Set<UUID> editingPlayers =
            Collections.synchronizedSet(new HashSet<>());

    private RoadEditorNetwork() {}

    // ── Player tracking ──

    public static void addEditing(ServerPlayer player) {
        editingPlayers.add(player.getUUID());
    }

    public static void removeEditing(ServerPlayer player) {
        editingPlayers.remove(player.getUUID());
    }

    public static boolean isEditing(ServerPlayer player) {
        return editingPlayers.contains(player.getUUID());
    }

    /** Remove a player by UUID (for disconnect cleanup). */
    public static void removeByUuid(UUID playerId) {
        editingPlayers.remove(playerId);
    }

    // ── Sync ──

    /**
     * Send the current road network to a specific player.
     * Used when a player first enters edit mode.
     */
    public static void sendSyncToPlayer(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        RoadSavedData roadData = RoadSavedData.getOrCreate(level);
        RoadNetwork network = roadData.getNetwork();
        RoadNetworkSyncPacket packet = RoadNetworkSyncPacket.from(
                network, roadData.getColonyId());
        Log.info(TAG, "[RoadEditor] sendSyncToPlayer: player={} nodes={} edges={} colonyId={}",
                player.getGameProfile().getName(), network.nodeCount(),
                network.edgeCount(), roadData.getColonyId());
        PacketDistributor.sendToPlayer(player, packet);
    }

    /**
     * Send the current road network to ALL players in edit mode.
     * Used after edge removal or other modifications.
     */
    public static void sendSyncToEditing(MinecraftServer server) {
        if (server == null) return;
        ServerLevel level = server.overworld();
        RoadSavedData roadData = RoadSavedData.getOrCreate(level);
        RoadNetwork network = roadData.getNetwork();
        RoadNetworkSyncPacket packet = RoadNetworkSyncPacket.from(
                network, roadData.getColonyId());

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (editingPlayers.contains(player.getUUID())) {
                PacketDistributor.sendToPlayer(player, packet);
            }
        }
    }

    /**
     * Send exit-edit packet to a specific player.
     */
    public static void sendExitToPlayer(ServerPlayer player) {
        Log.info(TAG, "[RoadEditor] sendExitToPlayer: player={}", player.getGameProfile().getName());
        PacketDistributor.sendToPlayer(player, RoadNetworkSyncPacket.exitPacket());
    }
}
