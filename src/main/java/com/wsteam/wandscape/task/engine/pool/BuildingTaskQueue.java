package com.wsteam.wandscape.task.engine.pool;

import com.wsteam.wandscape.shared.data.WorkItem;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
/**
 * Per-building runtime task queue.
 * Only the head task enters the global pool; subsequent WorkItems wait here
 * and are promoted when the head completes.
 */
public class BuildingTaskQueue {

    private final Deque<WorkItem> pending = new ArrayDeque<>();

    @Nullable
    private Long headTaskId;

    public void enqueue(WorkItem item) {
        pending.addLast(item);
    }

    /** Pop the next WorkItem to promote to head. Returns null if empty. */
    @Nullable
    public WorkItem dequeueNext() {
        return pending.pollFirst();
    }

    public boolean hasPending() {
        return !pending.isEmpty();
    }

    public int pendingSize() {
        return pending.size();
    }

    @Nullable
    public Long getHeadTaskId() {
        return headTaskId;
    }

    public void setHeadTaskId(@Nullable Long taskId) {
        this.headTaskId = taskId;
    }

    public boolean hasHead() {
        return headTaskId != null;
    }

    public void clearHead() {
        this.headTaskId = null;
    }

    /** Snapshot of pending WorkItems for UI/persistence. */
    public Deque<WorkItem> getPending() {
        return new ArrayDeque<>(pending);
    }
}
