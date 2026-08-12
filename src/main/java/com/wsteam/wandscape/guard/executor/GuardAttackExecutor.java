package com.wsteam.wandscape.guard.executor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.wsteam.wandscape.core.component.NavigationState;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.guard.GuardScanner;
import com.wsteam.wandscape.guard.GuardZone;
import com.wsteam.wandscape.magic.entity.MagicBeamEntity;
import com.wsteam.wandscape.magic.internal.MagicCaster;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.op.api.AtomicOp;
import com.wsteam.wandscape.op.executor.OpExecutor;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/**
 * 守卫攻击执行器：持续异步循环（一次守卫 = 一个 {@code guard:attack} 任务）。
 *
 * <p>循环（由 {@link #tickAll()} 驱动，从 {@code Wandscape.onServerTick} 调用，每 {@link #RECHECK_TICKS} tick 一轮）：
 * <ol>
 *   <li>从所有非停摆建筑包围盒重算攻击区（水平 ±attackRange）与脱离区（±releaseRange），Y 不变。</li>
 *   <li>攻击区找最近存活 {@code Enemy}（距施法 NPC）；无目标 → 看脱离区：仍有怪则待命重试，无怪则任务完成。</li>
 *   <li>有目标 → **主动切换**：把当前光束重新指向最近怪物（{@link MagicBeamEntity#retarget}）。</li>
 *   <li>视线（LOS）通过 → 无光束则施法，有则靠 ③ 持续指向最近；LOS 被方块挡 → **寻路**到能打到怪物的位置。</li>
 * </ol>
 *
 * <p>任务未完成前 future 保持未完成，NPC 保持 ACTIVE，不会被调度器改派/被打断。
 * 施法/伤害/视觉完全复用 magic 管道（MagicBeamEntity 每 tick 对束内 Enemy 造成 magic 伤害）。
 */
public final class GuardAttackExecutor implements OpExecutor<AtomicOp.AttackMonsterOp> {

    private static final String TAG = "GuardAttackExecutor";

    /** 循环重检间隔（tick）：主动切换最近目标 / LOS 重查 / 施法节拍。 */
    private static final int RECHECK_TICKS = 10;

    /** 无视线/不可达超时时间（tick）：连续 10 秒打不到怪且无视线，超时放弃任务。 */
    private static final int UNREACHABLE_TIMEOUT_TICKS = 200;
    /** 不可达怪物黑名单时长（tick）：放弃后 30 秒内不再对其触发守卫任务。 */
    private static final int UNREACHABLE_BLACK_DURATION_TICKS = 600;

    private record Pending(CompletableFuture<Void> future, World world, long npcId, int remainingTicks,
                           int attackRange, int releaseRange, int noLosTicks) {}

    private record CycleResult(int waitTicks, int noLosTicks) {}

    private final List<Pending> pending = new ArrayList<>();

    @Override
    public Class<AtomicOp.AttackMonsterOp> opType() {
        return AtomicOp.AttackMonsterOp.class;
    }

