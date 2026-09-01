package com.wsteam.wandscape.content.npc.system;
import com.wsteam.wandscape.content.task.boundary.RitualOps;
import com.wsteam.wandscape.content.task.boundary.MovementOps;
import com.wsteam.wandscape.foundation.util.TickProfiler;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.content.task.component.NavigationState;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.component.TaskExecutor;
import com.wsteam.wandscape.content.task.ecs.System;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.types.GridPos;
import com.wsteam.wandscape.content.task.types.RitualId;
import com.wsteam.wandscape.content.task.boundary.WandscapeRitualOps;
import com.wsteam.wandscape.content.magic.data.MagicDef;
import com.wsteam.wandscape.content.magic.internal.SpellbookLoader;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.foundation.log.Log;
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
public class NavigationSystem implements System {

    private static final String TAG = "NavigationSystem";

    static final double STOP_RANGE_SQ = 25.0; // 5²
    static final double NAV_SPEED = 1.0;
    private static final int PATHFIND_TIMEOUT = 200;
    private static final int MAX_REPATH = 5;
    /** Base cooldown (ticks) between self_teleport casts; divided by SPELL_SPEED. */
    private static final int TELEPORT_COOLDOWN_TICKS = 150;
    /** 传送固定魔力消耗。 */
    private static final int TELEPORT_MANA_COST = 30;

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
            double dy = Math.abs(npc.getY() - (nav.target.y() + 1.0));

            // Arrived (all modes): 水平距离 <= 5格 且 垂直高度差 <= 3.5格（避免身处地下矿洞/屋顶盲区时误判已到达）
            if (hDistSq <= STOP_RANGE_SQ && dy <= 3.5) {
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
                        && (hDistSq > (long) Config.NPC_WALK_THRESHOLD.get() * Config.NPC_WALK_THRESHOLD.get()
                            || dy > Config.NPC_WALK_THRESHOLD.get())) {
                    switchToRitualTeleport(nav, npcId, world);
                    continue;
                }

                npc.setAiWanderingEnabled(false);

                if (nav.mode == NavigationState.Mode.PATHFINDING) {
                    boolean ok = startPathfinding(nav, npc, npcId);
                    if (!ok) {
                        Log.info(TAG, "[NavSys] NPC {} — pathfinding init failed, switching to teleport", npcId);
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
                Log.info(TAG, "[NavSys] NPC {} re-path #{}, elapsed={} ok={}",
                        npcId, nav.repathCount, elapsed, ok);
                if (!ok) {
                    Log.info(TAG, "[NavSys] NPC {} — re-path failed, switching to teleport", npcId);
                    switchToRitualTeleport(nav, npcId, world);
                }
            } else {
                Log.info(TAG, "[NavSys] NPC {} — re-paths exhausted, switching to teleport", npcId);
                switchToRitualTeleport(nav, npcId, world);
            }
            return;
        }

        // 渡水是合法的慢移动：原版水中移速（即便本模组把 WATER_MOVEMENT_EFFICIENCY 提到 1.0）
        // 仍明显慢于陆地，固定超时会把正在游过河/湖的 NPC 提前判死并触发传送。水中改靠下方
        // 卡死进度检测（STUCK_* 三连）兜底——真正卡死（无水平推进）照样会被传送，慢但前进的不受影响。
        if (elapsed > PATHFIND_TIMEOUT && !npc.isInWater()) {
            Log.info(TAG, "[NavSys] NPC {} — timeout {} ticks, switching to teleport", npcId, elapsed);
            switchToRitualTeleport(nav, npcId, world);
            return;
        }

        // Stuck check
        if (tickCounter - nav.lastCheckTick >= Config.STUCK_CHECK_INTERVAL_TICKS.get()) {
            double progress = Math.abs(npc.getX() - nav.lastCheckX)
                    + Math.abs(npc.getZ() - nav.lastCheckZ);
            if (progress < Config.STUCK_MIN_MOVE_DISTANCE.get()) {
                nav.stuckChecks++;
                Log.info(TAG, "[NavSys] NPC {} — stuck check #{}, progress={}",
                        npcId, nav.stuckChecks, String.format("%.2f", progress));
                if (nav.stuckChecks >= Config.STUCK_MAX_RETRIES.get()) {
                    Log.info(TAG, "[NavSys] NPC {} — stuck, switching to teleport", npcId);
                    switchToRitualTeleport(nav, npcId, world);
                    return;
                }
            } else {
                nav.stuckChecks = 0;
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
            Log.info(TAG, "[NavSys] NPC {} moveTo FAIL dest=({},{},{}) from=({},{},{}) "
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
        WandscapeNpc airborne = EntityComponentBridge.INSTANCE.getNpc(npcId);
        if (airborne != null && !airborne.onGround()) {
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
            Log.info(TAG, "[NavSys] NPC {} — teleport gated (lock/CD/mana), walking instead", npcId);
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
            Log.info(TAG, "[NavSys] NPC {} — self_teleport ritual fired → ({},{},{})",
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
