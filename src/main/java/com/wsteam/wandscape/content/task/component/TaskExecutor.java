package com.wsteam.wandscape.content.task.component;
import com.wsteam.wandscape.content.task.NpcTaskQueue;

import com.google.gson.JsonElement;
import com.wsteam.wandscape.content.task.types.GridPos;
import com.wsteam.wandscape.content.task.runtime.ExecutorState;
import com.wsteam.wandscape.content.task.runtime.NpcTaskPackage;
import com.wsteam.wandscape.content.task.runtime.TaskSequence;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
/**
 * NPC-side task execution state.
 * Holds the private queue (high priority) and current global task progress.
 */
public class TaskExecutor {

    /** Per-NPC task queue — stores {@link NpcTaskPackage}s instead of bare ops. */
    public final NpcTaskQueue npcQueue = new NpcTaskQueue();

    /** Currently assigned global task ID, or null. */
    public Long globalTaskId = null;

    /** Blueprint of the current global task (set by GlobalTaskPool.assign). */
    public TaskSequence currentSequence = null;

    /** Index into currentSequence.steps() for the next op to execute. */
    public int stepIndex = 0;

    /** Original TaskRequest params (set by GlobalTaskPool.assign). Used by EmitEventOp template resolution. */
    public Map<String, JsonElement> taskParams = null;

    /** Local execution state. */
    public ExecutorState state = ExecutorState.IDLE;

    /**
     * Pending async future for the current step. Non-null means this step
     * has been submitted (via executor.execute() or navigator) and is
     * awaiting completion.
     *
     * <p>When the future resolves:
     * <ul><li>If {@link #pendingFutureIsNav} is true: was a nav future — do NOT
     *     advance stepIndex, just continue to execute the op.</li>
     *     <li>If false: was an op execution future — advance stepIndex
     *     (the op was already performed via the future's callback).</li></ul>
     */
    public CompletableFuture<Void> pendingFuture = null;

    /** Whether the pending future is from navigation (not op execution). */
    public boolean pendingFutureIsNav = false;

    /**
     * Current op world-position target, for visual feedback.
     * Set by TaskExecutionSystem before executing an op; cleared on step advance.
     * The NPC renderer reads this indirectly via {@code getDebugTarget()}.
     */
    @Nullable
    public GridPos currentOpTarget = null;

    /**
     * Kind of the currently executing op: "transform", "block_interact", "ritual", or null.
     * Used by the renderer to choose visual effects:
     * - transform/block_interact → wand beam + target particles
     * - ritual → magic circle at target position
     */
    @Nullable
    public String currentOpKind = null;

    /**
     * Fixed standoff position for the current task, computed from the bounding box
     * of all position-bearing op targets. When non-null, per-op navigation is skipped
     * — the NPC stays at this position and only rotates to face each target.
     */
    @Nullable
    public GridPos stance = null;

    /** Tick when this NPC last performed work. Used for idle detection. */
    public long lastWorkTick = 0;

    /**
     * Active package source token currently executing (e.g. "global:123" or "self_defense").
     * Used by TaskExecutionSystem to detect when a new or resumed package starts.
     */
    @Nullable
    public String activePackageSource = null;

    /**
     * Whether the initial navigation toward the task stance/target has completed.
     * When true, task ops execute without distance checks.
     */
    public boolean initialNavDone = false;

    /**
     * 多法师协同建造的「批次续接」意图：某批次完工时若同栋建筑还有别的批次，这里记下工地，
     * 由 {@code TaskExecutionSystem} 在下一次空闲时直接把下一条批次续给同一个 NPC，
     * 不必等调度器心跳——否则每批之间都空转一次，表现为「干几秒、停一下、来回跑」。
     * {@code untilTick} 过后由执行系统自行丢弃；{@link #reset()} 一并清掉，
     * 但 {@link #releaseGlobalTask()} **不清**——续接意图本就是跨任务存在的。
     */
    @Nullable
    public BatchContinuation continuation = null;

    /**
     * 批次续接意图（纯数据）。
     *
     * @param buildingId 工地（建筑 id）
     * @param colonyId   归属小镇；可空（无主任务不做续接）
     * @param untilTick  失效时刻（近似 tick，见 {@code TaskExecutionSystem.worldTick}）
     */
    public record BatchContinuation(UUID buildingId, @Nullable UUID colonyId, long untilTick) {}

    /** Reset all state. */
    public void reset() {
        npcQueue.clear();
        globalTaskId = null;
        currentSequence = null;
        stepIndex = 0;
        taskParams = null;
        pendingFuture = null;
        pendingFutureIsNav = false;
        currentOpTarget = null;
        currentOpKind = null;
        stance = null;
        lastWorkTick = 0;
        state = ExecutorState.IDLE;
        activePackageSource = null;
        initialNavDone = false;
        continuation = null;
    }

    /** Clear global task state (used when task is interrupted or completes). */
    public void releaseGlobalTask() {
        globalTaskId = null;
        currentSequence = null;
        stepIndex = 0;
        taskParams = null;
        pendingFuture = null;
        pendingFutureIsNav = false;
        currentOpTarget = null;
        currentOpKind = null;
        stance = null;
        state = ExecutorState.IDLE;
        activePackageSource = null;
        initialNavDone = false;
    }
}
