package com.wsteam.wandscape.engine.source;

import java.util.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.data.BuildingConfig.NodeConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.system.TaskSource;
import com.wsteam.wandscape.core.task.BuildingTaskPool;
import com.wsteam.wandscape.core.task.GlobalTaskPool;
import com.wsteam.wandscape.core.task.TaskRequest;
import com.wsteam.wandscape.core.types.BehaviourTag;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.core.BlockPos;
import com.wsteam.wandscape.shared.log.Log;

/**
 * {@link TaskSource} that polls building block entities and translates
 * queued {@link WorkItem}s into engine {@link TaskRequest}s.
 *
 * <p>Uses {@link BuildingTaskPool} to ensure only one head task per building
 * enters the {@link GlobalTaskPool} at a time. When the head completes, the
 * next pending WorkItem is promoted.
 *
 * <p>This is the ONLY bridge between building BEs and the engine task pool.
 */
public class BuildingTaskSource implements TaskSource {
    private static final String TAG = "BuildingTaskSource";

    // Poll every 1 second (20 ticks)
    private static final int POLL_INTERVAL_TICKS = 20;

    // Log heartbeat every N polls to avoid spam
    private int pollCount = 0;
    private static final int HEARTBEAT_INTERVAL = 10; // every ~10 seconds

    @Override
    public int pollIntervalTicks() {
        return POLL_INTERVAL_TICKS;
    }

    @Override
    public void poll(GlobalTaskPool pool, World world) {
        BuildingApi api = getBuildingApi();
        if (api == null) return;

        pollCount++;

        BuildingTaskPool btp = world.buildingTaskPool;

        // ── 1. Cleanup: detect completed building head tasks, promote next ──
        if (btp != null) {
            for (var entry : btp.getAll().entrySet()) {
                UUID buildingId = entry.getKey();
                Long headId = entry.getValue().getHeadTaskId();
                if (headId != null && !pool.isActive(headId)) {
                    btp.onHeadCompleted(buildingId, pool);
                    api.clearCurrentTask(buildingId);
                    Log.info(TAG, "[BuildingTaskSource] cleanup building {} head #{} completed",
                            buildingId.toString().substring(0, 8), headId);
                }
            }
        }

        // ── 2. Node auto-supply: enqueue gather work for idle node buildings ──
        supplyNodeBuildings(api, btp);

        // ── 3. Publish new work — only for buildings without a head task ──
        List<UUID> buildingIds = api.getBuildingsWithPendingWork(null);

        if (pollCount % HEARTBEAT_INTERVAL == 0) {
            Log.info(TAG, "[BuildingTaskSource] heartbeat #{} — pool={} tasks, buildings_with_work={}, building_pool={}",
                    pollCount, pool.size(), buildingIds.size(),
                    btp != null ? btp.totalBuildings() : 0);
        }

        for (UUID buildingId : buildingIds) {
            // Skip if building already has an active head
            if (btp != null && btp.hasHead(buildingId)) continue;

            WorkItem item = api.dequeueWork(buildingId);
            if (item == null) continue;

            try {
                long taskId;
                if (btp != null) {
                    taskId = btp.enqueue(buildingId, item, pool);
                } else {
                    // Fallback: direct publish (no BuildingTaskPool)
                    TaskRequest request = new TaskRequest(
                            item.blueprintId(), item.params(), item.priority(),
                            item.wandRequirementOverrides());
                    taskId = pool.addTask(request);
                }

                if (taskId >= 0) {
                    api.setCurrentTask(buildingId, toTaskUuid(taskId));
                    Log.info(TAG, "[BuildingTaskSource] >>> TASK PUBLISHED: id=#{} blueprint={} building={} pool_size={}",
                            taskId, item.blueprintId(),
                            buildingId.toString().substring(0, 8), pool.size());
                }
            } catch (Exception e) {
                Log.warn(TAG, "[BuildingTaskSource] FAILED: blueprint={} building={} error={}",
                        item.blueprintId(), buildingId, e.getMessage());
            }
        }
    }

