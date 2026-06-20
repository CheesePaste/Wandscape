package com.wsteam.wandscape.engine.boundary;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.core.boundary.MovementOps;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;

/**
 * MC implementation of {@link MovementOps} — direct teleport, no pathfinding.
 *
 * <p>Pathfinding on a dynamic construction site where blocks are constantly
 * being placed/removed is fundamentally unreliable: paths invalidate mid-walk,
 * NPCs get walled in, stuck-detection heuristics break. Teleport is instant,
 * deterministic, and the player sees the NPC appear at the work site — which
 * is perfectly acceptable for a colony automation mod.
 */
public class WandscapeMovementOps implements MovementOps {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final double STOP_RANGE_SQ = 25.0; // 5² — horizontal only

    @Override
    public CompletableFuture<Void> navigateTo(long npcId, int x, int y, int z) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        if (npc == null) {
            LOGGER.warn("navigateTo: unknown NPC {}", npcId);
            return CompletableFuture.completedFuture(null);
        }

        double dx = npc.getX() - (x + 0.5);
        double dz = npc.getZ() - (z + 0.5);
        if (dx * dx + dz * dz <= STOP_RANGE_SQ) {
            return CompletableFuture.completedFuture(null);
        }

        npc.setPos(x + 0.5, y, z + 0.5);
        LOGGER.debug("nav teleport: npc {} → ({},{},{})", npcId, x, y, z);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void cancelNavigation(long npcId) {
        // No-op — teleport has no in-flight state
    }

    /** @return true if horizontal distance ≤ 5 blocks */
    public static boolean isInRange(WandscapeNpc npc, int targetX, int targetZ) {
        double dx = npc.getX() - (targetX + 0.5);
        double dz = npc.getZ() - (targetZ + 0.5);
        return dx * dx + dz * dz <= STOP_RANGE_SQ;
    }
}
