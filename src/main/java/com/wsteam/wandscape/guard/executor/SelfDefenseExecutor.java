package com.wsteam.wandscape.guard.executor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.core.component.NpcTaskQueue;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.magic.internal.MagicCaster;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.op.api.AtomicOp;
import com.wsteam.wandscape.op.executor.OpExecutor;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.task.runtime.NpcTaskPackage;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * NPC 自防御执行器：主动仇恨范围 + 受伤反击，**独立于守卫任务**。
 *
 * <p>每个 NPC 一个持续异步循环（一次自防御 = 一个注入私有队列的 {@code self_defense}
 * 包），由 {@link #tick(World)} 驱动（从 {@code Wandscape.onServerTick} 调用）：
 * <ol>
 *   <li>节流侦测（每 {@link #DETECT_INTERVAL_TICKS} tick）：遍历所有 NPC，有有效目标
 *       （仇恨目标优先、否则半径内最近 {@code Enemy}）且未在战斗中 → **抢占**：暂停当前包
 *       （{@link NpcTaskQueue#suspendCurrent}，挂起栈满则跳过），注入 {@code self_defense} 包。
 *       <b>和平模式 NPC 不索敌不反击</b>，但被怪贴到 {@code Config.GUARD_PEACE_FLEE_RANGE} 内时
 *       同样抢占注入——持续循环按「逃跑」处理（{@link GuardCombat#navigateAway} 后撤）。</li>
 *   <li>持续循环（每 {@link #RECHECK_TICKS} tick 一轮）：重解析目标 → 无目标则 complete future
 *       （队列 {@code finishCurrentPackage} 自动 {@code resumeLatest} 恢复原包）；有目标则交给
 *       {@link GuardCombat#engage} 战斗（光束重定向 / LOS / 寻路 / 施法 / 风筝走位）。</li>
 * </ol>
 *
 * <p>抢占边界：挂起前若 NPC 正卡在异步 op（{@code pendingFuture} 未完成），先分离该 future
 * （底层执行器独立推进，完成后 {@code World.startAsyncOp} 自动清理；若是导航 future 则取消导航），
 * 否则任务执行系统会一直等旧 future、不转去执行自防御包。
 */
public final class SelfDefenseExecutor implements OpExecutor<AtomicOp.SelfDefenseOp> {

    private static final String TAG = "SelfDefense";
    /** 循环重检间隔（tick）：重选目标 / LOS 重查 / 施法节拍。 */
    private static final int RECHECK_TICKS = 10;
    /** 和平模式逃跑循环重检间隔（tick）。 */
    private static final int FLEE_RECHECK_TICKS = 10;
    /** 侦测间隔（tick）：遍历 NPC 检查是否需抢占。 */
    private static final int DETECT_INTERVAL_TICKS = 4;
    /** 自防御包优先级（仅日志/文档意义；队列按 LIFO 挂起栈 + FIFO 执行，非按此排序）。 */
    private static final int SELF_DEFENSE_PRIORITY = 90;

    private record Pending(CompletableFuture<Void> future, World world, long npcId, int remainingTicks,
                           int radius) {}

    private final List<Pending> pending = new ArrayList<>();
    private int detectTickCounter = 0;

    @Override
    public Class<AtomicOp.SelfDefenseOp> opType() {
        return AtomicOp.SelfDefenseOp.class;
    }

    @Override
    public CompletableFuture<Void> execute(AtomicOp.SelfDefenseOp op, World world, long npcId) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        if (npc == null || npc.level().isClientSide) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = world.startAsyncOp("self_defense");
        pending.add(new Pending(future, world, npcId, 1, op.radius()));
        return future;
    }

    /** 每 MC tick 调用：先节流侦测抢占，再驱动持续循环。 */
    public void tick(World world) {
        detectAndInject(world);
        tickAll();
    }

    // ── 侦测 + 抢占注入 ──

    private void detectAndInject(World world) {
        if (++detectTickCounter % DETECT_INTERVAL_TICKS != 0) return;

        for (Map.Entry<Long, WandscapeNpc> entry : EntityComponentBridge.INSTANCE.allNpcs().entrySet()) {
            long npcId = entry.getKey();
            WandscapeNpc npc = entry.getValue();
            if (npc == null || npc.isRemoved() || npc.level().isClientSide) continue;

            TaskExecutor exec = world.get(npcId, TaskExecutor.class);
            if (exec == null) continue;
            NpcTaskQueue queue = exec.npcQueue;
            if (isAlreadyFighting(exec, queue, world)) continue;
            if (!(npc.level() instanceof ServerLevel level)) continue;

            // 和平模式：不主动索敌、不反击，但被怪贴到 peaceFleeRange 内会临时逃离
            if (npc.isPeaceMode()) {
                LivingEntity threat = nearestVisibleEnemyAround(npc, level,
                        Config.GUARD_PEACE_FLEE_RANGE.get());
                if (threat != null) {
                    injectSelfDefense(world, npcId, exec, queue, level, "flee",
                            threat.getName().getString());
                }
                continue;
            }

            LivingEntity target = resolveTarget(npc, level);
            if (target == null) continue;

            injectSelfDefense(world, npcId, exec, queue, level, "self-defense",
                    target.getName().getString());
        }
    }

    /**
     * 抢占注入 self_defense 包（正常战斗 / 和平逃跑共用）：
     * 若正卡在异步 op，先分离 pendingFuture，让任务执行系统转到自防御包；挂起栈满则放弃。
     */
    private static void injectSelfDefense(World world, long npcId,
                                          TaskExecutor exec, NpcTaskQueue queue, ServerLevel level,
                                          String what, String targetName) {
        if (exec.pendingFuture != null) {
            if (exec.pendingFutureIsNav && world.movementOps != null) {
                world.movementOps.cancelNavigation(npcId);
            }
            exec.pendingFuture = null;
            exec.pendingFutureIsNav = false;
        }
        boolean hadPackage = queue.currentPackage() != null;
        if (hadPackage && queue.suspendCurrent(level.getGameTime()) == null) {
            return; // 挂起栈满，不能覆盖当前包
        }
        queue.startPackage(NpcTaskPackage.system("self_defense",
                new AtomicOp.SelfDefenseOp(Config.GUARD_SELF_DEFENSE_RANGE.get()),
                null, SELF_DEFENSE_PRIORITY));
        Log.info(TAG, "NPC {} engages {} target={} preempted={}",
                npcId, what, targetName, hadPackage ? "yes" : "idle");
    }

    /** 当前包已是自防御 / 建筑守卫（guard:attack）时不再叠加。 */
    private static boolean isAlreadyFighting(TaskExecutor exec, NpcTaskQueue queue, World world) {
        NpcTaskPackage current = queue.currentPackage();
        if (current == null) return false;
        if (current.source().equals("self_defense")) return true;
        if (exec.globalTaskId != null && world.taskPool != null) {
            GlobalTask t = world.taskPool.get(exec.globalTaskId);
            if (t != null && t.blueprintId != null && t.blueprintId.startsWith("guard:")) return true;
        }
        return false;
    }

    /** 目标解析：仇恨目标（存活、非玩家、hateRange 内、可见）优先，否则半径内最近可见 {@code Enemy}。
     *  地下/隔墙看不见的怪物不锁为目标。 */
    @Nullable
    private static LivingEntity resolveTarget(WandscapeNpc npc, ServerLevel level) {
        int hateRange = Config.GUARD_HATE_RANGE.get();
        LivingEntity hated = npc.getHatedAttacker(level);
        if (hated != null && !(hated instanceof Player)
                && npc.distanceToSqr(hated) <= (double) hateRange * hateRange
                && GuardCombat.hasLineOfSight(npc, hated)) {
            return hated;
        }
        return nearestVisibleEnemyAround(npc, level, Config.GUARD_SELF_DEFENSE_RANGE.get());
    }

    /** 半径内最近可见存活 {@code Enemy}（球面距离 + LOS）；无则 null。 */
    @Nullable
    private static LivingEntity nearestVisibleEnemyAround(WandscapeNpc npc, ServerLevel level, int radius) {
        LivingEntity nearest = null;
        double bestSq = (double) radius * radius;
        Vec3 pos = npc.position();
        for (Entity e : level.getEntities((Entity) null, npc.getBoundingBox().inflate(radius), e -> e instanceof Enemy)) {
            if (!(e instanceof LivingEntity mob) || mob.isRemoved() || !mob.isAlive()) continue;
            if (!GuardCombat.hasLineOfSight(npc, mob)) continue;
            double d = mob.distanceToSqr(pos);
            if (d <= bestSq) {
                bestSq = d;
                nearest = mob;
            }
        }
        return nearest;
    }

    // ── 持续循环（镜像 GuardAttackExecutor.tickAll） ──

    private void tickAll() {
        if (pending.isEmpty()) return;

        List<Pending> next = new ArrayList<>(pending.size());
        List<CompletableFuture<Void>> toComplete = new ArrayList<>();

        for (Pending p : pending) {
            int remaining = p.remainingTicks() - 1;
            if (remaining > 0) {
                next.add(new Pending(p.future(), p.world(), p.npcId(), remaining, p.radius()));
                continue;
            }
            int wait = runCycle(p);
            if (wait < 0) {
                toComplete.add(p.future());
            } else {
                next.add(new Pending(p.future(), p.world(), p.npcId(), Math.max(1, wait), p.radius()));
            }
        }

        pending.clear();
        pending.addAll(next);
        for (CompletableFuture<Void> f : toComplete) {
            f.complete(null);
        }
        if (!toComplete.isEmpty()) {
            Log.info(TAG, "self-defense complete — {} done, {} pending", toComplete.size(), pending.size());
        }
    }

    /** 一轮自防御循环。返回下次等待 tick 数；负数表示任务完成（队列恢复挂起任务）。 */
    private int runCycle(Pending p) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(p.npcId());
        if (npc == null || npc.isRemoved()) return -1;
        if (!(npc.level() instanceof ServerLevel level)) {
            GuardCombat.markCombatEnd(npc);
            return -1;
        }
        // 和平模式：不战斗，只逃离可见威胁；无威胁 → 完成（队列恢复挂起任务）。
        // 战斗中途开启和平 → 同样转入逃离（原逻辑是立即结束自防御）。
        if (npc.isPeaceMode()) {
            LivingEntity threat = nearestVisibleEnemyAround(npc, level,
                    Config.GUARD_PEACE_FLEE_RANGE.get());
            if (threat == null) {
                GuardCombat.markCombatEnd(npc);
                return -1;
            }
            // 和平逃跑也走战斗态（禁 wandering/施法硬钉），逃跑导航才不被钉住
            GuardCombat.markInCombat(npc);
            GuardCombat.navigateAway(level, npc, p.world(), p.npcId(),
                    threat.getBoundingBox().getCenter());
            return FLEE_RECHECK_TICKS;
        }

        LivingEntity target = resolveTarget(npc, level);
        if (target == null) {
            // 无目标：仇恨已死/过期、半径内无怪 → 完成，队列恢复挂起任务
            npc.clearHatedAttackerIfExpired(level);
            GuardCombat.markCombatEnd(npc);
            return -1;
        }
        // 施法视觉（法阵/颜色）由 beam MagicDef 定义（magic_spells/beam.json），随魔法数据走
        GuardCombat.engage(level, npc, target, p.world(), p.npcId(),
                MagicCaster.beamCircleId(), MagicCaster.beamColor());
        return RECHECK_TICKS;
    }
}
