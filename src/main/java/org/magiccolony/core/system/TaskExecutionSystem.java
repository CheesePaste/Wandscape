package org.magiccolony.core.system;

import org.magiccolony.core.Log;
import org.magiccolony.core.boundary.ColonyResourceAccess;
import org.magiccolony.core.boundary.RitualOps;
import org.magiccolony.core.component.*;
import org.magiccolony.core.ecs.System;
import org.magiccolony.core.ecs.World;
import org.magiccolony.core.op.*;
import org.magiccolony.core.task.ExecutorState;
import org.magiccolony.core.task.GlobalTaskPool;
import org.magiccolony.core.task.TaskSequence;
import org.magiccolony.core.types.ResourceStack;
import org.magiccolony.core.types.RitualId;

import java.util.List;

/**
 * Drives NPC task execution each tick.
 * Iterates all NPCs, runs the current AtomicOp, handles DONE/WAITING results.
 *
 * <p>Tick flow (V2 pure/side-effect batch processing):
 * <ol>
 *   <li>Determine current AtomicOp (private queue first, then global task)</li>
 *   <li>Pure ops (EmitEventOp, IfConditionOp) execute continuously — no mana cost,
 *       no tick boundary. They handle their own step advancement.</li>
 *   <li>Side-effect ops execute one-at-a-time with mana check. After execution,
 *       the inner loop exits (next tick picks up the next step).</li>
 * </ol>
 */
public class TaskExecutionSystem implements System {

    private static final String TAG = "TaskExec";
    private static final RitualId ITEM_TELEPORT = new RitualId("item_teleport");

    private final GlobalTaskPool taskPool;

    public TaskExecutionSystem(GlobalTaskPool taskPool) {
        this.taskPool = taskPool;
    }

    @Override
    public void update(World world, float delta) {
        OpExecutorRegistry registry = world.opExecutors;
        if (registry == null) return;

        List<Long> npcs = world.query(Position.class, ManaPool.class, TaskExecutor.class,
                WandCarrier.class, Inventory.class);

        Log.debug(TAG, "processing %d NPCs", npcs.size());

        for (long npcId : npcs) {
            TaskExecutor exec = world.get(npcId, TaskExecutor.class);
            if (exec == null || !exec.hasWork()) continue;

            // ---- Batch processing: pure ops loop, side-effect ops one-per-tick ----
            processNpc(world, npcId, exec, registry);
        }
    }

    /** Process one NPC's work for this tick. */
    private void processNpc(World world, long npcId, TaskExecutor exec,
                            OpExecutorRegistry registry) {
        while (exec.hasWork()) {

            // ---- 1. Determine current AtomicOp ----
            boolean isPrivate = !exec.isPrivateQueueEmpty();
            AtomicOp currentOp;

            if (isPrivate) {
                currentOp = exec.peekPrivate();
            } else if (exec.globalTaskId != null && exec.currentSequence != null) {
                if (exec.currentSequence.isComplete(exec.stepIndex)) {
                    taskPool.completeTask(exec.globalTaskId, npcId);
                    exec.releaseGlobalTask();
                    return; // task done, no more work for this NPC
                }
                currentOp = exec.currentSequence.get(exec.stepIndex);
            } else {
                return; // No work
            }

            String opName = currentOp.getClass().getSimpleName();
            boolean isPure = isPureOp(currentOp);

            Log.debug(TAG, "NPC %d - %s %s (step=%d private=%s pure=%s)",
                    npcId, isPrivate ? "private" : "global#" + exec.globalTaskId,
                    opName, exec.stepIndex, isPrivate, isPure);

            // ---- 2. Handle ResourceRequestOp inline (side-effect) ----
            if (currentOp instanceof AtomicOp.ResourceRequestOp resOp) {
                if (processResourceRequest(resOp, world, npcId, exec, isPrivate)) {
                    // DONE — continue inner loop (ResourceRequestOp fulfilled,
                    // move to next step immediately)
                    continue;
                } else {
                    // WAITING — exit inner loop
                    break;
                }
            }

            // ---- 3. Mana check (side-effect ops only) ----
            if (!isPure) {
                ManaPool mana = world.get(npcId, ManaPool.class);
                WandCarrier wc = world.get(npcId, WandCarrier.class);
                if (mana == null || wc == null) return;

                int baseCost = currentOp.baseManaCost();
                int actualCost = Math.max(1, Math.round(baseCost * wc.bestManaEfficiency()));
                if (mana.current() < actualCost) {
                    Log.debug(TAG, "NPC %d - mana %d < %d, skipping %s",
                            npcId, mana.current(), actualCost, opName);
                    return; // No mana — stop processing this NPC
                }

                // ---- 4. Execute side-effect op ----
                @SuppressWarnings("unchecked")
                OpExecutor<AtomicOp> executor = (OpExecutor<AtomicOp>) (Object) registry.get(currentOp.getClass());
                if (executor == null) return;

                OpResult result;
                if (currentOp instanceof AtomicOp.RitualOp ritOp && exec.state != ExecutorState.WAITING) {
                    world.ritualOps.beginRitual(ritOp.ritual(), ritOp.target(), world, npcId);
                    result = OpResult.WAITING;
                    Log.debug(TAG, "NPC %d - RitualOp begin %s", npcId, ritOp.ritual().id());
                } else if (currentOp instanceof AtomicOp.RitualOp ritOp) {
                    result = world.ritualOps.pollRitual(ritOp.ritual(), ritOp.target(), world, npcId);
                    Log.debug(TAG, "NPC %d - RitualOp poll %s → %s", npcId, ritOp.ritual().id(), result);
                } else {
                    result = executor.execute(currentOp, world, npcId);
                }

                // ---- 5. Process result ----
                switch (result) {
                    case DONE -> {
                        mana.consume(actualCost);
                        exec.state = ExecutorState.ACTIVE;
                        Log.debug(TAG, "NPC %d - %s DONE (mana -%d = %d)",
                                npcId, opName, actualCost, mana.current());

                        if (isPrivate) {
                            exec.popPrivate();
                        } else {
                            advanceGlobalStep(exec, npcId, 1);
                        }
                    }
                    case WAITING -> {
                        exec.state = ExecutorState.WAITING;
                        Log.debug(TAG, "NPC %d - %s WAITING", npcId, opName);
                    }
                }
                // Side-effect op: exit inner loop regardless of result
                break;
            }

            // ---- 6. Execute pure op (continuous) ----
            @SuppressWarnings("unchecked")
            OpExecutor<AtomicOp> pureExecutor =
                    (OpExecutor<AtomicOp>) (Object) registry.get(currentOp.getClass());
            if (pureExecutor == null) return;

            pureExecutor.execute(currentOp, world, npcId);
            // Pure ops handle their own advancement (stepIndex / private queue)
            // The inner loop continues automatically
        }

        // After the work loop, if nothing left to do, mark NPC idle
        if (!exec.hasWork()) {
            exec.state = ExecutorState.IDLE;
        }
    }

