package com.wsteam.wandscape.content.task.engine.pool;

import com.wsteam.wandscape.content.building.data.WorkItem;
import com.wsteam.wandscape.content.building.internal.ConstructionBatches;
import com.wsteam.wandscape.content.task.boundary.EventBus;
import com.wsteam.wandscape.content.task.event.CustomEvent;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.content.task.runtime.TaskState;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-building task queues so only the head task of each building
 * enters the {@link GlobalTaskPool}. When the head completes, the next
 * pending WorkItem is automatically promoted.
 *
 * <p>Pure data structure — zero Minecraft dependencies.
 */
public class BuildingTaskPool {

    private static final String TAG = "BuildingTaskPool";

    private final Map<UUID, BuildingTaskQueue> queues = new ConcurrentHashMap<>();

    @Nullable
    public Runnable onChanged;

    public void notifyChanged() {
        if (onChanged != null) onChanged.run();
    }

    private BuildingTaskQueue getOrCreate(UUID buildingId) {
        return queues.computeIfAbsent(buildingId, k -> new BuildingTaskQueue());
    }

    /**
     * Enqueue a WorkItem for a building.
     * If the building has no head task, compile and publish to the global pool immediately.
     * Otherwise, append to the building's pending queue.
     *
     * @param colonyId the building's owning colony (may be null for unassigned buildings) —
     *                 passed through to the published {@link TaskRequest}
     * @return the global task ID if a new head was published, or -1 if queued behind existing head
     */
    public long enqueue(UUID buildingId, @Nullable UUID colonyId, WorkItem item, GlobalTaskPool pool) {
        BuildingTaskQueue queue = getOrCreate(buildingId);

        if (!queue.hasHead()) {
            if (ConstructionBatches.isSplittable(item)) {
                long tid = enqueueBatches(buildingId, colonyId, ConstructionBatches.split(item, colonyId), pool);
                notifyChanged();
                return tid;
            }
            TaskRequest request = new TaskRequest(
                    item.blueprintId(), item.params(), item.priority(), colonyId);
            long taskId = pool.addTaskFromBuilding(request, buildingId);
            queue.setHeadTaskId(taskId);
            notifyChanged();
            return taskId;
        }

        queue.enqueue(item);
        notifyChanged();
        return -1;
    }

    /**
     * Enqueue a pre-split set of construction batches for a building.
     * If the task requires preparation (materials/clearing), only the initial batch is published;
     * subsequent batches wait in {@link BuildingTaskQueue#getPendingBatches} until the initial batch finishes.
     * Otherwise, all batches are published to {@link GlobalTaskPool} simultaneously for concurrent execution.
     *
     * @return global task ID of the first published batch
     */
    public long enqueueBatches(UUID buildingId, @Nullable UUID colonyId, ConstructionBatches.SplitResult split, GlobalTaskPool pool) {
        BuildingTaskQueue queue = getOrCreate(buildingId);
        queue.setColonyId(colonyId);
        Map<String, String> compData = new LinkedHashMap<>(split.completionData());
        if (buildingId != null) {
            compData.putIfAbsent("building_id", buildingId.toString());
        }
        queue.setCompletionData(compData);

        boolean needsPrep = ConstructionBatches.needsPreparation(split.initialBatch());
        if (needsPrep) {
            long taskId = pool.addTaskFromBuilding(split.initialBatch(), buildingId);
            queue.addActiveBatch(taskId);
            queue.setPendingBatches(split.remainingBatches());
            Log.info(TAG, "building {} published prep batch #{} ({} pending placement batches)",
                    buildingId.toString().substring(0, 8), taskId, split.remainingBatches().size());
            notifyChanged();
            return taskId;
        } else {
            long firstId = pool.addTaskFromBuilding(split.initialBatch(), buildingId);
            queue.addActiveBatch(firstId);
            for (TaskRequest req : split.remainingBatches()) {
                long taskId = pool.addTaskFromBuilding(req, buildingId);
                queue.addActiveBatch(taskId);
            }
            queue.clearPendingBatches();
            Log.info(TAG, "building {} published all {} batches concurrently",
                    buildingId.toString().substring(0, 8), 1 + split.remainingBatches().size());
            notifyChanged();
            return firstId;
        }
    }

