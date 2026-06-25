package com.wsteam.wandscape.core.component;

import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.task.ExecutorState;
import com.wsteam.wandscape.core.task.NpcTaskPackage;
import com.wsteam.wandscape.core.task.TaskSequence;
import com.wsteam.wandscape.core.types.GridPos;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;

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

    // ── Backward-compat wrappers (delegate to NpcTaskQueue) ──

    /** Ensure a pending package is started if we have work but no current package. */
    private void ensureCurrentPackage() {
        if (npcQueue.currentPackage() == null && npcQueue.hasPending()) {
            npcQueue.startNextPending();
        }
    }

    /** @deprecated use {@link #npcQueue}.enqueueNormal() */
    @Deprecated
    public void pushPrivate(AtomicOp op) {
        var pkg = NpcTaskPackage.system("system:legacy", op, null, 0);
        npcQueue.enqueueNormal(pkg);
        if (npcQueue.currentPackage() == null) {
            npcQueue.startNextPending();
        }
    }

    /** @deprecated use {@link #npcQueue}.enqueueUrgent() */
    @Deprecated
    public void pushPrivateFront(AtomicOp op) {
        var pkg = NpcTaskPackage.system("system:legacy_urgent", op, null, 99);
        npcQueue.enqueueUrgent(pkg);
    }

    /** @deprecated use {@link #npcQueue}.peekCurrentOp() */
    @Deprecated
    public AtomicOp peekPrivate() {
        ensureCurrentPackage();
        return npcQueue.peekCurrentOp();
    }

    /** @deprecated use {@link #npcQueue}.advanceStep() */
    @Deprecated
    public AtomicOp popPrivate() {
        ensureCurrentPackage();
        AtomicOp op = npcQueue.peekCurrentOp();
        npcQueue.advanceStep();
        if (npcQueue.isCurrentPackageDone()) {
            npcQueue.finishCurrentPackage();
        }
        return op;
    }

    /** @deprecated use {@link #npcQueue}.hasPending() */
    @Deprecated
    public boolean isPrivateQueueEmpty() {
        return npcQueue.isIdle();
    }

    /** @deprecated wand lifecycle now managed by WandLifecycle */
    @Deprecated
    public int wandIdleTicks = 0;

    /** @deprecated wand lifecycle now managed by WandLifecycle */
    @Deprecated
    public static final int WAND_RETURN_DELAY_TICKS = 60;

    /** @deprecated use {@link #npcQueue}.hasWork() */
    @Deprecated
    public boolean hasWork() {
        return npcQueue.hasWork() || globalTaskId != null;
    }

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
    }
}
