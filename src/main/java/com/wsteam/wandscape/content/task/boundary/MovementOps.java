package com.wsteam.wandscape.content.task.boundary;
import com.wsteam.wandscape.content.task.component.Position;

import java.util.concurrent.CompletableFuture;
/**
 * Core-layer boundary for NPC movement and navigation.
 * Implemented by the Minecraft adapter layer using PathfinderMob navigation.
 *
 * <p>Navigation contract:
 * <ul>
 *   <li>{@link #navigateTo} returns a CompletableFuture that ALWAYS resolves
 *       (never exceptionally). Timeout triggers teleport fallback.</li>
 *   <li>{@link #cancelNavigation} stops any in-flight navigation for the NPC.</li>
 * </ul>
 */
public interface MovementOps {

    /**
     * Navigate an NPC to the target block position.
     *
     * <p>Never throws or completes exceptionally. If pathfinding fails or
     * times out (10s), the NPC is teleported directly to the target and
     * the future resolves normally.
     *
     * @param npcId ECS entity ID of the NPC
     * @param x     target block X
     * @param y     target block Y
     * @param z     target block Z
     * @return a future that resolves when the NPC is at the target
     */
    CompletableFuture<Void> navigateTo(long npcId, int x, int y, int z);

    /**
     * Cancel any active navigation for this NPC.
     * Resolves the pending navigation future immediately.
     */
    void cancelNavigation(long npcId);
}
