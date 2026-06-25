package com.wsteam.wandscape.engine.system;

import java.util.List;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.component.SuspensionContext;
import com.wsteam.wandscape.core.component.ManaPool;
import com.wsteam.wandscape.core.component.NavigationState;
import com.wsteam.wandscape.core.component.Position;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.core.ecs.System;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.op.AtomicOp;
import com.wsteam.wandscape.core.task.NpcTaskPackage;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.RitualId;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;

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

    private static final Logger LOGGER = LogUtils.getLogger();

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
                    LOGGER.info("[NavSys] NPC {} pathfinding → ({},{},{}) hDistSq={} ok={}",
                            npcId, nav.target.x(), nav.target.y(), nav.target.z(), (int) hDistSq, ok);
                    if (!ok) {
                        LOGGER.info("[NavSys] NPC {} — moveTo failed immediately, switching to teleport", npcId);
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
                LOGGER.info("[NavSys] NPC {} re-path #{}, elapsed={} ok={}",
                        npcId, nav.repathCount, elapsed, ok);
                if (!ok) {
                    LOGGER.info("[NavSys] NPC {} — re-path failed, switching to teleport", npcId);
                    switchToRitualTeleport(nav, npcId, world);
                }
            } else {
                LOGGER.info("[NavSys] NPC {} — re-paths exhausted, switching to teleport", npcId);
                switchToRitualTeleport(nav, npcId, world);
            }
            return;
        }

        if (elapsed > PATHFIND_TIMEOUT) {
            LOGGER.info("[NavSys] NPC {} — timeout {} ticks, switching to teleport", npcId, elapsed);
            switchToRitualTeleport(nav, npcId, world);
            return;
        }

        // Stuck check
        if (tickCounter - nav.lastCheckTick >= STUCK_CHECK_INTERVAL) {
            double progress = Math.abs(npc.getX() - nav.lastCheckX)
                    + Math.abs(npc.getZ() - nav.lastCheckZ);
            if (progress < STUCK_MIN_PROGRESS) {
                nav.stuckChecks++;
                LOGGER.info("[NavSys] NPC {} — stuck check #{}, progress={}",
                        npcId, nav.stuckChecks, String.format("%.2f", progress));
                if (nav.stuckChecks >= MAX_STUCK_CHECKS) {
                    LOGGER.info("[NavSys] NPC {} — stuck, switching to teleport", npcId);
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

    // ---- Ritual teleport via private queue ----

    /**
     * Push a {@code RitualOp(SELF_TELEPORT)} to the NPC's private queue.
     * TaskExecutionSystem picks it up, executes via {@code RitualOps.beginRitual()},
     * and the NPC arrives at the target.
     * <p>
     * The nav future is NOT completed here. Instead it chains to the ritual
     * future, so TaskExecutionSystem blocks until the ritual actually finishes
     * and the NPC is at the destination. This prevents the re-navigation loop
     * that occurred when the future was completed before the teleport happened.
     */
    private void switchToRitualTeleport(NavigationState nav, long npcId, World world) {
        TaskExecutor exec = world.get(npcId, TaskExecutor.class);

        // Suspend the current package so the urgent teleport can run next
        if (exec != null && exec.npcQueue.currentPackage() != null) {
            SuspensionContext ctx = exec.npcQueue.suspendCurrent(worldTick(world));
            LOGGER.info("[NavSys] NPC {} — suspended current pkg for teleport (ctx={})",
                    npcId, ctx != null ? "ok" : "null");
            exec.pendingFuture = null;
            exec.pendingFutureIsNav = false;
        }
        if (exec != null && world.movementOps != null) {
            world.movementOps.cancelNavigation(npcId);
        }

        GridPos target = nav.target;
        exec.npcQueue.enqueueUrgent(NpcTaskPackage.system("system:stuck_teleport",
                new AtomicOp.RitualOp(RitualId.SELF_TELEPORT, target), null, 80));
        nav.mode = NavigationState.Mode.TELEPORT_RITUAL;
        nav.stuckChecks = 0;
        nav.repathCount = 0;

        if (target != null) {
            LOGGER.info("[NavSys] NPC {} — self_teleport ritual queued → ({},{},{})",
                    npcId, target.x(), target.y(), target.z());
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