    /**
     * Called when a building's head task completes or fails terminally.
     * Promotes the next pending WorkItem to head if any remain.
     *
     * @param colonyId the building's owning colony (passed through to the promoted task)
     */
    public void onHeadCompleted(UUID buildingId, @Nullable UUID colonyId, GlobalTaskPool pool) {
        BuildingTaskQueue queue = queues.get(buildingId);
        if (queue == null) return;

        queue.clearHead();

        WorkItem next = queue.dequeueNext();
        if (next != null) {
            if (ConstructionBatches.isSplittable(next)) {
                long taskId = enqueueBatches(buildingId, colonyId, ConstructionBatches.split(next, colonyId), pool);
                Log.info(TAG, "building {} promoted next splittable batch #{} blueprint={} pending={}",
                        buildingId.toString().substring(0, 8), taskId,
                        next.blueprintId(), queue.pendingSize());
            } else {
                TaskRequest request = new TaskRequest(
                        next.blueprintId(), next.params(), next.priority(), colonyId);
                long taskId = pool.addTaskFromBuilding(request, buildingId);
                queue.setHeadTaskId(taskId);
                Log.info(TAG, "building {} promoted next #{} blueprint={} pending={}",
                        buildingId.toString().substring(0, 8), taskId,
                        next.blueprintId(), queue.pendingSize());
            }
        } else {
            queues.remove(buildingId); // clean up empty queue
        }
        notifyChanged();
    }

    /**
     * Park a building's head task that went {@code AWAITING_RESOURCES} (e.g. an
     * element shortage during synthesis / craft). Frees the head slot so the next
     * WorkItem can be published; the parked task stays in the {@link GlobalTaskPool}
     * and resumes on its own once its resources arrive.
     */
    public void parkHead(UUID buildingId, long taskId) {
        BuildingTaskQueue queue = queues.get(buildingId);
        if (queue == null) return;
        Long head = queue.getHeadTaskId();
        if (head != null && head == taskId) {
            queue.setHeadTaskId(null);
        }
        queue.removeActiveBatch(taskId);
        queue.addParked(taskId);
        notifyChanged();
    }

    public boolean hasActiveBatches(UUID buildingId) {
        BuildingTaskQueue queue = queues.get(buildingId);
        return queue != null && queue.hasActiveBatches();
    }

    /**
     * 该建筑除 {@code excludeTaskId} 之外是否还有未完工批次（待发的 pending，或别的活跃批次）。
     *
     * <p>多法师协同的批次续接判定用：批次完工那一刻它自己还在 {@code activeBatchIds} 里，
     * 必须排除掉自己——否则每栋楼的最后一批也会留下一个永远没用武之地的续接意图。
     */
    public boolean hasOtherUnfinishedBatches(UUID buildingId, long excludeTaskId) {
        BuildingTaskQueue queue = queues.get(buildingId);
        if (queue == null) return false;
        if (queue.hasPendingBatches()) return true;
        for (long id : queue.getActiveBatchIds()) {
            if (id != excludeTaskId) return true;
        }
        return false;
    }

    public boolean isBatchBuilding(UUID buildingId) {
        BuildingTaskQueue queue = queues.get(buildingId);
        return queue != null && (queue.hasActiveBatches() || queue.hasPendingBatches() || queue.hasCompletionData() || (queue.hasParked() && queue.hasCompletionData()));
    }

