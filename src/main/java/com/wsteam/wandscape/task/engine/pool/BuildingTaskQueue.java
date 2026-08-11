package com.wsteam.wandscape.task.engine.pool;

import com.wsteam.wandscape.shared.data.WorkItem;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
/**
 * Per-building runtime task queue.
 * Only the head task enters the global pool; subsequent WorkItems wait here
 * and are promoted when the head completes.
 *
 * <p>Parked tasks are heads that hit a resource shortage and went
 * {@code AWAITING_RESOURCES}. They no longer block the queue (the next WorkItem
 * is promoted), but they are tracked so the footprint lease is held until they
 * resume and complete.
 */
public class BuildingTaskQueue {

    private final Deque<WorkItem> pending = new ArrayDeque<>();

    /** Tasks that were heads but are parked waiting for resources. */
    private final Set<Long> parkedTaskIds = new HashSet<>();

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

    // ── Parked (resource-waiting) task tracking ──

    public void addParked(long taskId) {
        parkedTaskIds.add(taskId);
    }

    public void removeParked(long taskId) {
        parkedTaskIds.remove(taskId);
    }

    public boolean hasParked() {
        return !parkedTaskIds.isEmpty();
    }

    /** Snapshot of parked task ids (safe to iterate while removing). */
    public Set<Long> getParkedTaskIds() {
        return new HashSet<>(parkedTaskIds);
    }

    /** Snapshot of pending WorkItems for UI/persistence. */
    public Deque<WorkItem> getPending() {
        return new ArrayDeque<>(pending);
    }
}
