package com.wsteam.wandscape.engine.source;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.system.TaskSource;
import com.wsteam.wandscape.core.task.GlobalTaskPool;
import com.wsteam.wandscape.core.task.TaskRequest;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

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

    @Override
    public int pollIntervalTicks() {
        return POLL_INTERVAL_TICKS;
    }

    @Override
    public void poll(GlobalTaskPool pool, World world) {
        BuildingApi api = getBuildingApi();
        if (api == null) return;

        pollCount++;

        List<UUID> buildingIds = api.getBuildingsWithPendingWork(null);

        if (pollCount % HEARTBEAT_INTERVAL == 0) {
            LOGGER.info("[BuildingTaskSource] heartbeat #{} — pool={} tasks, buildings_with_work={}",
                    pollCount, pool.size(), buildingIds.size());
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

    /** Convert engine long task id to a UUID for BuildingApi tracking. */
    private static UUID toTaskUuid(long taskId) {
        return new UUID(taskId, 0);
    }
}