    /**
     * Handle a ResourceRequestOp inline.
     * @return true if fulfilled (DONE), false if WAITING
     */
    private boolean processResourceRequest(AtomicOp.ResourceRequestOp op, World world,
                                           long npcId, TaskExecutor exec, boolean isPrivate) {
        ColonyResourceAccess resources = world.colonyResources;
        ResourceStack requested = op.requested();

        int available = resources.available(requested.resource());
        Log.debug(TAG, "NPC %d - ResourceRequestOp %s (available=%d)",
                npcId, requested, available);

        if (resources.hasEnough(requested.resource(), requested.amount())) {
            if (resources.reserve(requested.resource(), requested.amount())) {
                Inventory inv = world.get(npcId, Inventory.class);
                if (inv != null && inv.add(requested)) {
                    resources.commit(requested.resource(), requested.amount());
                    Log.debug(TAG, "NPC %d - ResourceRequestOp fulfilled: %s → inventory",
                            npcId, requested);
                } else {
                    resources.release(requested.resource(), requested.amount());
                    exec.state = ExecutorState.WAITING;
                    Log.debug(TAG, "NPC %d - inventory full, WAITING", npcId);
                    return false;
                }
            }

            if (isPrivate) {
                exec.popPrivate();
            } else {
                advanceGlobalStep(exec, npcId, 1);
            }
            exec.state = ExecutorState.ACTIVE;
            return true;
        } else {
            exec.state = ExecutorState.WAITING;
            Log.debug(TAG, "NPC %d - resource shortage: need %s, have %d",
                    npcId, requested, available);

            if (!isPrivate && exec.globalTaskId != null) {
                taskPool.markAwaitingResources(exec.globalTaskId, npcId, requested, world);
            }
            return false;
        }
    }

    /** Advance global step index and check for completion. */
    private void advanceGlobalStep(TaskExecutor exec, long npcId, int delta) {
        int nextStep = exec.stepIndex + delta;
        exec.stepIndex = nextStep;
        taskPool.advanceStep(exec.globalTaskId, nextStep);

        if (exec.currentSequence.isComplete(nextStep)) {
            taskPool.completeTask(exec.globalTaskId, npcId);
            exec.releaseGlobalTask();
            Log.info(TAG, "NPC %d - global #%d all steps complete", npcId, exec.globalTaskId);
        }
    }

    /** Check if an op is pure (no side effects on world state). */
    static boolean isPureOp(AtomicOp op) {
        return op instanceof AtomicOp.EmitEventOp || op instanceof AtomicOp.IfConditionOp;
    }
}