    @Override
    public CompletableFuture<Void> execute(AtomicOp.AttackMonsterOp op, World world, long npcId) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        if (npc == null || npc.level().isClientSide) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = world.startAsyncOp("guard_attack");
        pending.add(new Pending(future, world, npcId, 1,
                op.attackRange(), op.releaseRange(), 0));
        return future;
    }

    /** 每个 MC tick 调用：倒数等待，到期执行一轮循环；返回 -1 的任务 complete future。 */
    public void tickAll() {
        if (pending.isEmpty()) return;

        List<Pending> next = new ArrayList<>(pending.size());
        List<CompletableFuture<Void>> toComplete = new ArrayList<>();

        for (Pending p : pending) {
            int remaining = p.remainingTicks() - 1;
            if (remaining > 0) {
                next.add(new Pending(p.future(), p.world(), p.npcId(), remaining,
                        p.attackRange(), p.releaseRange(), p.noLosTicks()));
                continue;
            }
            CycleResult res = runCycle(p);
            if (res.waitTicks() < 0) {
                toComplete.add(p.future());
            } else {
                next.add(new Pending(p.future(), p.world(), p.npcId(), Math.max(1, res.waitTicks()),
                        p.attackRange(), p.releaseRange(), res.noLosTicks()));
            }
        }

        pending.clear();
        pending.addAll(next);
        for (CompletableFuture<Void> f : toComplete) {
            f.complete(null);
        }
        if (!toComplete.isEmpty()) {
            Log.info(TAG, "guard task completed — {} done, {} pending", toComplete.size(), pending.size());
        }
    }

    /** 一轮守卫循环。返回 CycleResult，waitTicks < 0 表示任务完成/放弃。 */
    private CycleResult runCycle(Pending p) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(p.npcId());
        if (npc == null || npc.isRemoved()) return new CycleResult(-1, 0);
        if (!(npc.level() instanceof ServerLevel level)) {
            GuardCombat.markCombatEnd(npc);
            return new CycleResult(-1, 0);
        }
        // 守卫生效到和平模式：立即完成任务并让光束淡出（任务会被 GuardTaskSource 重新发布，
        // 交给非和平 NPC；全殖民地都和平则不再发布守卫任务）
        if (npc.isPeaceMode()) {
            MagicBeamEntity beam = GuardCombat.findActiveBeam(level, npc);
            if (beam != null) beam.setLifetime(5);
            GuardCombat.cancelNavigation(p.world(), p.npcId());
            GuardCombat.markCombatEnd(npc);
            return new CycleResult(-1, 0);
        }

        List<GuardZone> attackZones = GuardScanner.zones(level, p.attackRange());
        if (attackZones.isEmpty()) { // 无建筑可守 → 完成
            GuardCombat.markCombatEnd(npc);
            return new CycleResult(-1, 0);
        }

        LivingEntity nearest = GuardScanner.nearestInZones(level, attackZones, npc.position());
        if (nearest == null) {
            // 无攻击目标：脱离区内仍有怪 → 保持守卫待命；彻底无怪 → 任务完成
            List<GuardZone> releaseZones = GuardScanner.zones(level, p.releaseRange());
            if (!GuardScanner.hasMonsterInZones(level, releaseZones)) {
                MagicBeamEntity beam = GuardCombat.findActiveBeam(level, npc);
                if (beam != null) beam.setLifetime(5); // 脱离时让光束快速淡出
                GuardCombat.cancelNavigation(p.world(), p.npcId()); // 停止寻路，NPC 站定
                GuardCombat.markCombatEnd(npc);
                return new CycleResult(-1, 0);
            }
            return new CycleResult(RECHECK_TICKS, 0);
        }

        // 视线与无视线超时处理：只有在真正的跨区域寻路赶路中（距离目标>5格且未到达）才暂停计时；
        // 若到达落点/卡在房顶/静止且无视线，稳健累加无视线超时
        boolean hasLos = GuardCombat.hasLineOfSight(npc, nearest);
        NavigationState nav = p.world().get(p.npcId(), NavigationState.class);
        boolean moving = isActuallyMoving(npc, nav);

        int nextNoLos = (hasLos || moving) ? 0 : p.noLosTicks() + RECHECK_TICKS;
        if (!hasLos && nextNoLos >= UNREACHABLE_TIMEOUT_TICKS) {
            Log.info(TAG, "[GuardAttackExecutor] NPC {} — stationary/stuck without LOS to target '{}' for {} ticks, abandoning task & blacklisting mob #{}",
                    p.npcId(), nearest.getName().getString(), nextNoLos, nearest.getId());
            GuardScanner.blacklistMob(nearest.getId(), level.getGameTime(), UNREACHABLE_BLACK_DURATION_TICKS);
            MagicBeamEntity beam = GuardCombat.findActiveBeam(level, npc);
            if (beam != null) beam.setLifetime(5);
            GuardCombat.cancelNavigation(p.world(), p.npcId());
            GuardCombat.markCombatEnd(npc);
            return new CycleResult(-1, 0);
        }

        // 施法视觉（法阵/颜色）由 beam MagicDef 定义（magic_spells/beam.json），随魔法数据走
        GuardCombat.engage(level, npc, nearest, p.world(), p.npcId(),
                MagicCaster.beamCircleId(), MagicCaster.beamColor());
        return new CycleResult(RECHECK_TICKS, nextNoLos);
    }

    /**
     * 判断 NPC 是否处于真正的大跨度寻路赶路中：
     * 1. 处于 PATHFINDING 模式且 target 不为空；
     * 2. 距离导航目标水平距离 > 5 格 (25.0)；
     * 3. 导航未完成；
     * 4. 非传送引导中。
     * 若已传送到达落点/在房顶小范围打转/在目的地附近/已卡住，均返回 false（允许正常累加无视线超时）。
     */
    private static boolean isActuallyMoving(WandscapeNpc npc, NavigationState nav) {
        if (nav == null || nav.mode != NavigationState.Mode.PATHFINDING || nav.target == null) {
            return false;
        }
        if (npc.isTeleportChanneling(npc.level().getGameTime())) {
            return false;
        }
        if (npc.getNavigation().isDone()) {
            return false;
        }
        double dx = npc.getX() - (nav.target.x() + 0.5);
        double dz = npc.getZ() - (nav.target.z() + 0.5);
        return (dx * dx + dz * dz) > 25.0;
    }
}
