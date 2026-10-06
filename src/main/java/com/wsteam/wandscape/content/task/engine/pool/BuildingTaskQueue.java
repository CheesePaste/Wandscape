package com.wsteam.wandscape.content.task.engine.pool;

import com.wsteam.wandscape.content.building.data.WorkItem;
import com.wsteam.wandscape.content.building.internal.ConstructionBatches;

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

    /** Active batch task IDs currently in GlobalTaskPool for multi-worker construction.
     *  {@link LinkedHashSet}：插入顺序 = Phase 2 释放顺序 = 批次下标顺序，完成游标要靠它算「队首连续完成」。 */
    private final Set<Long> activeBatchIds = new LinkedHashSet<>();

    /** Subsequent batches waiting to be published (e.g. after prep/foundation batch finishes). */
    private final List<TaskRequest> pendingBatches = new ArrayList<>();

    /** Completion event data to emit when all batches finish. */
    @Nullable
    private Map<String, String> completionData;

    /**
     * 拆批建造的持久化记录（标量：分批尺寸快照 / 准备是否完成 / **完成游标** / 总方块数 + 重建所需的
     * 优先级与清盒标志）。批次 params 不落盘，读档时用建筑 JSON 重建 WorkItem 再重新分批。
     *
     * <p>它在队列里的作用有两条：一是存档的唯一依据；二是让「只剩一个待释放批次计划」的队列
     * 不被判成空队列（{@link #hasHead()} / {@link #isEmpty()}）—— 否则读档后第一步就被
     * {@code pruneParked} 清掉，建筑永远差几千格、且不留痕。
     */
    @Nullable
    private ConstructionBatches.BatchJob batchJob;

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
        return headTaskId != null || !activeBatchIds.isEmpty() || !pendingBatches.isEmpty()
                || completionData != null || batchJob != null;
    }

    public void clearHead() {
        this.headTaskId = null;
        this.activeBatchIds.clear();
        this.pendingBatches.clear();
        this.completionData = null;
        this.batchJob = null;
    }

    // ── 拆批建造的持久化记录 ──

    public boolean hasBatchJob() {
        return batchJob != null;
    }

    @Nullable
    public ConstructionBatches.BatchJob getBatchJob() {
        return batchJob;
    }

    public void setBatchJob(@Nullable ConstructionBatches.BatchJob job) {
        this.batchJob = job;
    }

    /**
     * 推进完成游标：{@code leadingCompleted} 是**从队首连续完成**的 placement 批次数。
     * 只由 {@code BuildingTaskPool.checkBatchesProgress} 在剪除完成批次时调用 ——
     * 那里才知道「这批不会再被任何任务覆盖」，且能拿到 release 顺序（{@link #activeBatchIds}
     * 是 LinkedHashSet，插入序 = Phase 2 的释放序 = 批次下标序）。
     *
     * <p>游标单调递增：乱序完成时宁可落后（那几批读档会被重跑一次），也绝不越过尚未完成的批次。
     * 不动 {@code prepDone} —— 它只由释放分支推进（见 {@link #markPrepDone()}），
     * 这样读档能靠「prep 未完成却有完成游标」这条不可能组合抓出记录错位。
     */
    public void advanceBatchCursor(int leadingCompleted) {
        if (batchJob == null || leadingCompleted <= 0) return;
        batchJob = new ConstructionBatches.BatchJob(batchJob.batchSize(), batchJob.prepDone(),
                batchJob.completedBatches() + leadingCompleted, batchJob.blockCount(),
                batchJob.priority(), batchJob.clearBox());
    }

    /**
     * Phase 2 把待发批次一次性全放出去了 ⇒ 准备批次（扣料 + 清盒）至此被判定为已完成。
     * 释放只可能发生在「没有活跃、也没有停泊批次」时，所以这个位置就是 prep 完成的唯一判据。
     */
    public void markPrepDone() {
        if (batchJob == null || batchJob.prepDone()) return;
        batchJob = new ConstructionBatches.BatchJob(batchJob.batchSize(), true,
                batchJob.completedBatches(), batchJob.blockCount(),
                batchJob.priority(), batchJob.clearBox());
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

    public void unparkBatch(long taskId) {
        parkedTaskIds.remove(taskId);
        activeBatchIds.add(taskId);
    }

    public boolean hasCompletionData() {
        return completionData != null;
    }

    public boolean isEmpty() {
        return headTaskId == null
                && activeBatchIds.isEmpty()
                && parkedTaskIds.isEmpty()
                && pending.isEmpty()
                && pendingBatches.isEmpty()
                && completionData == null
                && batchJob == null;
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
