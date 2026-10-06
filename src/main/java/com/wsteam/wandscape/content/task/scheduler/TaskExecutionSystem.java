package com.wsteam.wandscape.content.task.scheduler;
import com.wsteam.wandscape.content.task.NpcTaskQueue;
import com.wsteam.wandscape.content.task.component.TaskExecutor;
import com.wsteam.wandscape.content.task.component.ColonyMember;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.component.NpcInventory;

import com.wsteam.wandscape.content.task.boundary.ColonyResourceAccess;
import com.wsteam.wandscape.content.task.boundary.MovementOps;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
// core.component wildcard replaced
import com.wsteam.wandscape.content.task.ecs.EcsSystem;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.types.GridPos;
import com.wsteam.wandscape.content.task.types.ResourceStack;
import com.wsteam.wandscape.content.task.types.RitualId;
import com.wsteam.wandscape.content.task.op.api.AtomicOp;
import com.wsteam.wandscape.content.task.op.executor.OpExecutor;
import com.wsteam.wandscape.content.task.op.executor.OpExecutorRegistry;
import com.wsteam.wandscape.content.task.op.executor.ResourceShortageException;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTaskPool;
import com.wsteam.wandscape.content.task.runtime.ExecutorState;
import com.wsteam.wandscape.content.task.runtime.NpcTaskPackage;
import com.wsteam.wandscape.content.task.runtime.TaskSequence;
import com.wsteam.wandscape.content.task.runtime.TaskState;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 *   <li>Initial navigation toward task stance/target if far away</li>
 *   <li>Execute current op → handle resources, async (no distance limit once started)</li>
 * </ol>
 */
public class TaskExecutionSystem implements EcsSystem {

    private static final String TAG = "TaskExec";
    private static final double NAV_RANGE_SQ = 25.0;
    private static final RitualId ITEM_TELEPORT = new RitualId("item_teleport");

    private final GlobalTaskPool taskPool;
    /** 派活方：任务完工、法师空出来时叫它下一 tick 立刻跑一轮，别让法师空等到心跳。 */
    private final SchedulerSystem scheduler;

    public TaskExecutionSystem(GlobalTaskPool taskPool, SchedulerSystem scheduler) {
        this.taskPool = taskPool;
        this.scheduler = scheduler;
    }

