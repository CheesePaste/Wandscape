package com.wsteam.wandscape.task.engine.pool;

import com.wsteam.wandscape.task.engine.dsl.TriggerDeclaration;
import com.wsteam.wandscape.core.boundary.EventBus;
import com.wsteam.wandscape.core.types.ResourceStack;

import java.util.*;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.wsteam.wandscape.task.runtime.*;

/**
 * A task in the global pool. Tracks its lifecycle state, assigned NPC, and progress.
 */
public class GlobalTask {

    public final long id;
    public final TaskSequence sequence;
    public int priority;
    public final long createdAt;

    /** Blueprint ID used to compile this task. Null for pre-built test tasks. */
    @Nullable
    public String blueprintId;

    /** Building that owns this task (null for non-building tasks). */
    @Nullable
    public UUID buildingId;

    /** Whether this task is the current head of its building's queue. */
    public boolean isBuildingHead;

    /** Trigger declarations carried from the blueprint (downstream task rules). */
    public final List<TriggerDeclaration> triggers;

    /** Active event subscriptions (populated on assign, cleared on complete). */
    public final List<EventBus.Subscription> subscriptions;

    /** Original TaskRequest params (for EmitEventOp template resolution). */
    public final Map<String, JsonElement> taskParams;

    public TaskState state;
    public int stepIndex;
    public Long assignedNpcId;
    /** Resources this task is waiting for (null or empty = not waiting). */
    @Nullable
    public List<ResourceStack> awaitingResource;
    public final Deque<InterruptRecord> interruptHistory;
    public final ApprovalInfo approval;

    /**
     * Persisted channel checkpoint: remaining ticks of the in-flight
     * {@code block_interact} channel (production synthesize/decompose/craft_wand/
     * brew_potion, node gather). Written each tick by the executor while the
     * channel runs, cleared ({@code -1}) when it completes or is consumed on resume.
     * Survives save/load and task re-assignment so a half-finished craft resumes
     * mid-channel instead of restarting from zero.
     */
    public int channelRemainingTicks = -1;

    /**
     * Transient per-execution epoch, bumped each time the task's channel op starts.
     * Lets the executor detect and cancel stale channels from a released task that
     * was re-assigned (otherwise the orphaned countdown would still produce output).
     * Not persisted — no channel survives a reload, so resetting to 0 is correct.
     */
    public int channelEpoch = 0;

    public GlobalTask(
            long id,
            TaskSequence sequence,
            int priority,
            long createdAt,
            List<TriggerDeclaration> triggers,
            List<EventBus.Subscription> subscriptions,
            Map<String, JsonElement> taskParams,
            TaskState state,
            int stepIndex,
            Long assignedNpcId,
            @Nullable List<ResourceStack> awaitingResource,
            Deque<InterruptRecord> interruptHistory,
            ApprovalInfo approval
    ) {
        this.id = id;
        this.sequence = sequence;
        this.priority = priority;
        this.createdAt = createdAt;
        this.triggers = triggers != null ? new ArrayList<>(triggers) : Collections.emptyList();
        this.subscriptions = subscriptions != null ? subscriptions : new ArrayList<>();
        this.taskParams = taskParams != null ? new HashMap<>(taskParams) : Collections.emptyMap();
        this.state = state;
        this.stepIndex = stepIndex;
        this.assignedNpcId = assignedNpcId;
        this.awaitingResource = awaitingResource != null ? List.copyOf(awaitingResource) : null;
        this.interruptHistory = interruptHistory != null ? interruptHistory : new ArrayDeque<>();
        this.approval = approval;
    }

    // Convenience constructors

    public static GlobalTask create(long id, TaskSequence sequence,
                                     int priority, List<TriggerDeclaration> triggers,
                                     Map<String, JsonElement> taskParams,
                                     TaskState initialState,
                                     ApprovalInfo approval) {
        return new GlobalTask(id, sequence, priority,
                System.currentTimeMillis(),
                triggers, new ArrayList<>(), taskParams,
                initialState, 0, null, null,
                new ArrayDeque<>(), approval);
    }

    /** Create a small task that skips approval. */
    public static GlobalTask createSmall(long id, TaskSequence sequence,
                                          int priority, List<TriggerDeclaration> triggers,
                                          Map<String, JsonElement> taskParams) {
        return new GlobalTask(id, sequence, priority,
                System.currentTimeMillis(),
                triggers, new ArrayList<>(), taskParams,
                TaskState.PENDING_ASSIGN, 0, null, null,
                new ArrayDeque<>(), null);
    }

    public boolean isComplete() {
        return sequence.isComplete(stepIndex);
    }

    /** Record an interruption and release the NPC. */
    public void interrupt(long npcId, long timestamp) {
        interruptHistory.addLast(new InterruptRecord(npcId, timestamp, stepIndex));
        assignedNpcId = null;
        state = TaskState.PENDING_ASSIGN;
    }

    @Override
    public String toString() {
        return "GlobalTask[id=" + id + " state=" + state + " step=" + stepIndex
                + "/" + sequence.size() + " npc=" + assignedNpcId + "]";
    }
}
