package com.wsteam.wandscape.guard.executor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.guard.GuardConstants;
import com.wsteam.wandscape.guard.GuardZone;
import com.wsteam.wandscape.magic.data.MagicCircleSpec;
import com.wsteam.wandscape.magic.internal.MagicCircleLoader;
import com.wsteam.wandscape.magic.internal.MagicCaster;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.op.api.AtomicOp;
import com.wsteam.wandscape.op.executor.OpExecutor;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * 守卫攻击执行器：持续异步循环（一次守卫 = 一个 {@code guard:attack} 任务）。
 *
 * <p>循环（由 {@link #tickAll()} 驱动，从 {@code Wandscape.onServerTick} 调用）：
 * <ol>
 *   <li>从所有非停摆建筑包围盒重算攻击区（水平 ±attackRange）与脱离区（±releaseRange），Y 不变。</li>
 *   <li>攻击区找最近存活 {@code Enemy}（距施法 NPC）；无目标 → 看脱离区：仍有怪则待命重试，无怪则任务完成。</li>
 *   <li>视线（LOS）：持杖手 → 目标身体中心 射线被方块挡 → 待命重试。</li>
 *   <li>{@link MagicCaster#castNpcAt} 施法，然后等待光束走完（spawn delay + 法阵时长 + tail）再回 ②。</li>
 * </ol>
 *
 * <p>任务未完成前 future 保持未完成，NPC 保持 ACTIVE，不会被调度器改派/被打断。
 * 施法/伤害/视觉完全复用 magic 管道（MagicBeamEntity 每 tick 对束内 Enemy 造成 magic 伤害）。
 */
public final class GuardAttackExecutor implements OpExecutor<AtomicOp.AttackMonsterOp> {

    private static final String TAG = "GuardAttackExecutor";

    private record Pending(CompletableFuture<Void> future, long npcId, int remainingTicks,
                           int attackRange, int releaseRange, String circleId, int color) {}

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
        pending.add(new Pending(future, npcId, 1,
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
                next.add(new Pending(p.future(), p.npcId(), remaining,
                        p.attackRange(), p.releaseRange(), p.circleId(), p.color()));
                continue;
            }
            int wait = runCycle(p);
            if (wait < 0) {
                toComplete.add(p.future());
            } else {
                next.add(new Pending(p.future(), p.npcId(), Math.max(1, wait),
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

        List<GuardZone> attackZones = zones(level, p.attackRange());
        if (attackZones.isEmpty()) return -1; // 无建筑可守 → 完成

        LivingEntity target = nearestInZones(level, attackZones, npc.position());
        if (target == null) {
            // 无攻击目标：脱离区内仍有怪 → 保持守卫待命；彻底无怪 → 任务完成
            List<GuardZone> releaseZones = zones(level, p.releaseRange());
            return hasMonsterInZones(level, releaseZones)
                    ? GuardConstants.STANDBY_TICKS
                    : -1;
        }
        if (!hasLineOfSight(npc, target)) {
            return GuardConstants.STANDBY_TICKS; // 看不见 → 待命重试
        }
        boolean ok = MagicCaster.castNpcAt(level, npc, target, p.circleId(), p.color());
        if (!ok) {
            return GuardConstants.STANDBY_TICKS; // 已有未发射施法 / spec 缺失 → 待命重试
        }
        MagicCircleSpec spec = MagicCircleLoader.getSpec(p.circleId());
        int duration = spec != null ? spec.durationTicks : 120;
        return MagicCaster.BEAM_SPAWN_DELAY + duration + MagicCaster.BEAM_TAIL;
    }

    // ── 区域扫描 ──

    /** 所有非停摆建筑包围盒水平外扩 {@code range} 的守卫区。 */
    private static List<GuardZone> zones(ServerLevel level, int range) {
        List<GuardZone> zones = new ArrayList<>();
        BuildingApi api = buildingApi();
        if (api == null) return zones;
        for (BuildingData b : api.getColonyBuildings(null)) {
            if (b.isShutdown() || b.isDemolishing()) continue;
            BoundingBox bb = api.getBuildingBounds(b.getBuildingId());
            if (bb == null) continue;
            zones.add(GuardZone.of(bb.minX(), bb.minY(), bb.minZ(),
                    bb.maxX(), bb.maxY(), bb.maxZ(), range));
        }
        return zones;
    }

    @Nullable
    private static BuildingApi buildingApi() {
        try {
            return WandscapeApis.getBuildingApi();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    /** 各区域并集 AABB（含边界块）内查询 Enemy，过滤后返回距 {@code from} 最近者。 */
    @Nullable
    private static LivingEntity nearestInZones(ServerLevel level, List<GuardZone> zones, Vec3 from) {
        AABB queryBox = unionAabb(zones);
        if (queryBox == null) return null;
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : level.getEntities((Entity) null, queryBox, e -> e instanceof Enemy)) {
            if (!(e instanceof LivingEntity mob) || mob.isRemoved() || !mob.isAlive()) continue;
            if (!inAnyZone(mob, zones)) continue;
            double d = mob.distanceToSqr(from);
            if (d < bestSq) {
                bestSq = d;
                best = mob;
            }
        }
        return best;
    }

    private static boolean hasMonsterInZones(ServerLevel level, List<GuardZone> zones) {
        AABB queryBox = unionAabb(zones);
        if (queryBox == null) return false;
        for (Entity e : level.getEntities((Entity) null, queryBox, e -> e instanceof Enemy)) {
            if (e instanceof LivingEntity mob && !mob.isRemoved() && mob.isAlive()
                    && inAnyZone(mob, zones)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inAnyZone(LivingEntity mob, List<GuardZone> zones) {
        for (GuardZone z : zones) {
            if (z.contains(mob.getX(), mob.getY(), mob.getZ())) return true;
        }
        return false;
    }

    /** 并集查询盒：块坐标含边界，故 AABB max 取 max+1 以覆盖整块。 */
    @Nullable
    private static AABB unionAabb(List<GuardZone> zones) {
        if (zones.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (GuardZone z : zones) {
            minX = Math.min(minX, z.minX());
            minY = Math.min(minY, z.minY());
            minZ = Math.min(minZ, z.minZ());
            maxX = Math.max(maxX, z.maxX());
            maxY = Math.max(maxY, z.maxY());
            maxZ = Math.max(maxZ, z.maxZ());
        }
        return new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
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
}