    @Override
    public void update(World world, float delta) {
    OpExecutorRegistry registry = world.opExecutors;
    if (registry == null) return;

    List<Long> npcs = world.query(Position.class, TaskExecutor.class, NpcInventory.class);

    for (long npcId : npcs) {
        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        if (exec == null) continue;

        NpcTaskQueue queue = exec.npcQueue;

        // ── 0. 跟随：释放小镇全局任务（保留 self_defense 等个人包）──
        // 放在「无工作→idle」之前：挂起栈里可能还压着被自防御抢断的 global 包，
        // 此时 hasWork()=false 但 hasGlobalPackage()=true，先走 idle 会让该包永驻挂起栈。
        if (world.entityOps != null
                && world.entityOps.isFollowing(npcId)
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
            Log.debug(LogCategory.TASK, "exec", "NPC %d — unregistered colony (placeholder/stale): released global task", npcId);
            continue;
        }

        // ── 1. No work → idle ──
        if (!queue.hasWork() && exec.globalTaskId == null) {
            if (exec.state != ExecutorState.IDLE) {
            }
            exec.state = ExecutorState.IDLE;
            exec.currentOpTarget = null;
            exec.currentOpKind = null;
            exec.activePackageSource = null;
            exec.initialNavDone = false;
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
        startNextPackageIfAny(exec, queue);

        // ── 1.5 建筑委派（Building Delegation）：错配的全局工作当场释放 ──
        // 放在包启动/绑定之后：此刻才谈得上「这个包属于哪座建筑」。委派是玩家的即时指令，
        // 不该等下一次调度心跳、更不该等这活干完——被委派的法师手上不该有别的建筑的活，
        // 被委派的建筑也不该让别人代劳。释放走 releaseForInterruption 的成熟路径
        //（保留步进、退还已取元素、丢全局包、保留个人包），下一拍调度器按新归属重派。
        if (violatesDelegation(world, npcId, exec)) {
            releaseForInterruption(world, npcId, exec, queue);
            Log.debug(LogCategory.TASK, "exec",
                    "NPC %d — released a task that violates its building delegation", npcId);
            return;
        }

        NpcTaskPackage pkg = queue.currentPackage();

        // ── 2. Pending async future from previous tick? ──
        if (exec.pendingFuture != null) {
            if (!exec.pendingFuture.isDone()) {
                return; // still waiting
            }
            CompletableFuture<Void> resolvedFuture = exec.pendingFuture;
            boolean wasNav = exec.pendingFutureIsNav;
            Log.debug(LogCategory.TASK, "exec", "NPC %d — future resolved (wasNav=%s)", npcId, wasNav);
            exec.pendingFuture = null;
            exec.pendingFutureIsNav = false;

            if (resolvedFuture.isCompletedExceptionally()) {
                Throwable cause = null;
                try {
                    resolvedFuture.get();
                } catch (Exception e) {
                    cause = e.getCause() != null ? e.getCause() : e;
                    Log.warn(LogCategory.TASK, "exec", "NPC %d — async op %s failed: %s",
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
                exec.activePackageSource = null;
                exec.initialNavDone = false;
                return;
            }

            if (!wasNav) {
                queue.advanceStep();
                syncStepToPool(exec, queue);
                exec.lastWorkTick = worldTick(world);
            } else {
                Log.debug(LogCategory.TASK, "exec", "NPC %d — initial nav resolved, starting work (no distance limit)", npcId);
                exec.initialNavDone = true;
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
            exec.activePackageSource = null;
            exec.initialNavDone = false;
            return;
        }

        // Detect new or resumed package
        if (!java.util.Objects.equals(exec.activePackageSource, pkg.source())) {
            exec.activePackageSource = pkg.source();
            exec.initialNavDone = false;
        }

        // ── 3. Initial navigation toward task stance/target if far away ──
        if (!exec.initialNavDone && exec.pendingFuture == null && world.movementOps != null) {
            GridPos navTarget = resolveTaskNavTarget(pkg);
            if (navTarget != null) {
                Position pos = world.get(npcId, Position.class);
                if (pos != null) {
                    double dx = pos.pos().x() - navTarget.x();
                    double dz = pos.pos().z() - navTarget.z();
                    if (dx * dx + dz * dz > NAV_RANGE_SQ) {
                        MovementOps mov = world.movementOps;
                        CompletableFuture<Void> navFuture = mov.navigateTo(
                                npcId, navTarget.x(), navTarget.y(), navTarget.z());
                        exec.pendingFuture = navFuture;
                        exec.pendingFutureIsNav = true;
                        exec.state = ExecutorState.ACTIVE;
                        return;
                    }
                }
            }
            // In range or positionless: mark initial navigation completed
            exec.initialNavDone = true;
        }

        // ── 4. Execute op loop (pure ops 连续批处理；旁路 op 按工作速度给每 tick 额度，不受距离限制) ──
        // 一格方块就是一个 op，所以「一拍放几格」＝「一拍执行几个拍内完成的旁路 op」：额度见 instantOpBudget。
        int sideEffectBudget = -1; // 懒算：本 tick 还剩几个旁路 op 的额度（<0 表示还没算过）
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

            // ── 4d. Visual feedback ──
            exec.currentOpTarget = currentOp.target();
            exec.currentOpKind = opKind(currentOp);

            // ── 4e. Execute → get future ──
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

                    // 本 tick 的旁路 op 额度用完就停手，剩下的留给下一 tick。
                    if (sideEffectBudget < 0) sideEffectBudget = instantOpBudget(world, npcId);
                    if (--sideEffectBudget <= 0) break;
                    continue;
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
        Log.debug(LogCategory.TASK, "exec", "NPC %d — finish pkg source=%s state=%s pendingFuture=%s nav=%s globalTaskId=%s",
                npcId, source, exec.state,
                exec.pendingFuture != null && !exec.pendingFuture.isDone(),
                exec.pendingFutureIsNav,
                exec.globalTaskId);

        if (source.startsWith("global:") && exec.globalTaskId != null) {
            syncStepToPool(exec, queue);
            taskPool.completeTask(exec.globalTaskId, npcId);
            Log.debug(LogCategory.TASK, "exec", "NPC %d — completed global task #%d", npcId, exec.globalTaskId);

            exec.releaseGlobalTask();
            // 法师空出来了 → 立刻叫调度器下一 tick 跑一轮：批次只有几十格，等心跳（≤1s）才接上
            // 下一条，玩家看到的就是「干几秒、手上停一下」。派活规则仍只有调度器一处，这里只是催它。
            scheduler.requestImmediatePass();
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
            exec.activePackageSource = null;
            exec.initialNavDone = false;
        }
    }

    // ── 多法师协同：批次之间的接续 ──

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
        exec.activePackageSource = null;
        exec.initialNavDone = false;
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

        NpcInventory inv = world.get(npcId, NpcInventory.class);
        ColonyResourceAccess colony = world.colonyResources;
        if (inv == null || colony == null) return;

        ColonyMember member = world.get(npcId, ColonyMember.class);
        UUID colonyId = member != null ? member.colonyId() : null;
        if (colonyId == null && task.taskParams != null) {
            var el = task.taskParams.get("colony_id");
            if (el != null && el.isJsonPrimitive()) {
                try {
                    colonyId = UUID.fromString(el.getAsString());
                } catch (IllegalArgumentException ignored) {}
            }
        }

        int firstReqIdx = -1;
        boolean refundedAny = false;

        for (int i = 0; i < task.sequence.size() && i < currentStep; i++) {
            if (task.sequence.get(i) instanceof AtomicOp.ResourceRequestOp(List<ResourceStack> items)) {
                for (ResourceStack item : items) {
                    int count = inv.count(item.resource());
                    if (count > 0) {
                        inv.remove(item.resource(), count);
                        colony.addResource(colonyId, item.resource(), count);
                        refundedAny = true;
                        Log.debug(LogCategory.TASK, "exec", "NPC %d — returned %d x %s to warehouse of colony %s on release",
                                npcId, count, item.resource().id(), colonyId);
                    }
                }
                if (firstReqIdx < 0 && refundedAny) {
                    firstReqIdx = i;
                }
            }
        }

        if (refundedAny && firstReqIdx >= 0) {
            exec.stepIndex = firstReqIdx;
            taskPool.advanceStep(taskId, firstReqIdx);
        }
    }

    /**
     * 释放绑定全局任务（保留步进 + 退还已取元素）并丢弃全部 {@code global:} 包。
     * 覆盖"NPC 不能再干这个活"的所有场景（跟随中断、幽灵 NPC）：
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
     * 跟随：释放该 NPC 的全部小镇全局任务（current/pending/挂起栈里的
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
        Log.debug(LogCategory.TASK, "exec", "NPC %d — follow/rest: released global tasks, kept personal packages",
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
        Log.debug(LogCategory.TASK, "exec", "NPC %d — phantom (MC entity gone): released global task", npcId);
    }

    /**
     * 若当前没有包且有排队包，启动下一个包并在它是 {@code global:} 包时绑定到执行器。
     * （原本内联在 {@code processNpc} 里；委派判定也要在包启动之后才谈得上"这个包属于哪座建筑"，
     * 故抽出来给两处共用。）
     */
    private void startNextPackageIfAny(TaskExecutor exec, NpcTaskQueue queue) {
        if (queue.currentPackage() != null || !queue.hasPending()) return;
        queue.startNextPending();
        NpcTaskPackage pkg = queue.currentPackage();
        if (pkg != null && pkg.source().startsWith("global:") && exec.globalTaskId == null) {
            bindGlobalTaskToExecutor(exec, pkg);
        }
    }

    /**
     * 该 NPC 当前绑定/待执行的全局任务是否**违反建筑委派**（见 {@code BuildingDelegation}）：
     * <ul>
     *   <li>被委派的法师手上拿着**不是它那座建筑**的全局任务（含没有建筑归属的护卫等任务）；</li>
     *   <li>手上任务所属的建筑已被委派给**别人**。</li>
     * </ul>
     *
     * <p>为什么放在执行侧逐拍判，而不是只在调度器门口判：委派是玩家随时可改的配置，
     * 而任务是在改配置**之前**就派出去的。这里复用"释放任务（保留步进 + 退还已取元素 +
     * 丢全局包）"的成熟路径，下一拍调度器就会按新归属重派，不必在改委派的那个包里
     * 另写一套搬任务的逻辑。
     */
    private static boolean violatesDelegation(World world, long npcId, TaskExecutor exec) {
        if (world.entityOps == null || world.taskPool == null || exec.globalTaskId == null) {
            return false;
        }
        GlobalTask task = world.taskPool.get(exec.globalTaskId);
        if (task == null) return false;
        // 已完成（还没被释放干净）的任务不碰：releaseTaskForReassign 会把它转回 PENDING_ASSIGN，
        // 等于让一件干完的活复活重跑。
        if (task.state == TaskState.COMPLETED) return false;

        UUID post = world.entityOps.delegatedBuildingOf(npcId);
        if (post != null) {
            // 被委派的法师只做它那座建筑的活：别的建筑的任务、无主任务（护卫/祭坛等）都不许留
            return task.buildingId == null || !post.equals(task.buildingId);
        }
        if (task.buildingId == null) return false;
        long owner = world.entityOps.delegatedNpcOf(task.buildingId);
        return owner >= 0 && owner != npcId;
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
            case AtomicOp.ClearBoxOp c    -> "clear_box";
            case AtomicOp.ParallelOp p    -> "parallel";
            case AtomicOp.SelfDefenseOp s -> "combat";
            default                       -> null;
        };
    }

    /**
     * 单个 tick 能执行几个**拍内完成**的旁路 op —— 也就是「一拍放几格方块」：**工作速度向下取整，至少 1**。
     *
     * <p>一格方块一个 op，所以放置速度就是 op 消费速度。口径：工作速度 1.5 → 1 格、2.5 → 2 格、
     * 3.0 → 3 格、0.5 → 1 格（原先是恒定的「每格占一拍」）。工作速度取自 ECS 边界
     * {@code EntityOps#getWorkSpeed}，即**有效属性**（含等级加成与装备），招募曲线 0.5~1.5、
     * 每级 +0.05，所以中低阶法师基本还是 1 格/拍，高阶（有效值 ≥2）才翻倍。
     *
     * <p>只管拍内完成的 op（放置 / 拆除 / 铺地这类 TransformOp）。引导类 op 本身多 tick
     * （采集、合成、仪式、整箱清空各有自己的每 tick 预算），不吃这个额度，也不会被它加速。
     */
    private static int instantOpBudget(World world, long npcId) {
        float work = world.entityOps != null ? world.entityOps.getWorkSpeed(npcId) : 1f;
        if (!(work > 1f)) return 1; // 0.5 / 1.0 / NaN 一律 1 格
        return (int) Math.floor(work);
    }

    /** Approximate tick counter from system time (for lastWorkTick tracking). */
    private static long worldTick(World world) {
        return java.lang.System.currentTimeMillis() / 50;
    }

    /**
     * Resolve the target position for the initial navigation toward a task.
     * Uses the package stance if present, falling back to bounding-box stance
     * computed from the sequence. Returns null if the task has no position-bearing ops.
     */
    @Nullable
    public static GridPos resolveTaskNavTarget(NpcTaskPackage pkg) {
        if (pkg.stance() != null) {
            return pkg.stance();
        }
        if (pkg.sequence() != null) {
            return computeTaskStance(pkg.sequence());
        }
        return null;
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
        return standoffStance(box[0], box[2], box[3], box[4]);
    }

    /**
     * 站位策略（全套规则只此一处）：站在目标包围盒**西侧外沿两格外**、最底层上方一格、Z 轴中点。
     * 放置是远程施法、不按距离门控，所以站位只决定法师「站在哪里把活干完」。
     *
     * @param minX 包围盒最小 X；{@code minY} 最小 Y；{@code minZ}/{@code maxZ} 最小/最大 Z
     */
    public static GridPos standoffStance(int minX, int minY, int minZ, int maxZ) {
        return new GridPos(minX - 2, minY + 1, (minZ + maxZ) / 2);
    }

    /**
     * 解析一条全局任务的站位：优先读 {@code params["task_bbox"]}（多法师协同的批次由
     * {@code ConstructionBatches} 按**整栋**包围盒写入，保证同栋所有批次共用一个站位），
     * 否则按 op 包围盒现算。
     *
     * <p>批次若各自按自己那一小块现算，站位能差十几格，法师每批都要横穿工地——
     * 这正是「多法师协同之后法师来回跑」的根因。
     */
    @Nullable
    public static GridPos resolveTaskStance(@Nullable GlobalTask task) {
        if (task == null) return null;
        GridPos declared = declaredStance(task.taskParams);
        if (declared != null) return declared;
        return task.sequence != null ? computeTaskStance(task.sequence) : null;
    }

    /** params 声明的整栋包围盒 {@code [minX, minY, minZ, maxX, maxY, maxZ]} → 站位。 */
    @Nullable
    private static GridPos declaredStance(@Nullable Map<String, JsonElement> params) {
        if (params == null) return null;
        JsonElement el = params.get("task_bbox");
        if (el == null || !el.isJsonArray()) return null;
        JsonArray arr = el.getAsJsonArray();
        if (arr.size() != 6) {
            Log.warn(TAG, "task_bbox malformed (%s values) — falling back to op bbox", arr.size());
            return null;
        }
        try {
            return standoffStance(arr.get(0).getAsInt(), arr.get(1).getAsInt(),
                    arr.get(2).getAsInt(), arr.get(5).getAsInt());
        } catch (RuntimeException e) {
            Log.warn(TAG, "task_bbox unreadable (%s) — falling back to op bbox", el);
            return null;
        }
    }

    private static void collectTargets(AtomicOp op, int[] box, boolean[] hasTarget) {
        if (op instanceof AtomicOp.ClearBoxOp clear) {
            // 整箱清空只带盒子两个角（target() 是 min）：两角都要算进去，站位才与原先
            // 「盒内每格一条 air op」时一致，否则大建筑的法师会站到盒子一侧去。
            includeTarget(box, hasTarget, clear.min());
            includeTarget(box, hasTarget, clear.max());
        } else {
            includeTarget(box, hasTarget, op.target());
        }
        if (op instanceof AtomicOp.ParallelOp(List<AtomicOp> steps)) {
            for (AtomicOp sub : steps) {
                collectTargets(sub, box, hasTarget);
            }
        }
    }

    private static void includeTarget(int[] box, boolean[] hasTarget, @Nullable GridPos t) {
        if (t == null) return;
        hasTarget[0] = true;
        if (t.x() < box[0]) box[0] = t.x();
        if (t.x() > box[1]) box[1] = t.x();
        if (t.y() < box[2]) box[2] = t.y();
        if (t.z() < box[3]) box[3] = t.z();
        if (t.z() > box[4]) box[4] = t.z();
    }

}
