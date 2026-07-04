package com.wsteam.wandscape.core.system;

import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.core.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.core.boundary.MovementOps;
import com.wsteam.wandscape.core.component.*;
import com.wsteam.wandscape.core.ecs.System;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.*;
import com.wsteam.wandscape.core.task.ExecutorState;
import com.wsteam.wandscape.core.task.GlobalTask;
import com.wsteam.wandscape.core.task.GlobalTaskPool;
import com.wsteam.wandscape.core.task.NpcTaskPackage;
import com.wsteam.wandscape.core.task.TaskSequence;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.core.types.RitualId;

import javax.annotation.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Drives NPC task execution from {@link NpcTaskQueue}.
 *
 * <p>Each NPC has a queue of {@link NpcTaskPackage}s. This system drives the
 * current package's op sequence, handles async futures, and releases packages
 * back to the global pool on mana depletion or resource shortage.
 *
 * <p>V3 package-driven model:
 * <ol>
 *   <li>No work → IDLE</li>
 *   <li>Pending async future → wait or advance</li>
 *   <li>No current package → start next from queue</li>
 *   <li>Navigate to package stance if out of range</li>
 *   <li>Execute current op → handle mana, resources, async</li>
 * </ol>
 */
public class TaskExecutionSystem implements System {

    private static final String TAG = "TaskExec";
    private static final double NAV_RANGE_SQ = 25.0;
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
                EquipmentComponent.class, Inventory.class);

        for (long npcId : npcs) {
            TaskExecutor exec = world.get(npcId, TaskExecutor.class);
            if (exec == null) continue;

            NpcTaskQueue queue = exec.npcQueue;

            // ── 0. No work → idle ──
            if (!queue.hasWork() && exec.globalTaskId == null) {
                if (exec.state != ExecutorState.IDLE) {
                    Log.debug(TAG, "NPC %d → IDLE (no work, was=%s pendingFuture=%s nav=%s)",
                            npcId, exec.state,
                            exec.pendingFuture != null && !exec.pendingFuture.isDone(),
                            exec.pendingFutureIsNav);
                }
                exec.state = ExecutorState.IDLE;
                exec.currentOpTarget = null;
                exec.currentOpKind = null;
                if (world.movementOps != null && exec.pendingFuture != null) {
                    world.movementOps.cancelNavigation(npcId);
                    exec.pendingFuture = null;
                    exec.pendingFutureIsNav = false;
                }
                continue;
            }

            processNpc(world, npcId, exec, queue, registry);
        }
    }

    private void processNpc(World world, long npcId, TaskExecutor exec,
                            NpcTaskQueue queue, OpExecutorRegistry registry) {

        // ── 1. No current package → start the next one ──
        if (queue.currentPackage() == null && queue.hasPending()) {
            queue.startNextPending();
            NpcTaskPackage pkg = queue.currentPackage();
            if (pkg != null && pkg.source().startsWith("global:") && exec.globalTaskId == null) {
                bindGlobalTaskToExecutor(exec, pkg);
            }
        }

        NpcTaskPackage pkg = queue.currentPackage();

        // ── 2. Pending async future from previous tick? ──
        boolean navJustResolved = false;
        if (exec.pendingFuture != null) {
            if (!exec.pendingFuture.isDone()) {
                Log.debug(TAG, "NPC %d — waiting on future (nav=%s)", npcId, exec.pendingFutureIsNav);
                return; // still waiting
            }
            Log.info(TAG, "NPC %d — future resolved (wasNav=%s)", npcId, exec.pendingFutureIsNav);
            exec.pendingFuture = null;
            if (!exec.pendingFutureIsNav) {
                queue.advanceStep();
                syncStepToPool(exec, queue);
                exec.lastWorkTick = worldTick(world);
            } else {
                Log.info(TAG, "NPC %d — nav resolved, continuing to execute op", npcId);
                exec.pendingFutureIsNav = false;
                navJustResolved = true;
            }
            if (queue.isCurrentPackageDone()) {
                finishOrReleaseCurrentPackage(exec, queue, npcId, world);
                return;
            }
        }

        // Refresh pkg after future handling (might have changed)
        pkg = queue.currentPackage();
        if (pkg == null) {
            exec.state = ExecutorState.IDLE;
            exec.currentOpTarget = null;
            exec.currentOpKind = null;
            return;
        }

        // ── 3. Navigate to package stance if far away ──
        // Skip when the nav just resolved — NavigationSystem already confirmed arrival.
        // The per-op range check in step 4d is the fallback if the NPC is somehow still
        // out of position.
        if (!navJustResolved && pkg.stance() != null && exec.pendingFuture == null
                && !exec.pendingFutureIsNav && world.movementOps != null) {
            Position pos = world.get(npcId, Position.class);
            if (pos != null) {
                double dx = pos.pos().x() - pkg.stance().x();
                double dz = pos.pos().z() - pkg.stance().z();
                if (dx * dx + dz * dz > NAV_RANGE_SQ) {
                    MovementOps mov = world.movementOps;
                    CompletableFuture<Void> navFuture = mov.navigateTo(
                            npcId, pkg.stance().x(), pkg.stance().y(), pkg.stance().z());
                    exec.pendingFuture = navFuture;
                    exec.pendingFutureIsNav = true;
                    Log.debug(TAG, "NPC %d — navigating to pkg stance %s", npcId, pkg.stance());
                    exec.state = ExecutorState.ACTIVE;
                    return;
                }
            }
        }

        // ── 4. Execute op loop (batch pure ops, one side-effect per tick) ──
        EquipmentComponent eq = world.get(npcId, EquipmentComponent.class);
        while (queue.peekCurrentOp() != null) {
            AtomicOp currentOp = queue.peekCurrentOp();

            // ── 4a. No-op skip: TransformOp where target already has desired block ──
            if (currentOp instanceof AtomicOp.TransformOp top && world.blockOps != null) {
                if (world.blockOps.getBlock(top.target()).equals(top.to())) {
                    queue.advanceStep();
                    exec.lastWorkTick = worldTick(world);
                    exec.state = ExecutorState.ACTIVE;
                    continue;
                }
            }

            // ── 4b. ParallelOp: launch all sub-ops concurrently ──
            if (currentOp instanceof AtomicOp.ParallelOp par) {
                executeParallel(par, world, npcId, exec, queue, registry);
                return;
            }

            // ── 4c. Mana check + consume ──
            boolean isPure = isPureOp(currentOp);
            if (!isPure) {
                ManaPool mana = world.get(npcId, ManaPool.class);
                if (mana == null || eq == null) return;

                float actualCost = currentOp.baseManaCost() * eq.getAttribute(com.wsteam.wandscape.core.types.AttributeType.MANA_COST_MULTIPLIER);
                if (mana.current() < actualCost) {
                    releaseToGlobalPool(exec, queue, npcId, world);
                    Log.info(TAG, "NPC %d — mana %.1f < %.1f, released pkg to pool",
                            npcId, mana.current(), actualCost);
                    return;
                }
                mana.consume(actualCost);
                Log.info(TAG, "NPC %d — mana -%.1f → %.1f/%d (%s)",
                        npcId, actualCost, mana.current(), mana.max(),
                        currentOp instanceof AtomicOp.RitualOp r ? r.ritual().id()
                                : currentOp.getClass().getSimpleName());
            }

            // ── 4d. Range check (for per-op nav, when no stance is set) ──
            GridPos target = currentOp.target();
            if (target != null && world.movementOps != null && pkg.stance() == null
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
                        exec.state = ExecutorState.ACTIVE;
                        Log.debug(TAG, "NPC %d — navigating to op target %s", npcId, target);
                        return;
                    }
                }
            }

            // ── 4e. Visual feedback ──
            exec.currentOpTarget = currentOp.target();
            exec.currentOpKind = opKind(currentOp);

            // ── 4f. Execute → get future ──
            @SuppressWarnings("unchecked")
            OpExecutor<AtomicOp> executor = (OpExecutor<AtomicOp>) (Object) registry.get(currentOp.getClass());
            if (executor == null) return;

            CompletableFuture<Void> future = executor.execute(currentOp, world, npcId);

            // ── 4g. Already done? (sync op) ──
            if (future.isDone()) {
                if (future.isCompletedExceptionally()) {
                    Throwable cause = null;
                    try {
                        future.get();
                    } catch (Exception e) {
                        cause = e.getCause();
                        Log.warn(TAG, "NPC %d — op %s failed: %s",
                                npcId, currentOp.getClass().getSimpleName(),
                                cause != null ? cause.getMessage() : e.getMessage());
                    }
                    if (cause instanceof ResourceShortageException shortage) {
                        if (exec.globalTaskId != null && taskPool != null) {
                            taskPool.markAwaitingResources(exec.globalTaskId, npcId,
                                    shortage.requestedItems(), world);
                            exec.releaseGlobalTask();
                        }
                        queue.clearCurrentWithoutResume();
                    } else {
                        releaseToGlobalPool(exec, queue, npcId, world);
                    }
                    exec.state = ExecutorState.IDLE;
                    exec.currentOpTarget = null;
                    exec.currentOpKind = null;
                    return;
                }
                if (!isPure) {
                    queue.advanceStep();
                    syncStepToPool(exec, queue);
                    exec.lastWorkTick = worldTick(world);
                    exec.state = ExecutorState.ACTIVE;

                    // Same-target batching: if next op shares target, continue
                    GridPos doneTarget = currentOp.target();
                    AtomicOp nextOp = queue.peekCurrentOp();
                    if (nextOp != null && sameTarget(doneTarget, nextOp.target())) {
                        continue;
                    }
                    // One side-effect per tick
                    break;
                }
                // Pure op: executor may have already finished the package via advanceAfterPureOp
                if (queue.isCurrentPackageDone() || queue.currentPackage() != pkg) {
                    finishOrReleaseCurrentPackage(exec, queue, npcId, world);
                    return;
                }
                if (queue.peekCurrentOp() == null) {
                    finishOrReleaseCurrentPackage(exec, queue, npcId, world);
                    return;
                }
                continue;
            }

            // ── 4h. Async op — store future and wait ──
            exec.pendingFuture = future;
            exec.pendingFutureIsNav = false;
            exec.state = ExecutorState.ACTIVE;
            Log.debug(TAG, "NPC %d - async op in-flight, waiting", npcId);
            return;
        }

        // No more ops in current package
        if (queue.peekCurrentOp() == null && queue.currentPackage() != null) {
            finishOrReleaseCurrentPackage(exec, queue, npcId, world);
        }
    }

    // ── Package lifecycle ──

    /**
     * Finish the current package. If it's a global task package, complete the task.
     * Then start the next pending/resumed package.
     */
    private void finishOrReleaseCurrentPackage(TaskExecutor exec, NpcTaskQueue queue,
                                                long npcId, World world) {
        NpcTaskPackage pkg = queue.currentPackage();
        if (pkg == null) return;

        String source = pkg.source();
        Log.info(TAG, "NPC %d — finish pkg source=%s state=%s pendingFuture=%s nav=%s globalTaskId=%s",
                npcId, source, exec.state,
                exec.pendingFuture != null && !exec.pendingFuture.isDone(),
                exec.pendingFutureIsNav,
                exec.globalTaskId);

        if (source.startsWith("global:") && exec.globalTaskId != null) {
            syncStepToPool(exec, queue);
            taskPool.completeTask(exec.globalTaskId, npcId);
            Log.info(TAG, "NPC %d — completed global task #%d", npcId, exec.globalTaskId);


            exec.releaseGlobalTask();
        }

        queue.finishCurrentPackage();
        exec.lastWorkTick = worldTick(world);

        // Start the next package if one is waiting
        if (queue.currentPackage() == null && queue.hasPending()) {
            queue.startNextPending();
            NpcTaskPackage nextPkg = queue.currentPackage();
            if (nextPkg != null && nextPkg.source().startsWith("global:")) {
                bindGlobalTaskToExecutor(exec, nextPkg);
            }
        }

        if (queue.currentPackage() == null) {
            exec.state = ExecutorState.IDLE;
            exec.currentOpTarget = null;
            exec.currentOpKind = null;
        }
    }

    /** Sync stepIndex from the queue to both exec and the global task pool. */
    private void syncStepToPool(TaskExecutor exec, NpcTaskQueue queue) {
        exec.stepIndex = queue.stepIndex();
        if (exec.globalTaskId != null && taskPool != null) {
            taskPool.advanceStep(exec.globalTaskId, queue.stepIndex());
        }
    }

    /**
     * Release the current package back to the global pool with preserved progress.
     *
     * <p>Before releasing, returns items from NPC inventory that were fetched by
     * already-executed {@link AtomicOp.ResourceRequestOp}s back to the warehouse,
     * and resets stepIndex to the first such request. This is critical because
     * stepIndex is a global progress cursor, but fetched items live in per-NPC
     * inventory. Without the refund+reset, the next NPC starts past the
     * ResourceRequestOp with an empty inventory and hits consumable shortages
     * on the first TransformOp.
     */
    private void releaseToGlobalPool(TaskExecutor exec, NpcTaskQueue queue,
                                      long npcId, World world) {
        if (exec.globalTaskId != null && taskPool != null) {
            syncStepToPool(exec, queue); // preserve progress before releasing
            returnAndReset(exec, npcId, world);
            taskPool.releaseTaskForReassign(exec.globalTaskId, npcId, world);
            exec.releaseGlobalTask();
        }
        queue.clearCurrentWithoutResume();
        exec.state = ExecutorState.IDLE;
        exec.currentOpTarget = null;
        exec.currentOpKind = null;
    }

    /**
     * Return items fetched by executed ResourceRequestOps back to the colony
     * warehouse, and reset stepIndex to the first request so the next NPC
     * re-fetches them. TransformOps that were already executed will no-op
     * (target already matches desired block), so re-fetch is safe.
     */
    private void returnAndReset(TaskExecutor exec, long npcId, World world) {
        long taskId = exec.globalTaskId;
        GlobalTask task = taskPool.get(taskId);
        if (task == null) return;

        int currentStep = exec.stepIndex;
        if (currentStep <= 0) return; // nothing past ResourceRequestOp

        Inventory inv = world.get(npcId, Inventory.class);
        ColonyResourceAccess colony = world.colonyResources;
        if (inv == null || colony == null) return;

        int firstReqIdx = -1;

        for (int i = 0; i < task.sequence.size() && i < currentStep; i++) {
            if (task.sequence.get(i) instanceof AtomicOp.ResourceRequestOp req) {
                for (ResourceStack item : req.items()) {
                    int count = inv.count(item.resource());
                    if (count > 0) {
                        inv.remove(item.resource(), count);
                        colony.addResource(item.resource(), count);
                        Log.info(TAG, "NPC %d — returned %d x %s to warehouse on release",
                                npcId, count, item.resource().id());
                    }
                }
                if (firstReqIdx < 0) {
                    firstReqIdx = i;
                }
            }
        }

        if (firstReqIdx >= 0) {
            exec.stepIndex = firstReqIdx;
            taskPool.advanceStep(taskId, firstReqIdx);
        }
    }

    /** Bind a global task to the executor when a global package starts. */
    private void bindGlobalTaskToExecutor(TaskExecutor exec, NpcTaskPackage pkg) {
        String source = pkg.source();
        if (!source.startsWith("global:")) return;
        try {
            long taskId = Long.parseLong(source.substring("global:".length()));
            exec.globalTaskId = taskId;
            exec.currentSequence = pkg.sequence();
            exec.stepIndex = pkg.startStepIndex();
            exec.stance = pkg.stance();
        } catch (NumberFormatException e) {
            Log.warn(TAG, "Invalid global task source: %s", source);
        }
    }

    // ── ParallelOp execution ──

    @SuppressWarnings("unchecked")
    private void executeParallel(AtomicOp.ParallelOp par, World world, long npcId,
                                  TaskExecutor exec, NpcTaskQueue queue,
                                  OpExecutorRegistry registry) {
        List<AtomicOp> subs = par.steps();
        if (subs.isEmpty()) {
            queue.advanceStep();
            exec.lastWorkTick = worldTick(world);
            exec.state = ExecutorState.ACTIVE;
            return;
        }

        float totalCost = 0;
        for (AtomicOp sub : subs) {
            if (!isPureOp(sub)) totalCost += sub.baseManaCost();
        }

        if (totalCost > 0) {
            ManaPool mana = world.get(npcId, ManaPool.class);
            EquipmentComponent eq = world.get(npcId, EquipmentComponent.class);
            if (mana == null || eq == null) return;
            float actualCost = totalCost * eq.getAttribute(com.wsteam.wandscape.core.types.AttributeType.MANA_COST_MULTIPLIER);
            if (mana.current() < actualCost) {
                releaseToGlobalPool(exec, queue, npcId, world);
                Log.info(TAG, "NPC %d — mana %.1f < %.1f total for %d parallel ops, released",
                        npcId, mana.current(), actualCost, subs.size());
                return;
            }
            mana.consume(actualCost);
            Log.info(TAG, "NPC %d — mana -%.1f → %.1f/%d (parallel x%d)",
                    npcId, actualCost, mana.current(), mana.max(), subs.size());
        }

        CompletableFuture<Void>[] futures = new CompletableFuture[subs.size()];
        for (int i = 0; i < subs.size(); i++) {
            AtomicOp sub = subs.get(i);
            OpExecutor<AtomicOp> subExec = (OpExecutor<AtomicOp>) (Object) registry.get(sub.getClass());
            if (subExec != null) {
                futures[i] = subExec.execute(sub, world, npcId);
            } else {
                Log.warn(TAG, "NPC %d — no executor for parallel sub-op %s",
                        npcId, sub.getClass().getSimpleName());
                futures[i] = CompletableFuture.completedFuture(null);
            }
        }

        exec.pendingFuture = CompletableFuture.allOf(futures);
        exec.pendingFutureIsNav = false;
        exec.state = ExecutorState.ACTIVE;
        exec.currentOpTarget = null;
        exec.currentOpKind = "parallel";
        Log.debug(TAG, "NPC %d — launched %d parallel ops, waiting for allOf", npcId, subs.size());
    }

    // ── Helpers ──

    static boolean isPureOp(AtomicOp op) {
        return op instanceof AtomicOp.EmitEventOp || op instanceof AtomicOp.IfConditionOp;
    }

    @Nullable
    private static String opKind(AtomicOp op) {
        return switch (op) {
            case AtomicOp.RitualOp r      -> "ritual:" + r.ritual().id();
            case AtomicOp.BlockInteractOp b -> "block_interact:" + b.action().id();
            case AtomicOp.TransformOp t   -> "transform";
            case AtomicOp.ParallelOp p    -> "parallel";
            default                       -> null;
        };
    }

    private static boolean sameTarget(@Nullable GridPos a, @Nullable GridPos b) {
        return a != null && b != null && a.equals(b);
    }

    /** Approximate tick counter from system time (for lastWorkTick tracking). */
    private static long worldTick(World world) {
        return java.lang.System.currentTimeMillis() / 50;
    }

    /**
     * Compute a fixed standoff position from the bounding box of all
     * position-bearing ops in the sequence. Returns null if no ops have targets.
     */
    @Nullable
    public static GridPos computeTaskStance(TaskSequence seq) {
        int[] box = { Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE };
        boolean[] hasTarget = { false };

        for (int i = 0; i < seq.size(); i++) {
            collectTargets(seq.get(i), box, hasTarget);
        }
        if (!hasTarget[0]) return null;
        return new GridPos(box[0] - 2, box[2] + 1, (box[3] + box[4]) / 2);
    }

    private static void collectTargets(AtomicOp op, int[] box, boolean[] hasTarget) {
        GridPos t = op.target();
        if (t != null) {
            hasTarget[0] = true;
            if (t.x() < box[0]) box[0] = t.x();
            if (t.x() > box[1]) box[1] = t.x();
            if (t.y() < box[2]) box[2] = t.y();
            if (t.z() < box[3]) box[3] = t.z();
            if (t.z() > box[4]) box[4] = t.z();
        }
        if (op instanceof AtomicOp.ParallelOp par) {
            for (AtomicOp sub : par.steps()) {
                collectTargets(sub, box, hasTarget);
            }
        }
    }

}
