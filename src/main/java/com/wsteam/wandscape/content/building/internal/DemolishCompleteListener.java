package com.wsteam.wandscape.content.building.internal;

import com.wsteam.wandscape.core.event.CustomEvent;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.service.ParticleService;
import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Map;

/**
 * Subscribes to the engine-internal {@code EventBus} for {@code demolish_complete} events.
 * When an NPC finishes demolishing a building's blocks, this listener
 * unregisters the building from the colony registry.
 */
public final class DemolishCompleteListener {
    private static final String TAG = "DemolishCompleteListener";

    private DemolishCompleteListener() {}

    /**
     * Register this listener on the engine event bus.
     * Call after engine bootstrap in {@code onServerStarting}.
     */
    public static void register() {
        var world = WandscapeEngine.getWorld();
        if (world == null || world.eventBus == null) {
            Log.warn(TAG, "Cannot register DemolishCompleteListener — engine not bootstrapped");
            return;
        }

        world.eventBus.subscribe(CustomEvent.class, DemolishCompleteListener::onDemolishComplete);
        Log.info(TAG, "DemolishCompleteListener registered on engine EventBus");
    }

    private static void onDemolishComplete(CustomEvent event) {
        if (!"demolish_complete".equals(event.name())) return;

        Map<String, String> params = event.params();
        String anchorStr = params.get("anchor");
        String buildingIdStr = params.get("building_id");

        if (anchorStr == null) {
            Log.warn(TAG, "demolish_complete event missing anchor");
            return;
        }

        BlockPos anchor = parseAnchor(anchorStr);
        if (anchor == null) return;

        Level level = getServerLevel();
        if (level == null) return;

        BuildingSavedData sd = BuildingSavedData.get(level);
        if (sd == null) return;

        BuildingState state = findByAnchor(sd, anchor);
        if (state == null) {
            return;
        }

        // Unregister the building from saved data
        var api = WandscapeApis.getBuildingApi();
        if (api != null) {
            api.unregisterBuilding(anchor);
        }

        // Notify colony system (cascade-deletes colony if this was a town hall)
        ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi != null) {
            colonyApi.onBuildingDestroyed(state);
        }

        // ── 拆除完成：建筑包围盒一圈灰烟 ──
        if (level instanceof ServerLevel srv) {
            ParticleService.burstRing(srv, ParticleTypes.LARGE_SMOKE, state.getBounds(), 40, 1.8, 0.06);
            ParticleService.burstRing(srv, ParticleTypes.CAMPFIRE_COSY_SMOKE, state.getBounds(), 20, 1.5, 0.08);
        }

        Log.info(TAG, "[Demolish] Building {} ({}) at {} — demolition complete, unregistered",
                state.getBuildingTypeId(),
                buildingIdStr != null ? buildingIdStr.substring(0, 8) : "?",
                anchor);
    }

    /** Parse "x,y,z" string into BlockPos. */
    private static BlockPos parseAnchor(String s) {
        String[] parts = s.split(",");
        if (parts.length != 3) return null;
        try {
            return new BlockPos(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            Log.warn(TAG, "Invalid anchor format: {}", s);
            return null;
        }
    }

    /** Find a building by anchor position. */
    private static BuildingState findByAnchor(BuildingSavedData data, BlockPos anchor) {
        for (BuildingState state : data.getAllBuildings()) {
            if (state.getAnchor().equals(anchor)) return state;
        }
        return null;
    }

    private static Level getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }
}
