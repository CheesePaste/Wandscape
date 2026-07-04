package com.wsteam.wandscape.building.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.data.BuildingConfig.NodeConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.api.WarehouseApi;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.event.MaintenanceForecastWarningEvent;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Periodic forecast system that monitors element reserves against upcoming
 * maintenance costs. When reserves fall below the configured threshold, it
 * automatically enqueues high-priority gather tasks on node buildings.
 *
 * <p>This prevents unexpected shutdowns by proactively building up reserves
 * before the daily settlement runs.
 */
public final class MaintenanceForecastSystem {
    private static final String TAG = "MaintenanceForecastSystem";

    /** Priority for forecast-triggered gather tasks (higher than normal node supply at 15). */
    private static final int FORECAST_GATHER_PRIORITY = 55;

    private int tickCounter;

    private MaintenanceForecastSystem() {}

    public static MaintenanceForecastSystem register() {
        MaintenanceForecastSystem system = new MaintenanceForecastSystem();
        NeoForge.EVENT_BUS.register(system);
        Log.info(TAG, "MaintenanceForecastSystem registered");
        return system;
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel level = server.overworld();
        if (level == null) return;

        tickCounter++;
        int interval = Config.FORECAST_INTERVAL_TICKS.get();
        if (tickCounter % interval != 0) return;

        doForecast(level);
    }

    private void doForecast(ServerLevel level) {
        BuildingSavedData savedData = BuildingSavedData.get(level);
        if (savedData == null) return;

        BuildingApi buildingApi;
        WarehouseApi warehouseApi;
        try {
            buildingApi = WandscapeApis.getBuildingApi();
            warehouseApi = WandscapeApis.getWarehouseApi();
        } catch (IllegalStateException e) {
            return;
        }

        BuildingConfigLoader configLoader = BuildingConfigLoader.getInstance();
        int reserveDays = Config.MAINTENANCE_RESERVE_DAYS.get();

        // Group non-shutdown buildings by colony and calculate daily cost
        Map<UUID, Map<ElementType, Long>> colonyDailyCost = new HashMap<>();
        Map<UUID, List<BuildingState>> colonyBuildings = new HashMap<>();

        for (BuildingState state : savedData.getAllBuildings()) {
            if (state.isShutdown()) continue;
            UUID colonyId = state.getColonyId();
            if (colonyId == null) continue;

            colonyBuildings.computeIfAbsent(colonyId, k -> new ArrayList<>()).add(state);

            var cost = state.getMaintenanceCost().costs();
            if (cost.isEmpty()) continue;

            var dailyMap = colonyDailyCost.computeIfAbsent(colonyId, k -> new HashMap<>());
            for (var entry : cost.entrySet()) {
                // Costs are daily values now — each settlement deducts once per day.
                dailyMap.merge(entry.getKey(), (long) entry.getValue(), Long::sum);
            }
        }

        // For each colony, check reserves
        for (var entry : colonyDailyCost.entrySet()) {
            UUID colonyId = entry.getKey();
            Map<ElementType, Long> dailyCost = entry.getValue();

            if (dailyCost.isEmpty()) continue;

            Map<ElementType, Long> shortfall = new HashMap<>();

            for (var costEntry : dailyCost.entrySet()) {
                ElementType element = costEntry.getKey();
                long needed = costEntry.getValue() * reserveDays;
                long available = warehouseApi.getElement(colonyId, element);
                if (available < needed) {
                    shortfall.put(element, needed - available);
                }
            }

            if (shortfall.isEmpty()) continue;

            Log.info(TAG, "[Forecast] Colony {} element shortfall: {} (reserves below {} days)",
                    colonyId.toString().substring(0, 8), shortfall, reserveDays);

            // Enqueue gather tasks on node buildings for short elements
            enqueueGatherTasks(buildingApi, configLoader, colonyId, shortfall);

            // Fire warning event for other systems (UI, debug, etc.)
            NeoForge.EVENT_BUS.post(new MaintenanceForecastWarningEvent(
                    colonyId, Map.copyOf(shortfall), Map.copyOf(dailyCost)));
        }
    }

    /**
     * Find idle node buildings that produce the short elements and enqueue
     * high-priority gather tasks.
     */
    private void enqueueGatherTasks(BuildingApi buildingApi, BuildingConfigLoader configLoader,
                                     UUID colonyId, Map<ElementType, Long> shortfall) {
        List<UUID> nodeBuildings = buildingApi.getBuildingsByCategory(colonyId, "node");

        for (UUID buildingId : nodeBuildings) {
            var bd = buildingApi.getBuilding(buildingId);
            if (bd == null || bd.isShutdown() || !bd.isStructureIntact()) continue;

            BuildingConfig config = configLoader.get(bd.getBuildingTypeId());
            if (config == null) continue;

            NodeConfig nodeConfig = config.nodeConfig();
            if (nodeConfig == null) continue;

            // Check if this node produces an element that is in shortfall
            ElementType producedElement;
            try {
                producedElement = ElementType.valueOf(nodeConfig.element().toUpperCase());
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!shortfall.containsKey(producedElement)) continue;

            // Check if building is truly idle (no current task, empty queue)
            if (buildingApi.isBuildingOccupied(buildingId)) continue;
            if (!buildingApi.getQueue(buildingId).isEmpty()) continue;

            // Build WorkItem (mirrors BuildingTaskSource.supplyNodeBuildings)
            Map<String, JsonElement> params = new LinkedHashMap<>();
            BlockPos pos = bd.getPosition();
            params.put("anchor", posToJsonArray(pos));
            params.put("element", new JsonPrimitive(nodeConfig.element()));
            params.put("amount", new JsonPrimitive(nodeConfig.amountPerHarvest()));
            params.put("channel_ticks", new JsonPrimitive(nodeConfig.channelTicks()));
            params.put("mana_cost", new JsonPrimitive(nodeConfig.manaCost()));

            WorkItem work = new WorkItem(nodeConfig.blueprint(), params,
                    FORECAST_GATHER_PRIORITY);
            buildingApi.enqueueWork(buildingId, work);

            Log.info(TAG, "[Forecast] Enqueued high-priority gather on {} for {} (shortfall: {})",
                    buildingId.toString().substring(0, 8), producedElement, shortfall.get(producedElement));
        }
    }

    private static com.google.gson.JsonArray posToJsonArray(BlockPos pos) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        arr.add(pos.getX());
        arr.add(pos.getY());
        arr.add(pos.getZ());
        return arr;
    }
}
