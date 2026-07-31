package com.wsteam.wandscape.engine.source;

import java.util.*;

import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.task.source.TaskSource;
import com.wsteam.wandscape.task.engine.pool.BuildingTaskPool;
import com.wsteam.wandscape.task.engine.pool.GlobalTaskPool;
import com.wsteam.wandscape.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
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

        // ── 2. Publish new work — only for buildings without a head task ──
        List<UUID> buildingIds = api.getBuildingsWithPendingWork(null);

        if (pollCount % HEARTBEAT_INTERVAL == 0) {
            Log.debug(TAG, "[BuildingTaskSource] heartbeat #{} — pool={} tasks, buildings_with_work={}, building_pool={}",
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
                            item.blueprintId(), item.params(), item.priority());
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

    /** Convert engine long task id to a UUID for BuildingApi tracking. */
    private static UUID toTaskUuid(long taskId) {
        return new UUID(taskId, 0);
    }
}
