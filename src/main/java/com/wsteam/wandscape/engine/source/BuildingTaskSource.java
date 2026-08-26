package com.wsteam.wandscape.engine.source;

import java.util.*;

import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.engine.WandscapeEngine;
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
        // Deterministic order so no building is starved by map order (id sort for fairness).
        buildingIds.sort(Comparator.comparing(UUID::toString));

        if (pollCount % HEARTBEAT_INTERVAL == 0) {
        }

        ChunkLoadManager chunkLoad = ChunkLoadManager.get();

        for (UUID buildingId : buildingIds) {
            // 创始人不在线且关闭离线运行 → 冻结小镇：不发布新任务
            //（建筑的排队工作与占地保留，上线后由下一次 poll 继续处理）
            com.wsteam.wandscape.shared.data.BuildingData bd = api.getBuilding(buildingId);
            UUID colonyId = bd != null ? bd.getColonyId() : null;
            if (colonyId != null && !com.wsteam.wandscape.engine.colony.ColonyActivation.isColonyActive(colonyId)) {
                continue;
            }

            // Skip if building already has an active head (should already be leased)
            if (btp != null && btp.hasHead(buildingId)) continue;

            // Force-load the footprint when a building is newly activated. No concurrency cap —
            // every building with pending work gets a lease (see docs/decisions.md).
            if (!chunkLoad.isLeased(buildingId) && !chunkLoad.leaseBuilding(buildingId)) {
                Log.warn(TAG, "[BuildingTaskSource] lease failed for building {}, deferring",
                        buildingId.toString().substring(0, 8));
                continue;
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

    /**
     * Immediately cancel a building's active engine tasks (the head task an NPC
     * is executing, plus any parked/pending) and drop its per-building task-pool
     * queue. This is the synchronous counterpart of {@link #poll}'s cleanup:
     * {@code poll} only reacts to a completed head on the next 20-tick cycle,
     * which is too slow when a building is undone — the NPC would keep placing
     * blocks. Stops the NPC mid-task, releases the footprint chunk lease, and
     * clears the {@link BuildingApi} current-task marker.
     *
     * <p>The material flow is intentionally left untouched: an in-flight
     * {@code request_resource} transport finishes and commits on its own, which
     * matches {@code cancelBuilding}'s existing "materials are charged in one
     * bulk commit at construction start" refund assumption.
     *
     * <p>Idempotent: a building whose queue was already removed returns no task
     * ids and is a no-op (safe to call from both undo and demolish paths).
     */
    public static void cancelBuildingTasks(UUID buildingId) {
        World world = WandscapeEngine.getWorld();
        if (world == null || world.taskPool == null || world.buildingTaskPool == null) return;

        for (long taskId : world.buildingTaskPool.removeBuilding(buildingId)) {
            world.taskPool.cancelTask(taskId, world);
        }

        ChunkLoadManager.get().releaseBuilding(buildingId);
        var api = WandscapeApis.getBuildingApiSilently();
        if (api != null) {
            api.clearCurrentTask(buildingId);
        }
    }

    /** Convert engine long task id to a UUID for BuildingApi tracking. */
    private static UUID toTaskUuid(long taskId) {
        return new UUID(taskId, 0);
    }
}
