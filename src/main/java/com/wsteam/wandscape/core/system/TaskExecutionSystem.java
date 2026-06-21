package com.wsteam.wandscape.core.system;

import com.wsteam.wandscape.core.Log;
import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.core.boundary.MovementOps;
import com.wsteam.wandscape.core.component.*;
import com.wsteam.wandscape.core.ecs.System;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.*;
import com.wsteam.wandscape.core.task.ExecutorState;
import com.wsteam.wandscape.core.task.GlobalTaskPool;
import com.wsteam.wandscape.core.task.TaskSequence;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.core.types.RitualId;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Drives NPC task execution — one engine logic tick.
 *
 * <p>V2.5 async model:
 * <ol>
 *   <li>For each NPC, check if a pending async future exists:
 *       <ul><li>Done → advance stepIndex, clear pending, continue to next op</li>
 *           <li>Not done → skip this NPC (still in-flight)</li></ul></li>
 *   <li>No pending → determine current op → call {@code executor.execute()} → future</li>
 *   <li>Future already done (sync) → advance stepIndex (pure ops batch-continue)</li>
 *   <li>Future not done (async) → store as pending → break (side-effect boundary)</li>
 * </ol>
 *
 * <p>An op is NEVER re-invoked. Async ops return an incomplete future;
 * the engine waits (via {@link World#hasPendingAsyncOps() gate}) and
 * advances the stepIndex when the future completes.
 */
public class TaskExecutionSystem implements System {

    private static final String TAG = "TaskExec";
    private static final double NAV_RANGE_SQ = 25.0; // 5² — horizontal operating range
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

        for (long npcId : npcs) {
            TaskExecutor exec = world.get(npcId, TaskExecutor.class);
            if (exec == null) continue;

            if (!exec.hasWork()) {
                continue;
            }

            processNpc(world, npcId, exec, registry);
        }
    }

    private void processNpc(World world, long npcId, TaskExecutor exec,
                            OpExecutorRegistry registry) {
        // ---- 0. Compute task stance (once per task, from op targets' bounding box) ----
        if (exec.stance == null && exec.currentSequence != null) {
            exec.stance = computeTaskStance(exec.currentSequence);
        }

        // ---- 0.5 Navigate to stance if far away ----
        if (exec.stance != null && exec.pendingFuture == null) {
            Position pos = world.get(npcId, Position.class);
            if (pos != null) {
                double dx = pos.pos().x() - exec.stance.x();
                double dz = pos.pos().z() - exec.stance.z();
                if (dx * dx + dz * dz > NAV_RANGE_SQ && world.movementOps != null) {
                    MovementOps mov = world.movementOps;
                    CompletableFuture<Void> navFuture = mov.navigateTo(
                            npcId, exec.stance.x(), exec.stance.y(), exec.stance.z());
                    exec.pendingFuture = navFuture;
                    exec.pendingFutureIsNav = true;
                    Log.debug(TAG, "NPC %d — navigating to stance %s", npcId, exec.stance);
                    return;
                }
            }
        }

        while (exec.hasWork()) {

            // ---- 1. Pending async future from previous tick? ----
            if (exec.pendingFuture != null) {
                if (!exec.pendingFuture.isDone()) {
                    return; // still waiting — skip this NPC
                }
                // Future resolved
                Log.debug(TAG, "NPC %d — future resolved (wasNav=%b)", npcId, exec.pendingFutureIsNav);
                exec.pendingFuture = null;
                if (!exec.pendingFutureIsNav) {
                    // Op future (e.g. AsyncTransformExecutor delay) —
                    // the op already executed via future's thenRun callback.
                    advanceStep(exec, npcId, 1);
                }
                // Nav future → do NOT advance. Continue to re-check
                // range (now in-range) and execute the operation.
                continue; // process next op (or re-process same op after nav)
            }

            // ---- 2. Determine current AtomicOp ----
            boolean isPrivate = !exec.isPrivateQueueEmpty();
            AtomicOp currentOp;

            if (isPrivate) {
                currentOp = exec.peekPrivate();
            } else if (exec.globalTaskId != null && exec.currentSequence != null) {
                if (exec.currentSequence.isComplete(exec.stepIndex)) {
                    taskPool.completeTask(exec.globalTaskId, npcId);
                    exec.releaseGlobalTask();
                    return;
                }
                currentOp = exec.currentSequence.get(exec.stepIndex);
            } else {
                exec.state = ExecutorState.IDLE;
                return;
            }

            // ---- 3. ResourceRequestOp handled inline ----
            if (currentOp instanceof AtomicOp.ResourceRequestOp resOp) {
                if (handleResourceRequest(resOp, world, npcId, exec, isPrivate)) {
                    continue; // fulfilled → next op
                } else {
                    return; // waiting → exit NPC processing
                }
            }

            // ---- 3.5. No-op skip: TransformOp where target already has desired block ----
            if (currentOp instanceof AtomicOp.TransformOp top && world.blockOps != null) {
                if (world.blockOps.getBlock(top.target()).equals(top.to())) {
                    advanceStep(exec, npcId, 1);
                    exec.state = ExecutorState.ACTIVE;
                    continue; // skip → next op (batch through consecutive no-ops)
                }
            }

            // ---- 4. Mana check + consume (before execution, for both sync and async) ----
            boolean isPure = isPureOp(currentOp);
            if (!isPure) {
                ManaPool mana = world.get(npcId, ManaPool.class);
                WandCarrier wc = world.get(npcId, WandCarrier.class);
                if (mana == null || wc == null) return;

                float actualCost = currentOp.baseManaCost() * wc.bestManaEfficiency();
                if (mana.current() < actualCost) {
                    // Insufficient mana: release global task back to pool for another NPC.
                    // Private-queue ops stall (no taskId to release).
                    if (!isPrivate && exec.globalTaskId != null && taskPool != null) {
                        taskPool.releaseTaskForReassign(exec.globalTaskId, npcId, world);
                        Log.debug(TAG, "NPC %d — mana %.1f < %.1f, released task #%d",
                                npcId, mana.current(), actualCost, exec.globalTaskId);
                    }
                    return;
                }
                mana.consume(actualCost);
            }

            // ---- 4.5. Range check (horizontal only) + navigation ----
            // RitualOp works at any distance (self_teleport, etc.) — skip nav
            GridPos target = currentOp.target();
            if (target != null && world.movementOps != null && exec.stance == null
                    && !(currentOp instanceof AtomicOp.RitualOp)) {
                Position pos = world.get(npcId, Position.class);
                if (pos != null) {
                    double dx = pos.pos().x() - target.x();
                    double dz = pos.pos().z() - target.z();
                    if (dx * dx + dz * dz > NAV_RANGE_SQ) {
                        MovementOps mov = world.movementOps;
                        CompletableFuture<Void> navFuture = mov.navigateTo(
                                npcId, target.x(), target.y(), target.z());
                        exec.pendingFuture = navFuture;
                        exec.pendingFutureIsNav = true;
                        Log.debug(TAG, "NPC %d — navigating to %s", npcId, target);
                        return; // wait for nav to resolve
                    }
                }
            }

            // ---- 4.6. Visual feedback: tell NPC where to aim its wand beam + op kind ----
            exec.currentOpTarget = currentOp.target();
            exec.currentOpKind = opKind(currentOp);

            // ---- 5. Execute → get future ----
            @SuppressWarnings("unchecked")
            OpExecutor<AtomicOp> executor = (OpExecutor<AtomicOp>) (Object) registry.get(currentOp.getClass());
            if (executor == null) return;

            CompletableFuture<Void> future = executor.execute(currentOp, world, npcId);

            // ---- 6. Already done? (sync op) ----
            if (future.isDone()) {
                if (!isPure) {
                    // Side-effect op: mana already consumed, advance stepIndex
                    advanceStep(exec, npcId, 1);
                    exec.state = ExecutorState.ACTIVE;

                    // Same-target batching: if next op shares the same target,
                    // stay in the loop to avoid redundant re-navigation.
                    GridPos doneTarget = currentOp.target();
                    AtomicOp nextOp = peekNextOp(exec);
                    if (nextOp != null && sameTarget(doneTarget, nextOp.target())) {
                        continue; // batch: process next op without breaking
                    }
                    break; // one side-effect per tick (normal flow)
                }
                // Pure op: already self-advanced inside execute() — continue batch
                continue;
            }

            // ---- 7. Not done (async op) — store and wait (mana already consumed) ----
            exec.pendingFuture = future;
            exec.pendingFutureIsNav = false;
            Log.debug(TAG, "NPC %d - async op in-flight, waiting", npcId);
            return; // don't have exec.state=WAITING — the future IS the state
        }

        // No more work
        if (!exec.hasWork()) {
            exec.state = ExecutorState.IDLE;
            exec.currentOpTarget = null;
            exec.currentOpKind = null;
            // Cancel any in-flight navigation that WE initiated
            if (world.movementOps != null && exec.pendingFuture != null) {
                world.movementOps.cancelNavigation(npcId);
            }
        }
    }

    // ---- Helpers ----

    private void advanceStep(TaskExecutor exec, long npcId, int delta) {
        if (!exec.isPrivateQueueEmpty()) {
            exec.popPrivate();
        } else if (exec.globalTaskId != null) {
            exec.stepIndex += delta;
            taskPool.advanceStep(exec.globalTaskId, exec.stepIndex);
            if (exec.currentSequence != null && exec.currentSequence.isComplete(exec.stepIndex)) {
                taskPool.completeTask(exec.globalTaskId, npcId);
                exec.releaseGlobalTask();
            }
        }
        exec.currentOpTarget = null; // clear visual target after step advances
        exec.currentOpKind = null;
    }

    private boolean handleResourceRequest(AtomicOp.ResourceRequestOp op, World world,
                                           long npcId, TaskExecutor exec, boolean isPrivate) {
        ColonyResourceAccess resources = world.colonyResources;
        ResourceStack requested = op.requested();
        int available = resources.available(requested.resource());

        if (resources.hasEnough(requested.resource(), requested.amount())) {
            if (!resources.reserve(requested.resource(), requested.amount())) return false;
            Inventory inv = world.get(npcId, Inventory.class);
            if (inv == null || !inv.add(requested)) {
                resources.release(requested.resource(), requested.amount());
                return false;
            }
            resources.commit(requested.resource(), requested.amount());
            advanceStep(exec, npcId, 1);
            exec.state = ExecutorState.ACTIVE;
            return true;
        }
        if (!isPrivate && exec.globalTaskId != null) {
            taskPool.markAwaitingResources(exec.globalTaskId, npcId, requested, world);
        }
        return false;
    }

    static boolean isPureOp(AtomicOp op) {
        return op instanceof AtomicOp.EmitEventOp || op instanceof AtomicOp.IfConditionOp;
    }

    /** Derive a visual-effect kind string from the op type, for client-side rendering. */
    @Nullable
    private static String opKind(AtomicOp op) {
        return switch (op) {
            case AtomicOp.RitualOp r      -> "ritual:" + r.ritual().id();
            case AtomicOp.BlockInteractOp b -> "block_interact:" + b.action().id();
            case AtomicOp.TransformOp t   -> "transform";
            default                       -> null;
        };
    }

    /** Peek at the next op without consuming it. Returns null if none. */
    @Nullable
    private static AtomicOp peekNextOp(TaskExecutor exec) {
        if (!exec.isPrivateQueueEmpty()) {
            return exec.peekPrivate();
        }
        if (exec.globalTaskId != null && exec.currentSequence != null
                && !exec.currentSequence.isComplete(exec.stepIndex)) {
            return exec.currentSequence.get(exec.stepIndex);
        }
        return null;
    }

    /** True when both targets are non-null and equal. */
    private static boolean sameTarget(@Nullable GridPos a, @Nullable GridPos b) {
        return a != null && b != null && a.equals(b);
    }

    /**
     * Compute a fixed standoff position from the bounding box of all
     * position-bearing ops in the sequence. Returns null if no ops have targets.
     */
    @Nullable
    static GridPos computeTaskStance(TaskSequence seq) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        boolean hasTarget = false;

        for (int i = 0; i < seq.size(); i++) {
            GridPos t = seq.get(i).target();
            if (t != null) {
                hasTarget = true;
                if (t.x() < minX) minX = t.x();
                if (t.x() > maxX) maxX = t.x();
                if (t.y() < minY) minY = t.y();
                if (t.z() < minZ) minZ = t.z();
                if (t.z() > maxZ) maxZ = t.z();
            }
        }
        if (!hasTarget) return null;
        return new GridPos(minX - 2, minY + 1, (minZ + maxZ) / 2);
    }
}
