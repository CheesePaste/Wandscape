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
                return enqueueBatches(buildingId, colonyId, ConstructionBatches.split(item, colonyId), pool);
            }
            TaskRequest request = new TaskRequest(
                    item.blueprintId(), item.params(), item.priority(), colonyId);
            long taskId = pool.addTaskFromBuilding(request, buildingId);
            queue.setHeadTaskId(taskId);
            return taskId;
        }

        queue.enqueue(item);
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
        queue.setCompletionData(split.completionData());

        boolean needsPrep = ConstructionBatches.needsPreparation(split.initialBatch());
        if (needsPrep) {
            long taskId = pool.addTaskFromBuilding(split.initialBatch(), buildingId);
            queue.addActiveBatch(taskId);
            queue.setPendingBatches(split.remainingBatches());
            Log.info(TAG, "building {} published prep batch #{} ({} pending placement batches)",
                    buildingId.toString().substring(0, 8), taskId, split.remainingBatches().size());
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
    }

    public boolean hasActiveBatches(UUID buildingId) {
        BuildingTaskQueue queue = queues.get(buildingId);
        return queue != null && queue.hasActiveBatches();
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
        if (!queue.hasActiveBatches() && !queue.hasPendingBatches() && queue.getCompletionData() == null) {
            return false;
        }

        // 1. Prune finished active batches
        List<Long> done = new ArrayList<>();
        for (long batchId : queue.getActiveBatchIds()) {
            GlobalTask task = pool.get(batchId);
            if (task == null || task.state == TaskState.COMPLETED) {
                done.add(batchId);
            }
        }
        for (long batchId : done) {
            queue.removeActiveBatch(batchId);
        }

        // 2. Prune finished parked batches
        if (queue.hasParked()) {
            List<Long> doneParked = new ArrayList<>();
            for (long parkedId : queue.getParkedTaskIds()) {
                GlobalTask task = pool.get(parkedId);
                if (task == null || task.state == TaskState.COMPLETED) {
                    doneParked.add(parkedId);
                }
            }
            for (long parkedId : doneParked) {
                queue.removeParked(parkedId);
            }
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
        if (queue.hasParked()) {
            for (long taskId : queue.getParkedTaskIds()) {
                GlobalTask task = pool.get(taskId);
                if (task == null || task.state == TaskState.COMPLETED) {
                    queue.removeParked(taskId);
                }
            }
        }
        // Clean up a queue left empty after its parked tasks completed with no new head.
        if (!queue.hasHead() && !queue.hasParked() && !queue.hasPending()) {
            queues.remove(buildingId, queue);
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
                q.addActiveBatch(task.id);
            }
        }
    }

    /** For persistence: restore a building queue. */
    public void putQueue(UUID buildingId, BuildingTaskQueue queue) {
        queues.put(buildingId, queue);
    }
}
