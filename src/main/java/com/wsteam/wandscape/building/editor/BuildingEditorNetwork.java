package com.wsteam.wandscape.building.editor;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.server.level.ServerPlayer;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Server-side player tracking for the building editor.
 * Mirrors {@code ProjectionNetwork} and {@code RoadEditorNetwork} patterns.
 */
public final class BuildingEditorNetwork {

    private static final String TAG = "BuildingEditorNetwork";

    /** Server-side set of players currently in building editor mode. */
    private static final Set<UUID> editingPlayers =
            Collections.synchronizedSet(new HashSet<>());

    private BuildingEditorNetwork() {}

    // ── Player tracking ──

    public static void addEditing(ServerPlayer player) {
        editingPlayers.add(player.getUUID());
        Log.info(TAG, "[BuildEditor] Player {} entered editor. Total: {}",
                player.getGameProfile().getName(), editingPlayers.size());
    }

    public static void removeEditing(ServerPlayer player) {
        editingPlayers.remove(player.getUUID());
        Log.info(TAG, "[BuildEditor] Player {} exited editor. Total: {}",
                player.getGameProfile().getName(), editingPlayers.size());
    }

    public static boolean isEditing(ServerPlayer player) {
        return editingPlayers.contains(player.getUUID());
    }

    /** Remove a player by UUID (for disconnect cleanup). */
    public static void removeByUuid(UUID playerId) {
        editingPlayers.remove(playerId);
        Log.info(TAG, "[BuildEditor] Removed disconnected player. Total: {}", editingPlayers.size());
    }

    /**
     * Load an existing building config by ID.
     * @return the config, or null if not found.
     */
    public static BuildingConfig loadBuildingConfig(String buildingId) {
        var configs = BuildingConfigLoader.getInstance().getAll();
        if (configs == null) return null;
        return configs.get(buildingId);
    }

    /**
     * Validate that a player can enter the building editor.
     * @return null if OK, or an error message string if not.
     */
    public static String validateEntry(ServerPlayer player) {
        var configs = BuildingConfigLoader.getInstance().getAll();
        if (configs == null || configs.isEmpty()) {
            return "No building configs loaded — check data/wandscape/buildings/";
        }
        var api = WandscapeApis.getBuildingApi();
        if (api == null) {
            return "Building API not available";
        }
        return null; // OK
    }
}
