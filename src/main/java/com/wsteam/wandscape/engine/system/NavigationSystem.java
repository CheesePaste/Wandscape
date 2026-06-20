package com.wsteam.wandscape.engine.system;

import java.util.List;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.component.ManaPool;
import com.wsteam.wandscape.core.component.NavigationState;
import com.wsteam.wandscape.core.component.Position;
import com.wsteam.wandscape.core.ecs.System;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;

import net.minecraft.core.particles.ParticleTypes;

/**
 * Single driver of all NPC movement.
 *
 * <p>Other systems request movement by writing {@link NavigationState}
 * (mode + target + future). This system picks it up on the next ECS tick
 * and drives the actual MC movement — pathfinding, ritual teleport, or
 * mana-gated waiting.
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
    static final int SELF_TELEPORT_MANA_COST = 20;

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

            // Arrived
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

                // If too far for pathfinding, switch to teleport
                if (nav.mode == NavigationState.Mode.PATHFINDING
                        && hDistSq > (long) PATHFIND_MAX_RANGE * PATHFIND_MAX_RANGE) {
                    nav.mode = NavigationState.Mode.TELEPORT_WAITING;
                }

                npc.setAiWanderingEnabled(false);

                if (nav.mode == NavigationState.Mode.PATHFINDING) {
                    boolean ok = npc.getNavigation().moveTo(
                            nav.target.x() + 0.5, nav.target.y() + 1, nav.target.z() + 0.5, NAV_SPEED);
                    LOGGER.info("[NavSys] NPC {} pathfinding → ({},{},{}) hDistSq={} ok={}",
                            npcId, nav.target.x(), nav.target.y(), nav.target.z(), (int) hDistSq, ok);
                    if (!ok) {
                        LOGGER.info("[NavSys] NPC {} — moveTo failed immediately, switching to teleport", npcId);
                        switchToTeleport(nav, npc, npcId, world);
                    }
                    continue;
                }
                // TELEPORT_WAITING: fall through to try-teleport immediately
            }

            switch (nav.mode) {
                case PATHFINDING -> tickPathfinding(nav, npc, npcId, world);
                case TELEPORT_WAITING -> tickTeleportWaiting(nav, npc, npcId, world);
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
                    switchToTeleport(nav, npc, npcId, world);
                }
            } else {
                LOGGER.info("[NavSys] NPC {} — re-paths exhausted, switching to teleport", npcId);
                switchToTeleport(nav, npc, npcId, world);
            }
            return;
        }

        if (elapsed > PATHFIND_TIMEOUT) {
            LOGGER.info("[NavSys] NPC {} — timeout {} ticks, switching to teleport", npcId, elapsed);
            switchToTeleport(nav, npc, npcId, world);
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
                    switchToTeleport(nav, npc, npcId, world);
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

    // ---- TELEPORT WAITING ----

    private void tickTeleportWaiting(NavigationState nav, WandscapeNpc npc, long npcId, World world) {
        ManaPool mana = world.get(npcId, ManaPool.class);
        if (mana != null && mana.current() >= SELF_TELEPORT_MANA_COST) {
            if (tryTeleport(nav, npc, npcId, world)) {
                arrive(nav, npc);
            }
        }
    }

    // ---- Internal ----

    private void switchToTeleport(NavigationState nav, WandscapeNpc npc, long npcId, World world) {
        if (tryTeleport(nav, npc, npcId, world)) {
            arrive(nav, npc);
        } else {
            nav.mode = NavigationState.Mode.TELEPORT_WAITING;
            nav.stuckChecks = 0;
            nav.repathCount = 0;
        }
    }

    private boolean tryTeleport(NavigationState nav, WandscapeNpc npc, long npcId, World world) {
        ManaPool mana = world.get(npcId, ManaPool.class);
        if (mana != null && !mana.consume(SELF_TELEPORT_MANA_COST)) {
            return false;
        }
        npc.teleportTo(nav.target.x() + 0.5, nav.target.y(), nav.target.z() + 0.5);
        spawnTeleportParticles(npc);
        LOGGER.info("[NavSys] NPC {} teleported → ({},{},{})",
                npcId, nav.target.x(), nav.target.y(), nav.target.z());
        return true;
    }

    private void arrive(NavigationState nav, WandscapeNpc npc) {
        npc.setAiWanderingEnabled(true);
        if (nav.future != null && !nav.future.isDone()) {
            nav.future.complete(null);
        }
        nav.reset();
    }

    private static void spawnTeleportParticles(WandscapeNpc npc) {
        for (int i = 0; i < 20; i++) {
            double ox = (npc.getRandom().nextDouble() - 0.5) * 1.5;
            double oy = npc.getRandom().nextDouble() * 2.0;
            double oz = (npc.getRandom().nextDouble() - 0.5) * 1.5;
            npc.level().addParticle(ParticleTypes.PORTAL,
                    npc.getX() + ox, npc.getY() + oy, npc.getZ() + oz,
                    0, 0, 0);
        }
    }
}