    /**
     * Checks progress of active batches for a building.
     * Prunes finished active batches and parked tasks.
     * If all active batches finish:
     * <ul>
     *   <li>If pending batches exist (Phase 1 prep finished), publishes all pending batches (Phase 2 parallel placement).</li>
     *   <li>If no pending batches remain, emits "build_complete" event via eventBus,
     *       clears batch state, promotes next WorkItem in queue (or removes queue).</li>
     * </ul>
     *
     * @return true if the entire batch construction for this building is now complete
     */
    public boolean checkBatchesProgress(UUID buildingId, GlobalTaskPool pool, @Nullable EventBus eventBus) {
        BuildingTaskQueue queue = queues.get(buildingId);
        if (queue == null) return false;
        if (!queue.hasActiveBatches() && !queue.hasPendingBatches() && !queue.hasCompletionData() && !queue.hasParked()) {
            return false;
        }

        // 0. Unpark any batches that woke up from AWAITING_RESOURCES
        if (queue.hasParked()) {
            for (long parkedId : new ArrayList<>(queue.getParkedTaskIds())) {
                GlobalTask task = pool.get(parkedId);
                if (task != null && task.state != TaskState.AWAITING_RESOURCES && task.state != TaskState.COMPLETED) {
                    queue.unparkBatch(parkedId);
                    Log.debug(TAG, "building {} batch #{} unparked (state={})",
                            buildingId.toString().substring(0, 8), parkedId, task.state);
                }
            }
        }

        // 1. Prune finished active batches
        List<Long> done = new ArrayList<>();
        for (long batchId : new ArrayList<>(queue.getActiveBatchIds())) {
            GlobalTask task = pool.get(batchId);
            if (task == null || task.state == TaskState.COMPLETED) {
                done.add(batchId);
            }
        }
        for (long batchId : done) {
            queue.removeActiveBatch(batchId);
        }

        // 2. Prune finished parked batches
        List<Long> doneParked = new ArrayList<>();
        if (queue.hasParked()) {
            for (long parkedId : new ArrayList<>(queue.getParkedTaskIds())) {
                GlobalTask task = pool.get(parkedId);
                if (task == null || task.state == TaskState.COMPLETED) {
                    doneParked.add(parkedId);
                }
            }
            for (long parkedId : doneParked) {
                queue.removeParked(parkedId);
            }
        }

        if (!done.isEmpty() || !doneParked.isEmpty()) {
            notifyChanged();
        }

        // If batches are still active or parked (waiting resources), not done yet
        if (queue.hasActiveBatches() || queue.hasParked()) {
            return false;
        }

        // 3. If there are pending batches waiting for prep batch to finish, release them all now
        if (queue.hasPendingBatches()) {
            List<TaskRequest> pending = queue.getPendingBatches();
            queue.clearPendingBatches();
            for (TaskRequest req : pending) {
                long taskId = pool.addTaskFromBuilding(req, buildingId);
                queue.addActiveBatch(taskId);
            }
            notifyChanged();
            Log.info(TAG, "building {} foundation finished, released {} parallel placement batches",
                    buildingId.toString().substring(0, 8), pending.size());
            return false;
        }

        // 4. All batches completed! Emit build_complete event and finish
        Map<String, String> completionData = queue.getCompletionData();
        if (completionData != null && eventBus != null) {
            eventBus.emit(new CustomEvent("build_complete", completionData));
            Log.info(TAG, "building {} all batches finished, emitted build_complete event",
                    buildingId.toString().substring(0, 8));
        }
        queue.clearCompletionData();

        UUID colonyId = queue.getColonyId();
        onHeadCompleted(buildingId, colonyId, pool);
        return true;
    }

    public boolean hasParked(UUID buildingId) {
        BuildingTaskQueue queue = queues.get(buildingId);
        return queue != null && queue.hasParked();
    }

    /** Snapshot of parked task ids for a building (debug/UI). */
    public Set<Long> getParkedTaskIds(UUID buildingId) {
        BuildingTaskQueue queue = queues.get(buildingId);
        return queue != null ? queue.getParkedTaskIds() : Set.of();
    }

