package com.wsteam.wandscape.projection.network;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.projection.data.BuildingSlot;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.server.level.ServerPlayer;

/**
 * Manages the set of players currently in soul projection mode.
 * Mirrors {@code RoadEditorNetwork} pattern exactly.
 */
public final class ProjectionNetwork {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Server-side set of projecting player UUIDs. */
    private static final Set<UUID> projectingPlayers =
            Collections.synchronizedSet(new HashSet<>());

    private ProjectionNetwork() {}

    // ── Player tracking ──

    public static void addProjecting(ServerPlayer player) {
        projectingPlayers.add(player.getUUID());
        LOGGER.info("[Projection] Player {} entered projection mode. Total: {}",
                player.getGameProfile().getName(), projectingPlayers.size());
    }

    public static void removeProjecting(ServerPlayer player) {
        projectingPlayers.remove(player.getUUID());
        LOGGER.info("[Projection] Player {} exited projection mode. Total: {}",
                player.getGameProfile().getName(), projectingPlayers.size());
    }

    public static boolean isProjecting(ServerPlayer player) {
        return projectingPlayers.contains(player.getUUID());
    }

    /** Remove a player by UUID (for disconnect cleanup). */
    public static void removeByUuid(UUID playerId) {
        projectingPlayers.remove(playerId);
        LOGGER.info("[Projection] Removed disconnected player. Total: {}",
                projectingPlayers.size());
    }

    /**
     * Build the list of available buildings for projection placement.
     * Reads from {@link BuildingConfigLoader} — returns all configs with a blueprint.
     */
    public static List<BuildingSlot> getAvailableBuildings() {
        var configs = BuildingConfigLoader.getInstance().getAll();
        if (configs == null || configs.isEmpty()) {
            return List.of();
        }
        return configs.values().stream()
                .filter(c -> c.blueprint() != null) // only buildings with a build blueprint
                .map(c -> new BuildingSlot(c.id(), c.displayName(), c.category()))
                .toList();
    }

    /**
     * Validate that a player can enter projection mode.
     * @return null if OK, or an error message string if not
     */
    public static String validateEntry(ServerPlayer player) {
        // Check if building configs are loaded
        var configs = BuildingConfigLoader.getInstance().getAll();
        if (configs == null || configs.isEmpty()) {
            return "No building configs loaded — check data/wandscape/buildings/";
        }
        // Check colony exists (at least one registered building)
        var api = WandscapeApis.getBuildingApi();
        if (api == null) {
            return "Building API not available";
        }
        return null; // OK
    }
}
