package com.wsteam.wandscape.engine.system;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.wsteam.wandscape.core.component.ManaPool;
import com.wsteam.wandscape.core.component.NavigationState;
import com.wsteam.wandscape.core.component.Position;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.core.ecs.System;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.RitualId;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.shared.log.Log;

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
    private static final int PATHFIND_MAX_RANGE = 32;
    static final double NAV_SPEED = 1.0;
    private static final int STUCK_CHECK_INTERVAL = 60;
    private static final int MAX_STUCK_CHECKS = 3;
    private static final double STUCK_MIN_PROGRESS = 2.0;
    private static final int PATHFIND_TIMEOUT = 200;
    private static final int MAX_REPATH = 5;

    private int tickCounter;

    @Override
    public void update(World world, float delta) {
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

            // Arrived (all modes)
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

                // Distance > 32 → skip pathfinding, use self_teleport ritual
                if (nav.mode == NavigationState.Mode.PATHFINDING
                        && hDistSq > (long) PATHFIND_MAX_RANGE * PATHFIND_MAX_RANGE) {
                    switchToRitualTeleport(nav, npcId, world);
                    continue;
                }

                npc.setAiWanderingEnabled(false);

                if (nav.mode == NavigationState.Mode.PATHFINDING) {
                    boolean ok = npc.getNavigation().moveTo(
                            nav.target.x() + 0.5, nav.target.y() + 1, nav.target.z() + 0.5, NAV_SPEED);
                    Log.info(TAG, "[NavSys] NPC {} pathfinding → ({},{},{}) hDistSq={} ok={}",
                            npcId, nav.target.x(), nav.target.y(), nav.target.z(), (int) hDistSq, ok);
                    if (!ok) {
                        Log.info(TAG, "[NavSys] NPC {} — moveTo failed immediately, switching to teleport", npcId);
                        switchToRitualTeleport(nav, npcId, world);
                    }
                    continue;
                }
                // TELEPORT_WAITING / TELEPORT_RITUAL: fall through
            }

            switch (nav.mode) {
                case PATHFINDING -> tickPathfinding(nav, npc, npcId, world);
                case TELEPORT_WAITING -> tickTeleportWaiting(nav, npc, npcId, world);
                case TELEPORT_RITUAL -> { /* ritual in private queue; arrival checked at top */ }
            }
        }
    }

    // ---- PATHFINDING ----

    private void tickPathfinding(NavigationState nav, WandscapeNpc npc, long npcId, World world) {
        int elapsed = tickCounter - nav.startTick;

        if (npc.getNavigation().isDone()) {
            if (nav.repathCount < MAX_REPATH) {
                nav.repathCount++;
                boolean ok = npc.getNavigation().moveTo(
                        nav.target.x() + 0.5, nav.target.y() + 1, nav.target.z() + 0.5, NAV_SPEED);
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

        if (elapsed > PATHFIND_TIMEOUT) {
            Log.info(TAG, "[NavSys] NPC {} — timeout {} ticks, switching to teleport", npcId, elapsed);
            switchToRitualTeleport(nav, npcId, world);
            return;
        }

        // Stuck check
        if (tickCounter - nav.lastCheckTick >= STUCK_CHECK_INTERVAL) {
            double progress = Math.abs(npc.getX() - nav.lastCheckX)
                    + Math.abs(npc.getZ() - nav.lastCheckZ);
            if (progress < STUCK_MIN_PROGRESS) {
                nav.stuckChecks++;
                Log.info(TAG, "[NavSys] NPC {} — stuck check #{}, progress={}",
                        npcId, nav.stuckChecks, String.format("%.2f", progress));
                if (nav.stuckChecks >= MAX_STUCK_CHECKS) {
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

    // ---- TELEPORT WAITING (mana-gated, for non-zero-cost rituals) ----

    private void tickTeleportWaiting(NavigationState nav, WandscapeNpc npc, long npcId, World world) {
        ManaPool mana = world.get(npcId, ManaPool.class);
        if (mana == null || mana.current() <= 0) return; // wait for mana regen
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
     */
    private void switchToRitualTeleport(NavigationState nav, long npcId, World world) {
        TaskExecutor exec = world.get(npcId, TaskExecutor.class);
        GridPos target = nav.target;

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

    private static long worldTick(World world) {
        return java.lang.System.currentTimeMillis() / 50;
    }

    private void arrive(NavigationState nav, WandscapeNpc npc) {
        npc.setAiWanderingEnabled(true);
        if (nav.future != null && !nav.future.isDone()) {
            nav.future.complete(null);
        }
        nav.reset();
    }
}
