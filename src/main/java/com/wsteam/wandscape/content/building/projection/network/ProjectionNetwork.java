package com.wsteam.wandscape.content.building.projection.network;

import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.content.building.projection.data.BuildingSlot;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.api.WandscapeApis;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Manages the set of players currently in soul projection mode.
 * Mirrors {@code RoadEditorNetwork} pattern exactly.
 */
public final class ProjectionNetwork {

    private static final String TAG = "ProjectionNetwork";

    /** Server-side set of projecting player UUIDs. */
    private static final Set<UUID> projectingPlayers =
            Collections.synchronizedSet(new HashSet<>());

    private ProjectionNetwork() {}

    static {
        try {
            Log.info(TAG, "[Init] Loading ProjectionNetwork class...");
            // Force-resolve key dependencies to catch linkage errors early
            Class.forName("com.wsteam.wandscape.content.building.internal.BuildingConfigLoader");
            Class.forName("com.wsteam.wandscape.api.WandscapeApis");
            Log.info(TAG, "[Init] Dependencies resolved successfully");
        } catch (Throwable t) {
            try {
                Log.error(TAG, "[Init] FAILED to resolve dependency", t);
            } catch (Throwable logFallback) {
                System.err.println("[ProjectionNetwork] FATAL init error (Log unavailable): " + t);
                t.printStackTrace();
            }
            throw new ExceptionInInitializerError(t);
        }
    }

    // ── Player tracking ──

    public static void addProjecting(ServerPlayer player) {
        projectingPlayers.add(player.getUUID());
        Log.info(TAG, "[Projection] Player {} entered projection mode. Total: {}",
                player.getGameProfile().getName(), projectingPlayers.size());
    }

    public static void removeProjecting(ServerPlayer player) {
        projectingPlayers.remove(player.getUUID());
        Log.info(TAG, "[Projection] Player {} exited projection mode. Total: {}",
                player.getGameProfile().getName(), projectingPlayers.size());
    }

    public static boolean isProjecting(ServerPlayer player) {
        return projectingPlayers.contains(player.getUUID());
    }

    /** Remove a player by UUID (for disconnect cleanup). */
    public static void removeByUuid(UUID playerId) {
        projectingPlayers.remove(playerId);
        Log.info(TAG, "[Projection] Removed disconnected player. Total: {}",
                projectingPlayers.size());
    }

    private static int categoryPriority(String category) {
        if (category == null) return 10;
        return switch (category) {
            case "government" -> 0;
            case "storage" -> 1;
            case "service" -> 2;
            case "shop" -> 3;
            case "relax" -> 3;
            case "atm" -> 3;
            case "workstation" -> 4;
            case "crafting_station" -> 5;
            case "node" -> 6;
            default -> 10;
        };
    }

    /**
     * Build the list of available buildings for projection placement.
     * Reads from {@link BuildingConfigLoader} — returns all configs with a blueprint,
     * sorted by category priority (government first) then display name.
     * Each slot carries whether the colony's first-free build is still available
     * (config {@code first_free: true} and not yet claimed for {@code colonyId}).
     *
     * @param colonyId the colony to check claim state against; may be null when no colony
     *                 resolves — then no building is marked first-free.
     */
    public static List<BuildingSlot> getAvailableBuildings(@Nullable UUID colonyId) {
        var configs = BuildingConfigLoader.getInstance().getAll();
        if (configs == null || configs.isEmpty()) {
            return List.of();
        }
        var buildingApi = WandscapeApis.getBuildingApiSilently();
        return configs.values().stream()
                .filter(c -> c.blueprint() != null) // only buildings with a build blueprint
                .filter(c -> !c.deprecated()) // deprecated buildings stay functional but are hidden from placement
                .map(c -> {
                    boolean isGov = "government".equals(c.category());
                    boolean firstFreeAvailable;
                    if (colonyId == null) {
                        firstFreeAvailable = isGov && c.firstFree();
                    } else {
                        firstFreeAvailable = !isGov && c.firstFree()
                                && buildingApi != null
                                && !buildingApi.isFirstFreeClaimed(colonyId, c.id());
                    }
                    return new BuildingSlot(c.id(), c.displayName(), c.category(), firstFreeAvailable);
                })
                .sorted(java.util.Comparator.comparingInt((BuildingSlot s) -> categoryPriority(s.category()))
                        .thenComparing(BuildingSlot::displayName))
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
