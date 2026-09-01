package com.wsteam.wandscape.content.building.source;
import com.wsteam.wandscape.content.colony.ColonyActivation;
import com.wsteam.wandscape.content.building.data.BuildingData;

import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.types.ResourceStack;
import com.wsteam.wandscape.content.production.ProductionEligibility;
import com.wsteam.wandscape.content.colony.service.ChunkLoadManager;
import com.wsteam.wandscape.api.BuildingApi;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.content.building.data.WorkItem;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.task.engine.pool.BuildingTaskPool;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTaskPool;
import com.wsteam.wandscape.content.task.engine.pool.TaskRequest;
import com.wsteam.wandscape.content.task.runtime.TaskState;
import com.wsteam.wandscape.content.task.source.TaskSource;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        var api = com.wsteam.wandscape.content.building.internal.BuildingApiImpl.get();
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
                        btp.onHeadCompleted(buildingId, resolveColony(api, buildingId), pool);
                        api.clearCurrentTask(buildingId);
                        Log.debug(LogCategory.BUILDING, "source", "cleanup building {} head #{} completed",
                                buildingId.toString().substring(0, 8), headId);
                    } else if (head.state == TaskState.AWAITING_RESOURCES) {
                        if (head.buildingId != null && isProductionTask(head) && isElementShortage(head)) {
                            // 生产任务中途缺元素（发布后元素被并发任务抢走）→ 回收回队列，
                            // 不 park 进不可见的 AWAITING_RESOURCES：任务留在面板可见，
                            // 下方发布区重新按「元素不足」跳过它，直到元素补齐。
                            WorkItem recycled = new WorkItem(head.blueprintId, head.taskParams, head.priority);
                            api.enqueueWork(buildingId, recycled);
                            pool.cancelTask(headId, world);
                            btp.onHeadCompleted(buildingId, resolveColony(api, buildingId), pool);
                            api.clearCurrentTask(buildingId);
                            Log.debug(LogCategory.BUILDING, "source", "building {} head #{} recycled to queue on element shortage",
                                    buildingId.toString().substring(0, 8), headId);
                        } else {
                            // 非生产任务（建材运输等）或缺的是物品原料（如药水玻璃瓶）→ 维持 park，
                            // 等资源到账由唤醒路径继续。
                            btp.parkHead(buildingId, headId);
                            api.clearCurrentTask(buildingId);
                            Log.debug(LogCategory.BUILDING, "source", "building {} head #{} parked on resource shortage",
                                    buildingId.toString().substring(0, 8), headId);
                        }
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
            com.wsteam.wandscape.content.building.data.BuildingData bd = api.getBuilding(buildingId);
            UUID colonyId = bd != null ? bd.getColonyId() : null;
            if (colonyId != null && !com.wsteam.wandscape.content.colony.ColonyActivation.isColonyActive(colonyId)) {
                continue;
            }

            // Skip if building already has an active head (should already be leased)
            if (btp != null && btp.hasHead(buildingId)) continue;

            // 先判队列里有没有现在能跑的条目：元素不足的合成任务不会发布，
            // 因此不能为了「排队但跑不了」的队列强制加载区块（否则每 20 tick 一次
            // 强制加载/释放，造成区块抖动）。
            Map<ElementType, Long> elementSnapshot = elementSnapshot(colonyId);
            if (!hasEligibleWork(api, buildingId, elementSnapshot)) {
                continue; // 什么都不够 → 不发布 → 相关 NPC 自然空闲，等自动收集补齐
            }

            // Force-load the footprint when a building is newly activated. No concurrency cap —
            // every building with pending work gets a lease (see docs/decisions.md).
            if (!chunkLoad.isLeased(buildingId) && !chunkLoad.leaseBuilding(buildingId)) {
                Log.warn(TAG, "[BuildingTaskSource] lease failed for building {}, deferring",
                        buildingId.toString().substring(0, 8));
                continue;
            }

            // 从上到下找第一个「当前元素够」的条目发布：元素不足的合成任务留在队列原位
            // 被跳过（面板可见「缺元素」），分解/建造/采集等非元素任务恒可发布。
            WorkItem item = api.dequeueWorkEligible(buildingId, work -> isEligible(work, elementSnapshot));
            if (item == null) {
                // 预检通过到真正弹队列之间元素被并发任务消耗光的竞态 → 放弃本轮。
                chunkLoad.releaseBuilding(buildingId);
                continue;
            }

            try {
                long taskId;
                if (btp != null) {
                    taskId = btp.enqueue(buildingId, colonyId, item, pool);
                } else {
                    // Fallback: direct publish (no BuildingTaskPool)
                    TaskRequest request = new TaskRequest(
                            item.blueprintId(), item.params(), item.priority(), colonyId);
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

    /** 消耗元素的生产任务（分解不抛短缺，排除）。 */
    private static boolean isProductionTask(GlobalTask task) {
        return task.blueprintId != null && task.blueprintId.startsWith("production:")
                && !"production:decompose".equals(task.blueprintId);
    }

    /** 短缺是否基于元素（回收只对元素短缺有意义；药水原料等物品短缺维持 park 等待）。 */
    private static boolean isElementShortage(GlobalTask task) {
        if (task.awaitingResource == null) return false;
        for (ResourceStack need : task.awaitingResource) {
            try {
                ElementType.valueOf(need.resource().id().toUpperCase());
                return true;
            } catch (IllegalArgumentException e) {
                // 不是元素 id → 物品短缺，不回收
            }
        }
        return false;
    }

    /** 非元素工作恒可发布；消耗元素的生产配方需当前元素足够才可发布。 */
    private static boolean isEligible(WorkItem work, @Nullable Map<ElementType, Long> elementSnapshot) {
        if (!ProductionEligibility.isElementCosting(work.blueprintId())) return true;
        Map<ElementType, Long> required = ProductionEligibility.requiredElements(work.blueprintId(), work.params());
        return ProductionEligibility.isAffordable(required, elementSnapshot);
    }

    /** 队列里是否存在现在能发布的条目（读队列，不弹）。 */
    private static boolean hasEligibleWork(com.wsteam.wandscape.content.building.internal.BuildingApiImpl api, UUID buildingId,
                                           @Nullable Map<ElementType, Long> elementSnapshot) {
        for (WorkItem item : api.getQueue(buildingId)) {
            if (isEligible(item, elementSnapshot)) return true;
        }
        return false;
    }

    /** 殖民地当前元素存量快照；无殖民地或银行不可用返回 null（元素配方按不足处理）。 */
    @Nullable
    private static Map<ElementType, Long> elementSnapshot(@Nullable UUID colonyId) {
        if (colonyId == null) return null;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        ColonyItemBank bank = ColonyItemBank.get(server.overworld());
        return bank != null ? bank.getElementSnapshot(colonyId) : null;
    }

    /** 建筑所属殖民地；建筑不存在/未归属返回 null（该任务视为无主，仍可派给真实殖民地 NPC）。 */
    @Nullable
    private static UUID resolveColony(com.wsteam.wandscape.content.building.internal.BuildingApiImpl api, UUID buildingId) {
        com.wsteam.wandscape.content.building.data.BuildingData bd = api.getBuilding(buildingId);
        return bd != null ? bd.getColonyId() : null;
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
        World world = World.getActive();
        if (world == null || world.taskPool == null || world.buildingTaskPool == null) return;

        for (long taskId : world.buildingTaskPool.removeBuilding(buildingId)) {
            world.taskPool.cancelTask(taskId, world);
        }

        // Sweep any active tasks in the global pool explicitly tagged with this buildingId
        for (GlobalTask task : world.taskPool.all()) {
            if (buildingId.equals(task.buildingId) && task.state != TaskState.COMPLETED) {
                world.taskPool.cancelTask(task.id, world);
            }
        }

        ChunkLoadManager.get().releaseBuilding(buildingId);
        var api = com.wsteam.wandscape.content.building.internal.BuildingApiImpl.get();
        if (api != null) {
            api.clearCurrentTask(buildingId);
        }
    }

    /** Convert engine long task id to a UUID for BuildingApi tracking. */
    private static UUID toTaskUuid(long taskId) {
        return new UUID(taskId, 0);
    }
}
