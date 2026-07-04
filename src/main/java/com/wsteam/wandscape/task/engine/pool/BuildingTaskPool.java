package com.wsteam.wandscape.task.engine.pool;

import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.data.WorkItem;

import javax.annotation.Nullable;
import java.util.Map;
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
            Log.debug(TAG, "building {} head published #{} blueprint={}",
                    buildingId.toString().substring(0, 8), taskId, item.blueprintId());
            return taskId;
        }

        queue.enqueue(item);
        Log.debug(TAG, "building {} queued (behind head #{}), pending={}",
                buildingId.toString().substring(0, 8), queue.getHeadTaskId(), queue.pendingSize());
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
            Log.debug(TAG, "building {} queue empty", buildingId.toString().substring(0, 8));
            queues.remove(buildingId); // clean up empty queue
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