    private BuildingApi getBuildingApi() {
        try {
            return WandscapeApis.getBuildingApi();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /**
     * For every idle node building (no current task, no queued work,
     * operational), auto-enqueue a gather WorkItem.
     */
    private void supplyNodeBuildings(BuildingApi api, @javax.annotation.Nullable BuildingTaskPool btp) {
        BuildingConfigLoader configLoader = BuildingConfigLoader.getInstance();

        // Check WarehouseSource thresholds — if it's active, respect its per-resource settings
        var ws = com.wsteam.wandscape.core.system.WarehouseSource.getActive();

        // Buildings that already have queued work (will be published in step 3)
        Set<UUID> hasWork = new HashSet<>(api.getBuildingsWithPendingWork(null));
        Log.info(TAG, "[TaskSrc] node supply scan: {} buildings already have pending work", hasWork.size());

        List<UUID> nodeBuildings = api.getBuildingsByCategory(null, "node");
        Log.info(TAG, "[TaskSrc] node supply: found {} node buildings total", nodeBuildings.size());

        for (UUID buildingId : nodeBuildings) {
            // Already has queued work → skip
            if (hasWork.contains(buildingId)) continue;
            // Already running a task → skip
            if (api.isBuildingOccupied(buildingId)) continue;
            // Already has a head in building task pool → skip
            if (btp != null && btp.hasHead(buildingId)) continue;

            BuildingData bd = api.getBuilding(buildingId);
            if (bd == null || bd.isShutdown() || !bd.isStructureIntact()) continue;

            BuildingConfig config = configLoader.get(bd.getBuildingTypeId());
            if (config == null) continue;

            NodeConfig nodeConfig = config.nodeConfig();
            if (nodeConfig == null) continue;

            // ── Check WarehouseSource threshold for this node's element ──
            if (ws != null) {
                var resourceId = new com.wsteam.wandscape.core.types.ResourceId(nodeConfig.element());
                int threshold = ws.getThreshold(resourceId);
                if (threshold <= 0) {
                    Log.debug(TAG,"[TaskSrc] node {} ({}) skipped: threshold=0 (disabled)",
                            buildingId.toString().substring(0, 8), resourceId.id());
                    continue; // auto-gather disabled for this resource
                }

                // Compare against current colony stock — only gather if stock < threshold
                UUID colonyId = bd.getColonyId();
                if (colonyId != null) {
                    try {
                        var elementType = com.wsteam.wandscape.shared.data.ElementType.valueOf(
                                nodeConfig.element().toUpperCase());
                        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                        if (server != null) {
                            var bank = com.wsteam.wandscape.warehouse.ColonyItemBank.get(server.overworld());
                            long currentStock = bank.countElement(colonyId, elementType);
                            if (currentStock >= threshold) {
                                Log.debug(TAG,"[TaskSrc] node {} ({}) skipped: stock={} >= threshold={}",
                                        buildingId.toString().substring(0, 8),
                                        resourceId.id(), currentStock, threshold);
                                continue;
                            }
                        }
                    } catch (IllegalArgumentException e) {
                        Log.debug(TAG,"[TaskSrc] node {}: unknown element '{}', proceeding anyway",
                                buildingId.toString().substring(0, 8), nodeConfig.element());
                    }
                }
            }

            // Build WorkItem params
            Map<String, JsonElement> params = new LinkedHashMap<>();
            BlockPos pos = bd.getPosition();
            params.put("anchor", posToJsonArray(pos));
            params.put("element", new JsonPrimitive(nodeConfig.element()));
            params.put("amount", new JsonPrimitive(nodeConfig.amountPerHarvest()));
            params.put("channel_ticks", new JsonPrimitive(nodeConfig.channelTicks()));
            params.put("mana_cost", new JsonPrimitive(nodeConfig.manaCost()));

            // Convert JSON wand_level string keys to BehaviourTag overrides
            Map<BehaviourTag, Integer> overrides = new HashMap<>();
            for (var e : nodeConfig.wandLevel().entrySet()) {
                BehaviourTag tag = BehaviourTag.fromKey(e.getKey());
                if (tag != null) overrides.put(tag, e.getValue());
            }

            WorkItem work = new WorkItem(nodeConfig.blueprint(), params, 15, overrides);
            api.enqueueWork(buildingId, work);
            Log.info(TAG, "[BuildingTaskSource] node supply: {} → {} x{} ({}t, {} mana) wandLevel={}",
                    buildingId.toString().substring(0, 8),
                    nodeConfig.element(), nodeConfig.amountPerHarvest(),
                    nodeConfig.channelTicks(), nodeConfig.manaCost(), overrides);
        }
    }

    /** Convert a BlockPos to a JSON array [x, y, z] for blueprint params. */
    private static com.google.gson.JsonArray posToJsonArray(BlockPos pos) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        arr.add(pos.getX());
        arr.add(pos.getY());
        arr.add(pos.getZ());
        return arr;
    }

    /** Convert engine long task id to a UUID for BuildingApi tracking. */
    private static UUID toTaskUuid(long taskId) {
        return new UUID(taskId, 0);
    }
}
