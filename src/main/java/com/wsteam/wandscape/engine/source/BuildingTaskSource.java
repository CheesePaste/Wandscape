package com.wsteam.wandscape.engine.source;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.data.BuildingConfig.NodeConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.system.TaskSource;
import com.wsteam.wandscape.core.task.GlobalTaskPool;
import com.wsteam.wandscape.core.task.TaskRequest;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.core.BlockPos;

/**
 * {@link TaskSource} that polls building block entities and translates
 * queued {@link WorkItem}s into engine {@link TaskRequest}s.
 *
 * <p>This is the ONLY bridge between building BEs and the engine task pool.
 * BEs store queue data; this source pulls it into the engine on a fixed interval.
 */
public class BuildingTaskSource implements TaskSource {
    private static final Logger LOGGER = LogUtils.getLogger();

    // Poll every 1 second (20 ticks)
    private static final int POLL_INTERVAL_TICKS = 20;

    // Log heartbeat every N polls to avoid spam
    private int pollCount = 0;
    private static final int HEARTBEAT_INTERVAL = 10; // every ~10 seconds

    /** Tracks buildingId → engine taskId for active tasks. */
    private final Map<UUID, Long> activeTasks = new ConcurrentHashMap<>();

    @Override
    public int pollIntervalTicks() {
        return POLL_INTERVAL_TICKS;
    }

    @Override
    public void poll(GlobalTaskPool pool, World world) {
        BuildingApi api = getBuildingApi();
        if (api == null) return;

        pollCount++;

        // ── 1. Cleanup: clear completed/stale tasks ──
        for (Iterator<Map.Entry<UUID, Long>> it = activeTasks.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (!pool.isActive(entry.getValue())) {
                api.clearCurrentTask(entry.getKey());
                it.remove();
            }
        }

        // ── 2. Node auto-supply: enqueue gather work for idle node buildings ──
        supplyNodeBuildings(api);

        // ── 3. Publish new work ──
        List<UUID> buildingIds = api.getBuildingsWithPendingWork(null);

        if (pollCount % HEARTBEAT_INTERVAL == 0) {
            LOGGER.info("[BuildingTaskSource] heartbeat #{} — pool={} tasks, buildings_with_work={}, tracked_active={}",
                    pollCount, pool.size(), buildingIds.size(), activeTasks.size());
        }

        for (UUID buildingId : buildingIds) {
            WorkItem item = api.dequeueWork(buildingId);
            if (item == null) continue;

            TaskRequest request = new TaskRequest(
                    item.blueprintId(),
                    item.params(),
                    item.priority()
            );

            try {
                long taskId = pool.addTask(request);
                api.setCurrentTask(buildingId, toTaskUuid(taskId));
                activeTasks.put(buildingId, taskId);
                LOGGER.info("[BuildingTaskSource] >>> TASK PUBLISHED: id=#{} blueprint={} params={} priority={} building={} pool_size={}",
                        taskId, item.blueprintId(), item.params(), item.priority(),
                        buildingId.toString().substring(0, 8), pool.size());
            } catch (Exception e) {
                LOGGER.warn("[BuildingTaskSource] FAILED: blueprint={} building={} error={}",
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
     * Phase 2: For every idle node building (no current task, no queued work,
     * operational), auto-enqueue a gather WorkItem.
     */
    private void supplyNodeBuildings(BuildingApi api) {
        BuildingConfigLoader configLoader = BuildingConfigLoader.getInstance();

        // Buildings that already have queued work (will be published in Phase 3)
        Set<UUID> hasWork = new HashSet<>(api.getBuildingsWithPendingWork(null));

        for (UUID buildingId : api.getBuildingsByCategory(null, "node")) {
            // Already has queued work → skip
            if (hasWork.contains(buildingId)) continue;
            // Already running a task → skip
            if (api.isBuildingOccupied(buildingId)) continue;

            BuildingData bd = api.getBuilding(buildingId);
            if (bd == null || bd.isShutdown()) continue;

            BuildingConfig config = configLoader.get(bd.getBuildingTypeId());
            if (config == null) continue;

            NodeConfig nodeConfig = config.nodeConfig();
            if (nodeConfig == null) continue;

            // Build WorkItem params
            Map<String, JsonElement> params = new LinkedHashMap<>();
            BlockPos pos = bd.getPosition();
            params.put("anchor", posToJsonArray(pos));
            params.put("element", new JsonPrimitive(nodeConfig.element()));
            params.put("amount", new JsonPrimitive(nodeConfig.amountPerHarvest()));
            params.put("channel_ticks", new JsonPrimitive(nodeConfig.channelTicks()));

            WorkItem work = new WorkItem(nodeConfig.blueprint(), params, 15);
            api.enqueueWork(buildingId, work);
            LOGGER.info("[BuildingTaskSource] node supply: {} → {} x{} ({} ticks)",
                    buildingId.toString().substring(0, 8),
                    nodeConfig.element(), nodeConfig.amountPerHarvest(),
                    nodeConfig.channelTicks());
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
