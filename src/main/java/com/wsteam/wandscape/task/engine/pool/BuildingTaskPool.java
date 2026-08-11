package com.wsteam.wandscape.task.engine.pool;

import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.task.runtime.TaskState;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
     * @return the global task ID if a new head was published, or -1 if queued behind existing head
     */
    public long enqueue(UUID buildingId, WorkItem item, GlobalTaskPool pool) {
        BuildingTaskQueue queue = getOrCreate(buildingId);

        if (!queue.hasHead()) {
            TaskRequest request = new TaskRequest(
                    item.blueprintId(), item.params(), item.priority());
            long taskId = pool.addTaskFromBuilding(request, buildingId);
            queue.setHeadTaskId(taskId);
            return taskId;
        }

        queue.enqueue(item);
        return -1;
    }

    /**
     * Called when a building's head task completes or fails terminally.
     * Promotes the next pending WorkItem to head if any remain.
     */
    public void onHeadCompleted(UUID buildingId, GlobalTaskPool pool) {
        BuildingTaskQueue queue = queues.get(buildingId);
        if (queue == null) return;

        queue.clearHead();

        WorkItem next = queue.dequeueNext();
        if (next != null) {
            TaskRequest request = new TaskRequest(
                    next.blueprintId(), next.params(), next.priority());
            long taskId = pool.addTaskFromBuilding(request, buildingId);
            queue.setHeadTaskId(taskId);
            Log.info(TAG, "building {} promoted next #{} blueprint={} pending={}",
                    buildingId.toString().substring(0, 8), taskId,
                    next.blueprintId(), queue.pendingSize());
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
            queue.clearHead();
        }
        queue.addParked(taskId);
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

    /** For persistence: restore a building queue. */
    public void putQueue(UUID buildingId, BuildingTaskQueue queue) {
        queues.put(buildingId, queue);
    }
}
