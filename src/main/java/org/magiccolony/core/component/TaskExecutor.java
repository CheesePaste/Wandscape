package org.magiccolony.core.component;

import org.magiccolony.core.op.AtomicOp;
import org.magiccolony.core.task.ExecutorState;
import org.magiccolony.core.task.TaskSequence;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

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
    public Map<String, String> taskParams = null;

    /** Local execution state. */
    public ExecutorState state = ExecutorState.IDLE;

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

    /** Reset to idle state, clearing all task data. */
    public void reset() {
        privateQueue.clear();
        globalTaskId = null;
        currentSequence = null;
        stepIndex = 0;
        taskParams = null;
        state = ExecutorState.IDLE;
    }

    /** Clear global task state (used when task is interrupted or completes). */
    public void releaseGlobalTask() {
        globalTaskId = null;
        currentSequence = null;
        stepIndex = 0;
        taskParams = null;
        state = ExecutorState.IDLE;
    }
}
