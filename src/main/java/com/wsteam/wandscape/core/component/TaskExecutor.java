package com.wsteam.wandscape.core.component;

import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.task.ExecutorState;
import com.wsteam.wandscape.core.task.TaskSequence;
import com.wsteam.wandscape.core.types.GridPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;

/**
 * NPC-side task execution state.
 * Holds the private queue (high priority) and current global task progress.
 */
public class TaskExecutor {

    /** High-priority private task queue (FIFO). */
    public final Deque<AtomicOp> privateQueue = new ArrayDeque<>();

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

    /**
     * Ticks since this NPC last had work. Incremented each tick while idle.
     * When this crosses {@code WAND_RETURN_DELAY}, equipped wands are pushed
     * as WandReturnOps so another NPC can use them.
     * Reset to 0 when a new task is assigned.
     */
    public int wandIdleTicks = 0;

    /** Ticks of idle time before equipped wands are auto-returned to warehouse. */
    public static final int WAND_RETURN_DELAY_TICKS = 60; // 3 seconds

    /** Push an op to the back of the private queue. */
    public void pushPrivate(AtomicOp op) {
        privateQueue.addLast(op);
    }

    /** Push an op to the front of the private queue (for priority insertion). */
    public void pushPrivateFront(AtomicOp op) {
        privateQueue.addFirst(op);
    }

    /** Peek at the next private op without removing it. */
    public AtomicOp peekPrivate() {
        return privateQueue.peekFirst();
    }

    /** Pop the next private op. */
    public AtomicOp popPrivate() {
        return privateQueue.pollFirst();
    }

    /** Check if the private queue is empty. */
    public boolean isPrivateQueueEmpty() {
        return privateQueue.isEmpty();
    }

    /** Whether this executor has any work to do. */
    public boolean hasWork() {
        return !privateQueue.isEmpty() || globalTaskId != null;
    }

    /** Reset all state. */
    public void reset() {
        privateQueue.clear();
        globalTaskId = null;
        currentSequence = null;
        stepIndex = 0;
        taskParams = null;
        pendingFuture = null;
        pendingFutureIsNav = false;
        currentOpTarget = null;
        currentOpKind = null;
        stance = null;
        wandIdleTicks = 0;
        state = ExecutorState.IDLE;
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
        // NOTE: wandIdleTicks is intentionally NOT reset here —
        // it tracks idle time across task boundaries for wand return delay.
        state = ExecutorState.IDLE;
    }
}
