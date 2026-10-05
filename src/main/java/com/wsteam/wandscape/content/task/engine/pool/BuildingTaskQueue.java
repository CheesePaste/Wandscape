package com.wsteam.wandscape.content.task.engine.pool;

import com.wsteam.wandscape.content.building.data.WorkItem;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Per-building runtime task queue.
 * Only the head task (or active construction batch tasks) enters the global pool;
 * subsequent WorkItems wait here and are promoted when the head completes.
 *
 * <p>Parked tasks are heads that hit a resource shortage and went
 * {@code AWAITING_RESOURCES}. They no longer block the queue (the next WorkItem
 * is promoted), but they are tracked so the footprint lease is held until they
 * resume and complete.
 *
 * <p>Multi-worker construction:
 * Supports multiple concurrent active batch tasks for large buildings.
 */
public class BuildingTaskQueue {

    private final Deque<WorkItem> pending = new ArrayDeque<>();

    /** Tasks that were heads but are parked waiting for resources. */
    private final Set<Long> parkedTaskIds = new HashSet<>();

    /** Active batch task IDs currently in GlobalTaskPool for multi-worker construction. */
    private final Set<Long> activeBatchIds = new HashSet<>();

    /** Subsequent batches waiting to be published (e.g. after prep/foundation batch finishes). */
    private final List<TaskRequest> pendingBatches = new ArrayList<>();

    /** Completion event data to emit when all batches finish. */
    @Nullable
    private Map<String, String> completionData;

    @Nullable
    private UUID colonyId;

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
        if (headTaskId != null) return headTaskId;
        if (!activeBatchIds.isEmpty()) return activeBatchIds.iterator().next();
        return null;
    }

    public void setHeadTaskId(@Nullable Long taskId) {
        this.headTaskId = taskId;
    }

    public boolean hasHead() {
        return headTaskId != null || !activeBatchIds.isEmpty();
    }

    public void clearHead() {
        this.headTaskId = null;
        this.activeBatchIds.clear();
        this.pendingBatches.clear();
        this.completionData = null;
    }

    // ── Multi-worker batch tracking ──

    public void addActiveBatch(long taskId) {
        activeBatchIds.add(taskId);
    }

    public void removeActiveBatch(long taskId) {
        activeBatchIds.remove(taskId);
    }

    public boolean hasActiveBatches() {
        return !activeBatchIds.isEmpty();
    }

    public Set<Long> getActiveBatchIds() {
        return Collections.unmodifiableSet(activeBatchIds);
    }

    public boolean hasPendingBatches() {
        return !pendingBatches.isEmpty();
    }

    public List<TaskRequest> getPendingBatches() {
        return List.copyOf(pendingBatches);
    }

    public void setPendingBatches(List<TaskRequest> batches) {
        pendingBatches.clear();
        if (batches != null) {
            pendingBatches.addAll(batches);
        }
    }

    public void clearPendingBatches() {
        pendingBatches.clear();
    }

    @Nullable
    public Map<String, String> getCompletionData() {
        return completionData;
    }

    public void setCompletionData(@Nullable Map<String, String> data) {
        this.completionData = data != null ? Map.copyOf(data) : null;
    }

    public void clearCompletionData() {
        this.completionData = null;
    }

    @Nullable
    public UUID getColonyId() {
        return colonyId;
    }

    public void setColonyId(@Nullable UUID colonyId) {
        this.colonyId = colonyId;
    }

    public Set<Long> getAllActiveTaskIds() {
        Set<Long> all = new HashSet<>(activeBatchIds);
        if (headTaskId != null) all.add(headTaskId);
        return all;
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
