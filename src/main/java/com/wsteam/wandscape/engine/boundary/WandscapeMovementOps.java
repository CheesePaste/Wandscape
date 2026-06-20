package com.wsteam.wandscape.engine.boundary;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.boundary.MovementOps;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;

/**
 * MC implementation of {@link MovementOps} using {@link net.minecraft.world.entity.PathfinderMob}
 * pathfinding with timeout + teleport fallback.
 *
 * <p>Navigation model:
 * <ol>
 *   <li>{@link #navigateTo} starts a pathfind via {@code getNavigation().moveTo()}
 *       and returns a future (via {@link World#startAsyncOp}).</li>
 *   <li>{@link #tickAll} checks each active nav every MC tick:
 *       <ul><li>Path done → resolve future</li>
 *           <li>NPC already in range → resolve immediately</li>
 *           <li>Timeout (200 ticks / 10s) → teleport NPC → resolve</li></ul></li>
 *   <li>{@link #cancelNavigation} stops the path and resolves early.</li>
 * </ol>
 */
public class WandscapeMovementOps implements MovementOps {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int NAV_TIMEOUT_TICKS = 200; // 10 seconds
    private static final double NAV_SPEED = 1.0;
    static final double STOP_RANGE_SQ = 6.25; // 2.5^2 — horizontal only

    /** Active navigation futures, keyed by ECS NPC entity ID. */
    private final Map<Long, PendingNav> activeNavs = new ConcurrentHashMap<>();
    private int tickCounter = 0;

    record PendingNav(CompletableFuture<Void> future, WandscapeNpc npc,
                       int targetX, int targetY, int targetZ, int startTick) {}

    // ================================================================
    // MovementOps
    // ================================================================

    @Override
    public CompletableFuture<Void> navigateTo(long npcId, int x, int y, int z) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        if (npc == null) {
            LOGGER.warn("navigateTo: unknown NPC {}", npcId);
            return CompletableFuture.completedFuture(null);
        }

        // Already in range? Skip navigation entirely.
        double dx = npc.getX() - (x + 0.5);
        double dz = npc.getZ() - (z + 0.5);
        if (dx * dx + dz * dz <= STOP_RANGE_SQ) {
            return CompletableFuture.completedFuture(null);
        }

        World world = WandscapeEngine.getWorld();
        if (world == null) return CompletableFuture.completedFuture(null);

        CompletableFuture<Void> future = world.startAsyncOp(
                "move_" + npcId + "_to_" + x + "," + y + "," + z);

        // Cancel any previous navigation for this NPC
        cancelNavigation(npcId);

        npc.getNavigation().moveTo(x + 0.5, y, z + 0.5, NAV_SPEED);
        activeNavs.put(npcId, new PendingNav(future, npc, x, y, z, tickCounter));

        LOGGER.debug("nav start: npc {} → ({},{},{})", npcId, x, y, z);
        return future;
    }

    @Override
    public void cancelNavigation(long npcId) {
        PendingNav pn = activeNavs.remove(npcId);
        if (pn != null) {
            pn.npc.getNavigation().stop();
            if (!pn.future.isDone()) {
                pn.future.complete(null);
            }
            LOGGER.debug("nav cancelled: npc {}", npcId);
        }
    }

    /**
     * Checks if an NPC is within operating range of a target (horizontal only).
     * Called from {@code TaskExecutionSystem} before deciding to navigate.
     *
     * @return true if horizontal distance ≤ 2.5 blocks
     */
    public static boolean isInRange(WandscapeNpc npc, int targetX, int targetZ) {
        double dx = npc.getX() - (targetX + 0.5);
        double dz = npc.getZ() - (targetZ + 0.5);
        return dx * dx + dz * dz <= STOP_RANGE_SQ;
    }

    // ================================================================
    // Per-tick — called from Wandscape.onServerTick
    // ================================================================

    /**
     * Tick all active navigations.
     * Must be called every MC tick from the server tick handler.
     *
     * @param currentTick monotonically increasing tick counter
     */
    public void tickAll(int currentTick) {
        tickCounter = currentTick;
        if (activeNavs.isEmpty()) return;

        List<Long> completed = new ArrayList<>();

        for (var entry : activeNavs.entrySet()) {
            long npcId = entry.getKey();
            PendingNav pn = entry.getValue();

            // NPC despawned / killed
            if (pn.npc.isRemoved()) {
                pn.future.complete(null);
                completed.add(npcId);
                continue;
            }

            double dx = pn.npc.getX() - (pn.targetX + 0.5);
            double dz = pn.npc.getZ() - (pn.targetZ + 0.5);
            boolean inRange = dx * dx + dz * dz <= STOP_RANGE_SQ;

            // Path finished or NPC already arrived
            if (pn.npc.getNavigation().isDone() || inRange) {
                pn.npc.getNavigation().stop();
                pn.future.complete(null);
                completed.add(npcId);
                LOGGER.debug("nav done: npc {} reached ({},{},{})",
                        npcId, pn.targetX, pn.targetY, pn.targetZ);
                continue;
            }

            // Timeout → teleport fallback
            if (currentTick - pn.startTick > NAV_TIMEOUT_TICKS) {
                pn.npc.getNavigation().stop();
                pn.npc.setPos(pn.targetX + 0.5, pn.targetY, pn.targetZ + 0.5);
                pn.future.complete(null);
                completed.add(npcId);
                LOGGER.info("nav timeout: npc {} teleported to ({},{},{})",
                        npcId, pn.targetX, pn.targetY, pn.targetZ);
            }
        }

        for (long npcId : completed) {
            activeNavs.remove(npcId);
        }
    }

    /** For diagnostics. */
    public int activeNavCount() {
        return activeNavs.size();
    }
}
