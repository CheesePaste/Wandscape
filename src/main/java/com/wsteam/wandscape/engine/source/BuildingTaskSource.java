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

    // Poll every 1 second (20 ticks) — fast enough to feel responsive,
    // slow enough to not hammer chunk loads
    private static final int POLL_INTERVAL_TICKS = 20;

    @Override
    public int pollIntervalTicks() {
        return POLL_INTERVAL_TICKS;
    }

    @Override
    public void poll(GlobalTaskPool pool, World world) {
        BuildingApi api = getBuildingApi();
        if (api == null) return;

        // Iterate all colonies. In stage 1, colonyId may be null — handle gracefully.
        List<UUID> buildingIds = api.getBuildingsWithPendingWork(null);
        // Also try getting buildings from the world's colony list once available
        // For now, the BuildingApiImpl's implementation handles null colony = all buildings

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
                // Track task via deterministic UUID from engine task id
                api.setCurrentTask(buildingId, toTaskUuid(taskId));
                LOGGER.debug("BuildingTaskSource: published task #{} ({}) from building {}",
                        taskId, item.blueprintId(), buildingId);
            } catch (Exception e) {
                LOGGER.warn("BuildingTaskSource: failed to publish task '{}' from building {}: {}",
                        item.blueprintId(), buildingId, e.getMessage());
            }
        }
    }

    private BuildingApi getBuildingApi() {
        try {
            return WandscapeApis.getBuildingApi();
        } catch (IllegalStateException e) {
            // BuildingCore not yet loaded
            return null;
        }
    }

    /** Convert engine long task id to a UUID for BuildingApi tracking. */
    private static UUID toTaskUuid(long taskId) {
        return new UUID(taskId, 0);
    }
}
