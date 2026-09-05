package com.wsteam.wandscape.content.npc.system;
import com.wsteam.wandscape.content.task.boundary.RitualOps;
import com.wsteam.wandscape.content.task.boundary.MovementOps;
import com.wsteam.wandscape.foundation.util.TickProfiler;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.foundation.registry.WandscapeConstants;
import com.wsteam.wandscape.content.task.component.NavigationState;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.component.TaskExecutor;
import com.wsteam.wandscape.content.task.ecs.EcsSystem;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.types.GridPos;
import com.wsteam.wandscape.content.task.types.RitualId;
import com.wsteam.wandscape.content.task.boundary.WandscapeRitualOps;
import com.wsteam.wandscape.content.magic.data.MagicDef;
import com.wsteam.wandscape.content.magic.internal.SpellbookLoader;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Single driver of all NPC movement.
 *
 * <p>Other systems request movement by writing {@link NavigationState}
 * (mode + target + future). This system picks it up on the next ECS tick
 * and drives the actual MC movement — pathfinding for short range,
 * self_teleport ritual via private queue for long range or pathfinding failure.
 *
 * <p>Registered after {@code TaskExecutionSystem} so that a navigation
 * request written during step-execution is picked up in the same
 * {@link World#tick(float)} call.
 */
public class NavigationSystem implements EcsSystem {

    private static final String TAG = "NavigationSystem";

    static final double STOP_RANGE_SQ = 25.0; // 5²
    static final double NAV_SPEED = 1.0;
    private static final int PATHFIND_TIMEOUT = 200;
    private static final int MAX_REPATH = 5;
    /** Base cooldown (ticks) between self_teleport casts; divided by SPELL_SPEED. */
    private static final int TELEPORT_COOLDOWN_TICKS = 150;
    /** 传送固定魔力消耗。 */
    private static final int TELEPORT_MANA_COST = 30;

    /**
     * 水中逼近判定：要算“已逼近目标”，到目标到达中心的历史最低 3D 距离须创新低 ≥ 该格数。
     * 慢泳渡河/潜水下潜每区间推进远超此值；水面原地扑腾/贴壁晃动噪声 < 1 格，不会误清停滞计数。
     */
    private static final double WATER_APPROACH_MIN_DIST = 1.0;
    /**
     * 水中逼近停滞连续区间数（每 STUCK_CHECK_INTERVAL_TICKS 判一次）达此值即切自传送脱困。
     * 约 4×60 = 240 tick 卡在无法再靠近目标的点才触发，正常渡河/水下工作全程持续逼近永不累积。
     */
    private static final int WATER_STALL_LIMIT = 4;

    private int tickCounter;

    @Override
    public void update(World world, float delta) {
        try (var span = com.wsteam.wandscape.foundation.util.TickProfiler.INSTANCE.start("ecs.navigation.tick")) {
        tickCounter++;

        List<Long> npcs = world.query(NavigationState.class, Position.class);
        for (long npcId : npcs) {
            NavigationState nav = world.get(npcId, NavigationState.class);
            if (nav == null || nav.mode == NavigationState.Mode.IDLE) continue;

            WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
            if (npc == null || npc.isRemoved()) {
                nav.reset();
                continue;
            }

            double dx = npc.getX() - (nav.target.x() + 0.5);
            double dz = npc.getZ() - (nav.target.z() + 0.5);
            double hDistSq = dx * dx + dz * dz;

            // Arrived (all modes): 水平距离 <= 5格（垂直高度任意）
            if (hDistSq <= STOP_RANGE_SQ) {
                arrive(nav, npc);
                continue;
            }

            // ---- First tick: initialise ----
            if (nav.startTick == 0) {
                nav.startTick = tickCounter;
                nav.lastCheckTick = tickCounter;
                nav.lastCheckX = npc.getX();
                nav.lastCheckZ = npc.getZ();

                // Distance > walkThreshold → skip pathfinding, use self_teleport ritual
                if (nav.mode == NavigationState.Mode.PATHFINDING
                        && hDistSq > (long) WandscapeConstants.NPC_WALK_THRESHOLD * WandscapeConstants.NPC_WALK_THRESHOLD) {
                    switchToRitualTeleport(nav, npcId, world);
                    continue;
                }

                npc.setAiWanderingEnabled(false);

                if (nav.mode == NavigationState.Mode.PATHFINDING) {
                    boolean ok = startPathfinding(nav, npc, npcId);
                    if (!ok) {
                        Log.debug(LogCategory.NPC, "nav", "NPC {} — pathfinding init failed, switching to teleport", npcId);
                        switchToRitualTeleport(nav, npcId, world);
                    }
                    continue;
                }
                // TELEPORT_WAITING / TELEPORT_RITUAL: fall through
            }

            switch (nav.mode) {
                case PATHFINDING -> tickPathfinding(nav, npc, npcId, world);
                case TELEPORT_WAITING -> tickTeleportWaiting(nav, npcId, world);
                case TELEPORT_RITUAL -> { /* ritual in private queue; arrival checked at top */ }
            }
        }
        }
    }

    // ---- PATHFINDING ----

    private void tickPathfinding(NavigationState nav, WandscapeNpc npc, long npcId, World world) {
        int elapsed = tickCounter - nav.startTick;

        if (npc.getNavigation().isDone()) {
            if (nav.repathCount < MAX_REPATH) {
                nav.repathCount++;
                BlockPos to = resolveWalkTarget(npc, nav.target);
                boolean ok = npc.getNavigation().moveTo(
                        to.getX() + 0.5, to.getY() + 1, to.getZ() + 0.5, NAV_SPEED);
                Log.debug(LogCategory.NPC, "nav", "NPC {} re-path #{}, elapsed={} ok={}",
                        npcId, nav.repathCount, elapsed, ok);
                if (!ok) {
                    Log.debug(LogCategory.NPC, "nav", "NPC {} — re-path failed, switching to teleport", npcId);
                    switchToRitualTeleport(nav, npcId, world);
                }
            } else {
                Log.debug(LogCategory.NPC, "nav", "NPC {} — re-paths exhausted, switching to teleport", npcId);
                switchToRitualTeleport(nav, npcId, world);
            }
            return;
        }

        // 渡水是合法的慢移动：原版水中移速（即便本模组把 WATER_MOVEMENT_EFFICIENCY 提到 1.0）
        // 仍明显慢于陆地，固定超时会把正在游过河/湖的 NPC 提前判死并触发传送。水中改靠下方
        // 卡死进度检测兜底——无水平推进（STUCK_* 三连）或有位移但逼近不了目标（水中净逼近判据，
        // 高岸水池这类游得动却爬不出去的困局）都会被传送；慢但持续逼近目标的不受影响。
        if (elapsed > PATHFIND_TIMEOUT && !npc.isInWater()) {
            Log.debug(LogCategory.NPC, "nav", "NPC {} — timeout {} ticks, switching to teleport", npcId, elapsed);
            switchToRitualTeleport(nav, npcId, world);
            return;
        }

        // Stuck check
        if (tickCounter - nav.lastCheckTick >= WandscapeConstants.STUCK_CHECK_INTERVAL_TICKS) {
            double progress = Math.abs(npc.getX() - nav.lastCheckX)
                    + Math.abs(npc.getZ() - nav.lastCheckZ);
            if (progress < WandscapeConstants.STUCK_MIN_MOVE_DISTANCE) {
                nav.stuckChecks++;
                Log.debug(LogCategory.NPC, "nav", "NPC {} — stuck check #{}, progress={}",
                        npcId, nav.stuckChecks, String.format("%.2f", progress));
                if (nav.stuckChecks >= WandscapeConstants.STUCK_MAX_RETRIES) {
                    Log.debug(LogCategory.NPC, "nav", "NPC {} — stuck, switching to teleport", npcId);
                    switchToRitualTeleport(nav, npcId, world);
                    return;
                }
            } else {
                nav.stuckChecks = 0;
                if (npc.isInWater()) {
                    GridPos waterTarget = nav.target;
                    if (waterTarget != null) {
                        // 到达中心与顶楼 arrive 判据一致：(x+0.5, y+1, z+0.5)。用 3D 距离而非仅水平——
                        // 潜水下潜/攀爬对目标只有垂直推进，若只看水平会误判成“逼近停滞”触发无效传送。
                        double dcx = waterTarget.x() + 0.5;
                        double dcy = waterTarget.y() + 1.0;
                        double dcz = waterTarget.z() + 0.5;
                        double vx = npc.getX() - dcx;
                        double vy = npc.getY() - dcy;
                        double vz = npc.getZ() - dcz;
                        double d = Math.sqrt(vx * vx + vy * vy + vz * vz);
                        if (nav.waterBestDist < 0) {
                            nav.waterBestDist = d; // 首区间惰性初始化，不计数
                        } else if (d < nav.waterBestDist - WATER_APPROACH_MIN_DIST) {
                            nav.waterBestDist = d;
                            nav.waterStallCount = 0; // 有新推进 → 正常渡河/水下，清零
                        } else {
                            nav.waterStallCount++;
                            Log.debug(LogCategory.NPC, "nav", "NPC {} — in water, best dist to target {}, stall #{}/{}",
                                    npcId, String.format("%.2f", nav.waterBestDist),
                                    nav.waterStallCount, WATER_STALL_LIMIT);
                            if (nav.waterStallCount >= WATER_STALL_LIMIT) {
                                Log.debug(LogCategory.NPC, "nav",
                                        "NPC {} — swimming but cannot approach target (high-bank water trap), teleporting",
                                        npcId);
                                switchToRitualTeleport(nav, npcId, world);
                                return;
                            }
                        }
                    }
                } else if (nav.waterStallCount > 0 || nav.waterBestDist >= 0) {
                    // 回到陆地：清掉水中探测状态，避免同一段 nav 跨两片水域时拿前一片的更近记录误判
                    nav.waterBestDist = -1.0;
                    nav.waterStallCount = 0;
                }
            }
            nav.lastCheckTick = tickCounter;
            nav.lastCheckX = npc.getX();
            nav.lastCheckZ = npc.getZ();
        }
    }

    /**
     * Resolves a walkable destination for pathfinding.
     * If the target block is solid (e.g. wall or in-ground foundation), targets the block above if clear.
     */
    private BlockPos resolveWalkTarget(WandscapeNpc npc, GridPos target) {
        BlockPos to = new BlockPos(target.x(), target.y(), target.z());
        if (npc.level().isLoaded(to) && npc.level().getBlockState(to).isSolid()) {
            BlockPos above = to.above();
            if (npc.level().isLoaded(above) && !npc.level().getBlockState(above).isSolid()) {
                return above;
            }
        }
        return to;
    }

    /**
     * Initialise pathfinding for a fresh request: vanilla A* to the target.
     * Returns false if movement cannot start at all.
     */
    private boolean startPathfinding(NavigationState nav, WandscapeNpc npc, long npcId) {
        GridPos target = nav.target;
        BlockPos to = resolveWalkTarget(npc, target);
        BlockPos from = npc.blockPosition();

        boolean ok = npc.getNavigation().moveTo(
                to.getX() + 0.5, to.getY() + 1, to.getZ() + 0.5, NAV_SPEED);
        if (!ok) {
            // 诊断：moveTo 返回 false（createPath 未找到路径）。打失败瞬间状态定位根因。
            var navPath = npc.getNavigation().getPath();
            Log.debug(LogCategory.NPC, "nav", "NPC {} moveTo FAIL dest=({},{},{}) from=({},{},{}) "
                            + "onGround={} y={} stepH={} loaded={} pathNodes={}",
                    npcId, to.getX(), to.getY(), to.getZ(),
                    from.getX(), from.getY(), from.getZ(),
                    npc.onGround(), npc.getY(), npc.maxUpStep(),
                    npc.level().isLoaded(to),
                    navPath != null ? navPath.getNodeCount() : -1);
        }
        return ok;
    }

    // ---- TELEPORT WAITING (spell-cooldown-gated, placeholder mode) ----

    private void tickTeleportWaiting(NavigationState nav, long npcId, World world) {
        switchToRitualTeleport(nav, npcId, world);
    }

    // ---- Ritual teleport (direct, no package queue) ----

    /**
     * Fire a {@code SELF_TELEPORT} ritual directly via {@code world.ritualOps}
     * instead of going through the NPC package queue.
     *
     * <p>The ritual's future replaces the failed nav future in
     * {@code TaskExecutor.pendingFuture} so TaskExec waits for the teleport
     * to complete before advancing. No packages are suspended or enqueued —
     * the current package stays in place and continues from its current step
     * once the NPC arrives at the target.
     *
     * <p>Teleport is a spell: gated by a per-NPC cooldown (base
     * {@code TELEPORT_COOLDOWN_TICKS}, shortened by SPELL_SPEED). On cooldown,
     * fall back to walking rather than standing.
     */
    private void switchToRitualTeleport(NavigationState nav, long npcId, World world) {
        // 空中（被怪击退/下落）：原版寻路对悬空起点必失败，此刻传送会白烧 CD+30 蓝——
        // 等落地后重试。战斗怪贴脸反复击退会让 NPC 频繁短暂离地，而 moveTo 每轮必失败→传送。
        // 水中游泳例外：浮在水里 onGround() 恒 false 但并非“被击退悬空”。若照常拦截，困在高岸
        // 深水池的 NPC 即使判定卡死也永远无法借自传送脱困（onGround false → 每轮直接 return，
        // startTick=0 复位 → 下轮又从寻路重新来，死循环）。游泳中同样放行，落点安全由
        // findSafeLanding 保证。
        WandscapeNpc airborne = EntityComponentBridge.INSTANCE.getNpc(npcId);
        if (airborne != null && !airborne.onGround() && !airborne.isInWater()) {
            nav.startTick = 0;
            return;
        }

        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        GridPos target = nav.target;

        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        // 门控：施法互斥锁 + 传送独立 CD + 固定魔力（magic_spells/teleport.json 数据驱动，缺失回退常量），
        // 任一不满足回退走路（而不是站等）。锁时长 = self_teleport 引导 tick（与 WandscapeRitualOps 引导时长对齐，防止引导期间并发施法）。
        MagicDef tp = SpellbookLoader.getSpec("teleport");
        int tpCd = tp != null ? tp.baseCooldown() : TELEPORT_COOLDOWN_TICKS;
        int tpMana = tp != null ? tp.manaCost() : TELEPORT_MANA_COST;
        if (npc != null && !npc.tryCastSpell("teleport", tpCd, tpMana,
                WandscapeRitualOps.channelTicks(RitualId.SELF_TELEPORT))) {
            Log.debug(LogCategory.NPC, "nav", "NPC {} — teleport gated (lock/CD/mana), walking instead", npcId);
            // 门控未通过（CD/锁/蓝）：真正开始走路，而不是站桩等 CD。startTick 保持非 0，
            // 避免下一 tick init 块再次进传送分支形成每 tick 空转（旧行为 startTick=0 → 每 tick
            // 重试门控 + 该走路时站着，直到 CD 结束才一次性传送）。中途 CD 就绪由
            // PATHFIND_TIMEOUT/卡住/重寻路失败后再切传送兜住。
            nav.mode = NavigationState.Mode.PATHFINDING;
            nav.startTick = tickCounter;
            if (!startPathfinding(nav, npc, npcId)) {
                // 连走路都起不来（如区块未加载）→ 退回原逻辑：下 tick 重试传送门控
                nav.startTick = 0;
            }
            return;
        }

        // ── Clear the failed nav future from TaskExecutor ──
        if (exec != null) {
            exec.pendingFuture = null;
            exec.pendingFutureIsNav = false;
        }

        // ── Cancel pathfinding (clears nav state) ──
        if (exec != null && world.movementOps != null) {
            world.movementOps.cancelNavigation(npcId);
        }

        // ── Restore nav state after cancelNavigation reset it ──
        nav.target = target;
        nav.startTick = tickCounter;

        // ── Direct ritual teleport — NO package queue manipulation ──
        if (world.ritualOps != null && target != null) {
            // 引导期间定身 + 减伤 75%（SelfDefenseHandler 消费；与 tryCastSpell 的锁时长对齐）
            if (npc != null) {
                npc.markTeleportChanneling(npc.level().getGameTime(),
                        WandscapeRitualOps.channelTicks(RitualId.SELF_TELEPORT));
            }
            CompletableFuture<Void> ritualFuture = world.ritualOps.beginRitual(
                    RitualId.SELF_TELEPORT, target, world, npcId, Map.of());
            if (exec != null) {
                exec.pendingFuture = ritualFuture;
                exec.pendingFutureIsNav = true;
            }
            nav.mode = NavigationState.Mode.TELEPORT_RITUAL;
            nav.stuckChecks = 0;
            nav.repathCount = 0;
            Log.debug(LogCategory.NPC, "nav", "NPC {} — self_teleport ritual fired → ({},{},{})",
                    npcId, target.x(), target.y(), target.z());
        } else {
            Log.warn(TAG, "[NavSys] NPC {} — cannot teleport: ritualOps={} target={}",
                    npcId, world.ritualOps != null, target != null);
        }
    }

    // ---- Internal ----

    private void arrive(NavigationState nav, WandscapeNpc npc) {
        npc.setAiWanderingEnabled(true);
        if (nav.future != null && !nav.future.isDone()) {
            nav.future.complete(null);
        }
        nav.reset();
    }
}
