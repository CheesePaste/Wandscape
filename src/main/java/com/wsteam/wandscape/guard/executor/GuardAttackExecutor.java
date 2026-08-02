package com.wsteam.wandscape.guard.executor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

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
    /** 两次施法最小间隔（tick），防光束实体未找到时堆叠。 */
    private static final int CAST_MIN_INTERVAL = 40;

    private record Pending(CompletableFuture<Void> future, World world, long npcId, int remainingTicks,
                           int attackRange, int releaseRange, String circleId, int color) {}

    private final List<Pending> pending = new ArrayList<>();
    /** npcId → 上次成功施法的 gameTime（施法节流）。 */
    private final Map<Long, Long> lastCastTick = new HashMap<>();

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
                op.attackRange(), op.releaseRange(), op.circleId(), op.color()));
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
                        p.attackRange(), p.releaseRange(), p.circleId(), p.color()));
                continue;
            }
            int wait = runCycle(p);
            if (wait < 0) {
                toComplete.add(p.future());
                lastCastTick.remove(p.npcId());
            } else {
                next.add(new Pending(p.future(), p.world(), p.npcId(), Math.max(1, wait),
                        p.attackRange(), p.releaseRange(), p.circleId(), p.color()));
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

    /** 一轮守卫循环。返回下次等待 tick 数；负数表示任务完成。 */
    private int runCycle(Pending p) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(p.npcId());
        if (npc == null || npc.isRemoved()) return -1;
        if (!(npc.level() instanceof ServerLevel level)) return -1;

        List<GuardZone> attackZones = GuardScanner.zones(level, p.attackRange());
        if (attackZones.isEmpty()) return -1; // 无建筑可守 → 完成

        LivingEntity nearest = GuardScanner.nearestInZones(level, attackZones, npc.position());
        if (nearest == null) {
            // 无攻击目标：脱离区内仍有怪 → 保持守卫待命；彻底无怪 → 任务完成
            List<GuardZone> releaseZones = GuardScanner.zones(level, p.releaseRange());
            if (!GuardScanner.hasMonsterInZones(level, releaseZones)) {
                MagicBeamEntity beam = findActiveBeam(level, npc);
                if (beam != null) beam.setLifetime(5); // 脱离时让光束快速淡出
                cancelNavigation(p);                    // 停止寻路，NPC 站定
                return -1;
            }
            return RECHECK_TICKS;
        }

        MagicBeamEntity beam = findActiveBeam(level, npc);
        if (beam != null) {
            beam.retarget(nearest); // 主动切换：光束持续指向最近的怪物
        }

        if (!hasLineOfSight(npc, nearest)) {
            // 看不见（隔墙）：旧光束会在墙上拖拽，先让它快速消失；然后寻路到能打到的位置
            if (beam != null) beam.setLifetime(5);
            navigateToward(p, npc, nearest);
            return RECHECK_TICKS;
        }

        // 看得见：停止移动，确保有光束（没有才施法，靠 ACTIVE_CASTERS + 节流防堆叠）
        cancelNavigation(p);
        if (beam == null && level.getGameTime() - lastCastTick.getOrDefault(p.npcId(), 0L) >= CAST_MIN_INTERVAL) {
            boolean ok = MagicCaster.castNpcAt(level, npc, nearest, p.circleId(), p.color());
            if (ok) {
                lastCastTick.put(p.npcId(), level.getGameTime());
            }
        }
        return RECHECK_TICKS;
    }

    // ── 光束 ──

    /** 找该 NPC 当前活跃的信标光束（光束源点位于持杖手附近，故在 NPC 周围小范围查询）。 */
    private static MagicBeamEntity findActiveBeam(ServerLevel level, WandscapeNpc npc) {
        AABB box = npc.getBoundingBox().inflate(30);
        for (MagicBeamEntity beam : level.getEntitiesOfClass(MagicBeamEntity.class, box)) {
            if (beam.isRemoved()) continue;
            if (beam.getCasterUuid().map(u -> u.equals(npc.getUUID())).orElse(false)) {
                return beam;
            }
        }
        return null;
    }

    // ── 视线 ──

    /** 持杖手 → 目标身体中心 射线无方块阻挡（起点沿方向前移 0.5 避免自贴墙误判）。 */
    private static boolean hasLineOfSight(WandscapeNpc npc, LivingEntity target) {
        if (!(npc.level() instanceof ServerLevel level)) return false;
        Vec3 from = npc.getStaffPosition();
        Vec3 to = target.getBoundingBox().getCenter();
        Vec3 delta = to.subtract(from);
        double dist = delta.length();
        if (dist < 0.1) return true;
        Vec3 start = from.add(delta.normalize().scale(0.5));
        HitResult hit = level.clip(new ClipContext(start, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() != HitResult.Type.BLOCK;
    }

    // ── 寻路 ──

    /** LOS 被挡时，向怪物位置寻路（寻路会绕过墙体，LOS 一清就停手施法）。 */
    private static void navigateToward(Pending p, WandscapeNpc npc, LivingEntity nearest) {
        World world = p.world();
        if (world == null || world.movementOps == null) return;
        NavigationState nav = world.get(p.npcId(), NavigationState.class);
        if (nav != null && nav.mode != NavigationState.Mode.IDLE) return; // 已在寻路中

        Vec3 mobPos = nearest.position();
        world.movementOps.navigateTo(p.npcId(),
                Mth.floor(mobPos.x), Mth.floor(mobPos.y), Mth.floor(mobPos.z));
    }

    /** 停止寻路（LOS 已通过 / 要施法时调用），让 NPC 站定。 */
    private static void cancelNavigation(Pending p) {
        World world = p.world();
        if (world == null || world.movementOps == null) return;
        NavigationState nav = world.get(p.npcId(), NavigationState.class);
        if (nav != null && nav.mode != NavigationState.Mode.IDLE) {
            world.movementOps.cancelNavigation(p.npcId());
        }
    }
}
