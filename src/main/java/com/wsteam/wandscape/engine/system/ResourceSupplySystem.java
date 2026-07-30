package com.wsteam.wandscape.engine.system;

import java.util.*;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import net.minecraft.core.BlockPos;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.data.BuildingConfig.NodeConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.core.ecs.System;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.production.ProductionRecipeLoader;
import com.wsteam.wandscape.production.data.SynthesizeRecipe;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.task.runtime.TaskState;

/**
 * Periodically scans {@link TaskState#AWAITING_RESOURCES} tasks and
 * orchestrates resource supply to unblock them.
 *
 * <p>Two actions per scan cycle:
 * <ol>
 *   <li>Wake up tasks whose resources are now available.</li>
 *   <li>For tasks still blocked: try synthesize first, then fall back to
 *       node gathering for raw elements.</li>
 * </ol>
 *
 * <p>Runs on a 40-tick heartbeat to balance responsiveness with overhead.
 * The event-driven {@code onResourceAdded} path handles immediate wake-up;
 * this system is the retry loop for cases where the initial shortage handler
 * could not create supply tasks (e.g. no crafting station was free at the time).
 */
public class ResourceSupplySystem implements System {

    private static final String TAG = "ResourceSupplySystem";
    private static final int HEARTBEAT = 40;

    private int tickCounter;

    @Override
    public void update(World world, float delta) {
        tickCounter++;
        if (tickCounter % HEARTBEAT != 0) return;
        scanStuckTasks(world);
    }

    private void scanStuckTasks(World world) {
        List<GlobalTask> waiting = world.taskPool.getByState(TaskState.AWAITING_RESOURCES);
        if (waiting.isEmpty()) return;

        for (GlobalTask task : waiting) {
            if (task.awaitingResource == null || task.awaitingResource.isEmpty()) continue;

            boolean allAvailable = true;
            for (ResourceStack need : task.awaitingResource) {
                if (world.colonyResources.available(need.resource()) < need.amount()) {
                    allAvailable = false;
                    break;
                }
            }

            if (allAvailable) {
                world.taskPool.wakeupTask(task.id);
                continue;
            }

            for (ResourceStack need : task.awaitingResource) {
                int available = world.colonyResources.available(need.resource());
                if (available >= need.amount()) continue;
                trySupplyResource(need.resource(), need.amount() - available, world);
            }
        }
    }

    /**
     * Try to create a supply task for the given resource shortfall.
     * Prefers synthesize (elements → items) over raw node gathering.
     */
    private void trySupplyResource(ResourceId resource, int deficit, World world) {
        ProductionRecipeLoader recipes = Wandscape.PRODUCTION_RECIPE_LOADER;
        if (recipes != null) {
            String recipeKey = stripMcPrefix(resource.id());
            SynthesizeRecipe recipe = recipes.getSynthesizeRecipe(recipeKey);
            if (recipe != null && !isSynthesizeInFlight(recipeKey, world)) {
                BuildingApi api = getBuildingApi();
                if (api != null) {
                    List<UUID> stations = api.getBuildingsByCategory(null, "crafting_station");
                    if (!stations.isEmpty()) {
                        UUID stationId = stations.get(0);
                        BuildingData building = api.getBuilding(stationId);
                        if (building != null) {
                            enqueueSynthesize(api, stationId, building.getPosition(), recipeKey, deficit);
                            return;
                        }
                    }
                }
            }
        }

        tryGatherElement(resource, deficit);
    }

    private void enqueueSynthesize(BuildingApi api, UUID stationId, BlockPos pos,
                                   String recipeKey, int amount) {
        Map<String, JsonElement> params = new LinkedHashMap<>();
        params.put("anchor", posToJsonArray(pos));
        params.put("recipe_id", new JsonPrimitive(recipeKey));
        params.put("count", new JsonPrimitive(Math.max(amount, 1)));
        params.put("channel_ticks", new JsonPrimitive(200));
        params.put("mana_cost", new JsonPrimitive(5));

        api.enqueueWork(stationId, new WorkItem("production:synthesize", params, 40));
        Log.info(TAG, "shortfall {} x{} → synthesize:{} at station {}",
                recipeKey, amount, recipeKey, stationId.toString().substring(0, 8));
    }

    private void tryGatherElement(ResourceId resource, int deficit) {
        ElementType element;
        try {
            element = ElementType.valueOf(resource.id().toUpperCase());
        } catch (IllegalArgumentException e) {
            return;
        }

        BuildingApi api = getBuildingApi();
        if (api == null) return;

        BuildingConfigLoader configLoader = BuildingConfigLoader.getInstance();
        List<UUID> nodeBuildings = api.getBuildingsByCategory(null, "node");

        for (UUID buildingId : nodeBuildings) {
            if (api.isBuildingOccupied(buildingId)) continue;
            if (!api.getQueue(buildingId).isEmpty()) continue;

            BuildingData bd = api.getBuilding(buildingId);
            if (bd == null || bd.isShutdown() || !bd.isStructureIntact()) continue;

            BuildingConfig config = configLoader.get(bd.getBuildingTypeId());
            if (config == null) continue;

            NodeConfig nodeConfig = config.nodeConfig();
            if (nodeConfig == null) continue;

            ElementType produced;
            try {
                produced = ElementType.valueOf(nodeConfig.element().toUpperCase());
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (produced != element) continue;

            BlockPos pos = bd.getPosition();
            Map<String, JsonElement> params = new LinkedHashMap<>();
            params.put("anchor", posToJsonArray(pos));
            params.put("element", new JsonPrimitive(nodeConfig.element()));
            params.put("amount", new JsonPrimitive(nodeConfig.amountPerHarvest()));
            params.put("channel_ticks", new JsonPrimitive(nodeConfig.channelTicks()));
            params.put("mana_cost", new JsonPrimitive(nodeConfig.manaCost()));

            api.enqueueWork(buildingId, new WorkItem(nodeConfig.blueprint(), params, 40));
            Log.info(TAG, "shortfall {} x{} → gather on node {}",
                    element, deficit, buildingId.toString().substring(0, 8));
            return;
        }
    }

    @Nullable
    private static BuildingApi getBuildingApi() {
        try {
            return WandscapeApis.getBuildingApi();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static boolean isSynthesizeInFlight(String recipeId, World world) {
        for (GlobalTask t : world.taskPool.all()) {
            if (t.state == TaskState.COMPLETED) continue;
            if (!"production:synthesize".equals(t.blueprintId)) continue;
            JsonElement recipeParam = t.taskParams.get("recipe_id");
            if (recipeParam != null && recipeParam.isJsonPrimitive()
                    && recipeId.equals(recipeParam.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static String stripMcPrefix(String id) {
        if (id.startsWith("minecraft:")) return id.substring("minecraft:".length());
        return id;
    }

    private static JsonArray posToJsonArray(BlockPos pos) {
        JsonArray arr = new JsonArray();
        arr.add(pos.getX());
        arr.add(pos.getY());
        arr.add(pos.getZ());
        return arr;
    }
}
