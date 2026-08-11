package com.wsteam.wandscape.engine.source;

import java.util.*;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.task.source.TaskSource;
import com.wsteam.wandscape.task.engine.pool.BuildingTaskPool;
import com.wsteam.wandscape.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.task.engine.pool.GlobalTaskPool;
import com.wsteam.wandscape.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.task.runtime.TaskState;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.engine.service.ChunkLoadManager;

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

        // ── 1. Cleanup: detect finished or resource-parked building head tasks ──
        if (btp != null) {
            for (var entry : btp.getAll().entrySet()) {
                UUID buildingId = entry.getKey();
                btp.pruneParked(buildingId, pool);

                Long headId = entry.getValue().getHeadTaskId();
                if (headId != null) {
                    GlobalTask head = pool.get(headId);
                    if (head == null || head.state == TaskState.COMPLETED) {
                        btp.onHeadCompleted(buildingId, pool);
                        api.clearCurrentTask(buildingId);
                        Log.info(TAG, "[BuildingTaskSource] cleanup building {} head #{} completed",
                                buildingId.toString().substring(0, 8), headId);
                    } else if (head.state == TaskState.AWAITING_RESOURCES) {
                        // Head is parked waiting for elements — release the head slot so the
                        // next queued WorkItem (which may be craftable) can be published. The
                        // parked task stays in the pool and resumes when its elements arrive.
                        btp.parkHead(buildingId, headId);
                        api.clearCurrentTask(buildingId);
                        Log.info(TAG, "[BuildingTaskSource] building {} head #{} parked on resource shortage",
                                buildingId.toString().substring(0, 8), headId);
                    }
                }

                // Release the footprint lease only when the building has no active head AND no
                // parked (resource-waiting) tasks that may still resume.
                if (!btp.hasHead(buildingId) && !btp.hasParked(buildingId)) {
                    ChunkLoadManager.get().releaseBuilding(buildingId);
                }
            }
        }

        // ── 2. Publish new work — only for buildings without a head task ──
        List<UUID> buildingIds = api.getBuildingsWithPendingWork(null);
        // Deterministic order so the concurrent-build budget isn't starved by map order.
        buildingIds.sort(Comparator.comparing(UUID::toString));

        if (pollCount % HEARTBEAT_INTERVAL == 0) {
        }

        ChunkLoadManager chunkLoad = ChunkLoadManager.get();
        int budget = Config.MAX_CONCURRENT_BUILDINGS.get();

        for (UUID buildingId : buildingIds) {
            // Skip if building already has an active head (should already be leased)
            if (btp != null && btp.hasHead(buildingId)) continue;

            // Force-load the footprint when a building is newly activated, within budget.
            boolean alreadyLeased = chunkLoad.isLeased(buildingId);
            if (!alreadyLeased) {
                if (chunkLoad.leasedCount() >= budget) {
                    continue;
                }
                if (!chunkLoad.leaseBuilding(buildingId)) {
                    Log.warn(TAG, "[BuildingTaskSource] lease failed for building {}, deferring",
                            buildingId.toString().substring(0, 8));
                    continue;
                }
            }

            WorkItem item = api.dequeueWork(buildingId);
            if (item == null) {
                // Nothing to actually do — drop the pointless lease.
                chunkLoad.releaseBuilding(buildingId);
                continue;
            }

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
                chunkLoad.releaseBuilding(buildingId);
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
