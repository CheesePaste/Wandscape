package com.wsteam.wandscape.content.task.scheduler;
import com.wsteam.wandscape.content.npc.system.NavigationSystem;
import com.wsteam.wandscape.content.task.boundary.BlockOps;
import com.wsteam.wandscape.content.npc.component.NpcTaskQueue;
import com.wsteam.wandscape.content.task.boundary.EntityOps;
import com.wsteam.wandscape.content.task.component.TaskExecutor;
import com.wsteam.wandscape.content.task.component.ColonyMember;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.component.Inventory;
import com.wsteam.wandscape.foundation.util.TickProfiler;

import com.wsteam.wandscape.content.task.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.content.task.boundary.MovementOps;
// core.component wildcard replaced
import com.wsteam.wandscape.content.task.ecs.System;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.types.GridPos;
import com.wsteam.wandscape.content.task.types.ResourceStack;
import com.wsteam.wandscape.content.task.types.RitualId;
import com.wsteam.wandscape.content.task.op.api.AtomicOp;
import com.wsteam.wandscape.content.task.op.executor.OpExecutor;
import com.wsteam.wandscape.content.task.op.executor.OpExecutorRegistry;
import com.wsteam.wandscape.content.task.op.executor.ResourceShortageException;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTaskPool;
import com.wsteam.wandscape.content.task.runtime.ExecutorState;
import com.wsteam.wandscape.content.task.runtime.NpcTaskPackage;
import com.wsteam.wandscape.content.task.runtime.TaskSequence;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Drives NPC task execution from {@link NpcTaskQueue}.
 *
 * <p>Each NPC has a queue of {@link NpcTaskPackage}s. This system drives the
 * current package's op sequence, handles async futures, and releases packages
 * back to the global pool on resource shortage.
 *
 * <p>V3 package-driven model:
 * <ol>
 *   <li>No work → IDLE</li>
 *   <li>Pending async future → wait or advance</li>
 *   <li>No current package → start next from queue</li>
 *   <li>Navigate to package stance if out of range</li>
 *   <li>Execute current op → handle resources, async</li>
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
        try (var span = com.wsteam.wandscape.foundation.util.TickProfiler.INSTANCE.start("ecs.task_execution.tick")) {
        OpExecutorRegistry registry = world.opExecutors;
        if (registry == null) return;

        List<Long> npcs = world.query(Position.class, TaskExecutor.class, Inventory.class);

        for (long npcId : npcs) {
            TaskExecutor exec = world.get(npcId, TaskExecutor.class);
            if (exec == null) continue;

            NpcTaskQueue queue = exec.npcQueue;

            // ── 0. 跟随/休息：释放小镇全局任务（保留 self_defense 等个人包）──
            // 放在「无工作→idle」之前：挂起栈里可能还压着被自防御抢断的 global 包，
            // 此时 hasWork()=false 但 hasGlobalPackage()=true，先走 idle 会让该包永驻挂起栈。
            if (world.entityOps != null
                    && (world.entityOps.isFollowing(npcId) || world.entityOps.isResting(npcId))
                    && (exec.globalTaskId != null || queue.hasGlobalPackage())) {
                releaseForInterruption(world, npcId, exec, queue);
                continue;
            }

            // ── 0.5 小镇冻结：创始人不在线且关闭离线运行 → NPC 原地冻结，不推进执行 ──
            // 保留原状态（队列/步骤/async future），创始人上线后由同一路径恢复。
            ColonyMember frozenMember = world.get(npcId, ColonyMember.class);
            if (frozenMember != null && world.entityOps != null
                    && !world.entityOps.isColonyActive(frozenMember.colonyId())) {
                continue;
            }

            // ── 0.6 幽灵 NPC 防御：MC 实体缺失/已移除（区块卸载、异常清理遗漏）──
            // 任务不得驱动一个不存在的 NPC：释放绑定全局任务（保留步进、退还已取元素）、
            // 丢弃全局包、清执行状态并跳过本轮。ECS 组件保留（区块重载后重连复用）。
            if (world.entityOps != null && !world.entityOps.isNpcAlive(npcId)) {
                releaseForPhantom(world, npcId, exec, queue);
                continue;
            }

            // ── 0.7 未注册殖民地 NPC 防御：占位（刷怪蛋召唤在殖民地外）/陈旧（殖民地已删除）
            // 殖民地无仓库可服务，不得执行任何殖民地工作——与幽灵防御同构：释放绑定全局任务、
            // 丢弃 global 包、取消导航，保留个人包（自防御）。首次清理后无残留即空转不刷日志。
            ColonyMember colonyMember = world.get(npcId, ColonyMember.class);
            if (colonyMember != null && world.entityOps != null
                    && !world.entityOps.isColonyRegistered(colonyMember.colonyId())
                    && (exec.globalTaskId != null || queue.hasGlobalPackage())) {
                releaseBoundGlobalTask(world, npcId, exec, queue);
                if (world.movementOps != null) {
                    world.movementOps.cancelNavigation(npcId);
                }
                Log.info(TAG, "NPC %d — unregistered colony (placeholder/stale): released global task", npcId);
                continue;
            }

            // ── 1. No work → idle ──
            if (!queue.hasWork() && exec.globalTaskId == null) {
                if (exec.state != ExecutorState.IDLE) {
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
                return; // still waiting
            }
            CompletableFuture<Void> resolvedFuture = exec.pendingFuture;
            boolean wasNav = exec.pendingFutureIsNav;
            Log.info(TAG, "NPC %d — future resolved (wasNav=%s)", npcId, wasNav);
            exec.pendingFuture = null;
            exec.pendingFutureIsNav = false;

            if (resolvedFuture.isCompletedExceptionally()) {
                Throwable cause = null;
                try {
                    resolvedFuture.get();
                } catch (Exception e) {
                    cause = e.getCause() != null ? e.getCause() : e;
                    Log.warn(TAG, "NPC %d — async op %s failed: %s",
                            npcId, pkg != null ? pkg.source() : "unknown",
                            cause.getMessage());
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

            if (!wasNav) {
                queue.advanceStep();
                syncStepToPool(exec, queue);
                exec.lastWorkTick = worldTick(world);
            } else {
                Log.info(TAG, "NPC %d — nav resolved, continuing to execute op", npcId);
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
                double dy = pos.pos().y() - pkg.stance().y();
                double dz = pos.pos().z() - pkg.stance().z();
                if (dx * dx + dz * dz > NAV_RANGE_SQ || Math.abs(dy) > 4.0) {
                    MovementOps mov = world.movementOps;
                    CompletableFuture<Void> navFuture = mov.navigateTo(
                            npcId, pkg.stance().x(), pkg.stance().y(), pkg.stance().z());
                    exec.pendingFuture = navFuture;
                    exec.pendingFutureIsNav = true;
                    exec.state = ExecutorState.ACTIVE;
                    return;
                }
            }
        }

        // ── 4. Execute op loop (batch pure ops, one side-effect per tick) ──
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

            // ── 4c. Pure-op classification (no mana gate — magic is time-gated) ──
            boolean isPure = isPureOp(currentOp);

            // ── 4d. Range check (for per-op nav, when no stance is set) ──
            GridPos target = currentOp.target();
            if (target != null && world.movementOps != null && pkg.stance() == null
                    && !(currentOp instanceof AtomicOp.RitualOp)) {
                Position pos = world.get(npcId, Position.class);
                if (pos != null) {
                    double dx = pos.pos().x() - target.x();
                    double dy = pos.pos().y() - target.y();
                    double dz = pos.pos().z() - target.z();
                    if (dx * dx + dz * dz > NAV_RANGE_SQ || Math.abs(dy) > 4.0) {
                        MovementOps mov = world.movementOps;
                        CompletableFuture<Void> navFuture = mov.navigateTo(
                                npcId, target.x(), target.y(), target.z());
                        exec.pendingFuture = navFuture;
                        exec.pendingFutureIsNav = true;
                        exec.state = ExecutorState.ACTIVE;
                        return;
                    }
                }
            }

            // ── 4e. Visual feedback ──
            exec.currentOpTarget = currentOp.target();
            exec.currentOpKind = opKind(currentOp);

            // ── 4f. Execute → get future ──
            @SuppressWarnings("unchecked")
            OpExecutor<AtomicOp> executor = (OpExecutor<AtomicOp>) registry.get(currentOp.getClass());
            if (executor == null) return;

            CompletableFuture<Void> future;
            try {
                future = executor.execute(currentOp, world, npcId);
            } catch (ResourceShortageException shortage) {
                if (exec.globalTaskId != null && taskPool != null) {
                    taskPool.markAwaitingResources(exec.globalTaskId, npcId,
                            shortage.requestedItems(), world);
                    exec.releaseGlobalTask();
                }
                queue.clearCurrentWithoutResume();
                exec.state = ExecutorState.IDLE;
                exec.currentOpTarget = null;
                exec.currentOpKind = null;
                return;
            } catch (Throwable t) {
                Log.warn(TAG, "NPC %d — executor threw exception for op %s: %s",
                        npcId, currentOp.getClass().getSimpleName(), t.getMessage());
                releaseToGlobalPool(exec, queue, npcId, world);
                exec.state = ExecutorState.IDLE;
                exec.currentOpTarget = null;
                exec.currentOpKind = null;
                return;
            }

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

    /**
     * Sync stepIndex from the queue to both exec and the global task pool.
     * Only acts when the current package is the bound global task's package —
     * otherwise (e.g. a {@code self_defense} package preempting a suspended global
     * task) syncing would overwrite the suspended task's progress with the
     * preempting package's step and corrupt its resume point.
     */
    private void syncStepToPool(TaskExecutor exec, NpcTaskQueue queue) {
        NpcTaskPackage pkg = queue.currentPackage();
        if (pkg == null || !pkg.source().startsWith("global:")) return;
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
            if (task.sequence.get(i) instanceof AtomicOp.ResourceRequestOp(List<ResourceStack> items)) {
                for (ResourceStack item : items) {
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

    /**
     * 释放绑定全局任务（保留步进 + 退还已取元素）并丢弃全部 {@code global:} 包。
     * 覆盖"NPC 不能再干这个活"的所有场景（跟随/休息中断、幽灵 NPC）：
     * 任务归还任务池供他人续跑，元素退还仓库、步进重置到首个 ResourceRequestOp，
     * 避免下一 NPC 空背包打到资源短缺死循环。
     */
    private void releaseBoundGlobalTask(World world, long npcId, TaskExecutor exec, NpcTaskQueue queue) {
        if (exec.globalTaskId != null && taskPool != null) {
            syncStepToPool(exec, queue); // preserve progress before releasing
            returnAndReset(exec, npcId, world);
            taskPool.releaseTaskForReassign(exec.globalTaskId, npcId, world);
        }
        queue.dropGlobalPackages();
        exec.releaseGlobalTask();
    }

    /**
     * 跟随/休息：释放该 NPC 的全部小镇全局任务（current/pending/挂起栈里的
     * {@code global:*} 包），只保留 {@code self_defense} 等个人包。已绑定的全局任务
     * 按步进归还任务池（{@link GlobalTaskPool#releaseTaskForReassign}），供其他 NPC 接取。
     *
     * <p>异步 future 处理：只有当前包是 {@code global:*} 时才清掉 {@code pendingFuture}
     * 并取消导航（该 future 属于被释放的任务）；若当前仍是个人包（如自防御的异步战斗），
     * 其 future 由对应执行器独立驱动，须保留，否则任务执行系统会失去同步。
     */
    private void releaseForInterruption(World world, long npcId, TaskExecutor exec, NpcTaskQueue queue) {
        boolean currentIsGlobal = queue.currentPackage() != null
                && queue.currentPackage().source().startsWith("global:");
        CompletableFuture<Void> keptFuture = exec.pendingFuture;
        boolean keptFutureIsNav = exec.pendingFutureIsNav;

        releaseBoundGlobalTask(world, npcId, exec, queue);

        if (!currentIsGlobal) {
            // 当前是个人包（如 self_defense）→ 恢复其异步 future，执行系统继续驱动
            exec.pendingFuture = keptFuture;
            exec.pendingFutureIsNav = keptFutureIsNav;
        } else if (world.movementOps != null) {
            world.movementOps.cancelNavigation(npcId);
        }
        Log.info(TAG, "NPC %d — follow/rest: released global tasks, kept personal packages",
                npcId);
    }

    /**
     * 幽灵 NPC（MC 实体缺失/已移除，如区块卸载）：释放绑定全局任务、丢弃全局包，
     * 取消导航并跳过执行。ECS 组件保留供区块重载后重连，重连后由调度器重新派活。
     * 首次清理后无残留（globalTaskId 空且无 global 包）即空转，不刷日志。
     */
    private void releaseForPhantom(World world, long npcId, TaskExecutor exec, NpcTaskQueue queue) {
        if (exec.globalTaskId == null && !queue.hasGlobalPackage()) return;
        releaseBoundGlobalTask(world, npcId, exec, queue);
        if (world.movementOps != null) {
            world.movementOps.cancelNavigation(npcId);
        }
        Log.info(TAG, "NPC %d — phantom (MC entity gone): released global task", npcId);
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

        CompletableFuture<Void>[] futures = new CompletableFuture[subs.size()];
        for (int i = 0; i < subs.size(); i++) {
            AtomicOp sub = subs.get(i);
            OpExecutor<AtomicOp> subExec = (OpExecutor<AtomicOp>) registry.get(sub.getClass());
            if (subExec != null) {
                try {
                    futures[i] = subExec.execute(sub, world, npcId);
                } catch (Throwable t) {
                    futures[i] = CompletableFuture.failedFuture(t);
                }
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
    }

    // ── Helpers ──

    static boolean isPureOp(AtomicOp op) {
        return op instanceof AtomicOp.EmitEventOp || op instanceof AtomicOp.IfConditionOp;
    }

    @Nullable
    private static String opKind(AtomicOp op) {
        return switch (op) {
            case AtomicOp.RitualOp r      -> "ritual:" + r.ritual().id();
            case AtomicOp.AltarCastOp a   -> "altar_cast:" + a.magicId();
            case AtomicOp.BlockInteractOp b -> "block_interact:" + b.action().id();
            case AtomicOp.TransformOp t   -> "transform";
            case AtomicOp.ParallelOp p    -> "parallel";
            case AtomicOp.SelfDefenseOp s -> "combat";
            default                       -> null;
        };
    }

    private static boolean sameTarget(@Nullable GridPos a, @Nullable GridPos b) {
        return a != null && a.equals(b);
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
        if (op instanceof AtomicOp.ParallelOp(List<AtomicOp> steps)) {
            for (AtomicOp sub : steps) {
                collectTargets(sub, box, hasTarget);
            }
        }
    }

}