    /** Drop parked tasks whose global task has completed or vanished. */
    public void pruneParked(UUID buildingId, GlobalTaskPool pool) {
        BuildingTaskQueue queue = queues.get(buildingId);
        if (queue == null) return;
        boolean changed = false;
        if (queue.hasParked()) {
            for (long taskId : new ArrayList<>(queue.getParkedTaskIds())) {
                GlobalTask task = pool.get(taskId);
                if (task == null || task.state == TaskState.COMPLETED) {
                    queue.removeParked(taskId);
                    changed = true;
                }
            }
        }
        // Clean up a queue left empty after its parked tasks completed with no new head.
        if (queue.isEmpty()) {
            queues.remove(buildingId, queue);
            changed = true;
        }
        if (changed) {
            notifyChanged();
        }
    }

    @Nullable
    public Long getHeadTaskId(UUID buildingId) {
        BuildingTaskQueue queue = queues.get(buildingId);
        return queue != null ? queue.getHeadTaskId() : null;
    }

    /**
     * True if the building still has a queue entry (head, parked, or pending WorkItems).
     * Unlike {@link #hasHead} this is also true for a queue holding only pending items.
     */
    public boolean hasQueue(UUID buildingId) {
        return queues.containsKey(buildingId);
    }

    public boolean hasHead(UUID buildingId) {
        BuildingTaskQueue queue = queues.get(buildingId);
        return queue != null && queue.hasHead();
    }

    public int getPendingCount(UUID buildingId) {
        BuildingTaskQueue queue = queues.get(buildingId);
        return queue != null ? queue.pendingSize() : 0;
    }

    public int totalBuildings() {
        return queues.size();
    }

    public void clear() {
        queues.clear();
        notifyChanged();
    }

    /** For persistence: all non-empty building queues. */
    public Map<UUID, BuildingTaskQueue> getAll() {
        return Map.copyOf(queues);
    }

    /**
     * Remove a building's whole queue (head, parked tasks, pending WorkItems).
     * Returns the global task ids that were live (head + parked) so the caller
     * can terminally cancel them in the {@link GlobalTaskPool} — otherwise a
     * cancelled building's task would keep running (e.g. an NPC still placing
     * blocks after an undo).
     */
    public List<Long> removeBuilding(UUID buildingId) {
        BuildingTaskQueue queue = queues.remove(buildingId);
        if (queue == null) return List.of();
        notifyChanged();
        List<Long> ids = new ArrayList<>(queue.getAllActiveTaskIds());
        ids.addAll(queue.getParkedTaskIds());
        return ids;
    }

    /**
     * Re-index active building tasks from GlobalTaskPool (e.g. after world load).
     */
    public void rebuildFromPool(GlobalTaskPool pool) {
        for (GlobalTask task : pool.all()) {
            if (task.buildingId != null && task.state != TaskState.COMPLETED) {
                BuildingTaskQueue q = getOrCreate(task.buildingId);
                if (task.state == TaskState.AWAITING_RESOURCES) {
                    q.addParked(task.id);
                } else {
                    q.addActiveBatch(task.id);
                }
                if (q.getColonyId() == null && task.taskParams != null && task.taskParams.containsKey("colony_id")) {
                    try {
                        q.setColonyId(UUID.fromString(task.taskParams.get("colony_id").getAsString()));
                    } catch (Exception ignored) {}
                }
                if (!q.hasCompletionData() && task.taskParams != null && task.taskParams.containsKey("building_id")) {
                    Map<String, String> comp = new LinkedHashMap<>();
                    comp.put("building_id", task.buildingId.toString());
                    if (task.taskParams.containsKey("building_type")) {
                        comp.put("building_type", task.taskParams.get("building_type").getAsString());
                    }
                    if (task.taskParams.containsKey("recipe_id")) {
                        comp.put("recipe_id", task.taskParams.get("recipe_id").getAsString());
                    }
                    q.setCompletionData(comp);
                }
            }
        }
    }

    /** For persistence: restore a building queue. */
    public void putQueue(UUID buildingId, BuildingTaskQueue queue) {
        queues.put(buildingId, queue);
    }
}
