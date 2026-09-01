package com.wsteam.wandscape.content.task.boundary;
import com.wsteam.wandscape.content.npc.system.NavigationSystem;

import com.wsteam.wandscape.content.task.boundary.MovementOps;
import com.wsteam.wandscape.content.task.component.NavigationState;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.types.GridPos;
import com.wsteam.wandscape.impl.WandscapeEngine;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;

import java.util.concurrent.CompletableFuture;

/**
 * Stateless MC adapter for {@link MovementOps}.
 *
 * <p>Writes {@link NavigationState} (mode + target + future) and lets
 * {@code NavigationSystem} do all the actual driving. No tickAll, no
 * internal state maps.
 */
public class WandscapeMovementOps implements MovementOps {

    private static final String TAG = "WandscapeMovementOps";

    @Override
    public CompletableFuture<Void> navigateTo(long npcId, int x, int y, int z) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        if (npc == null || npc.isRemoved()) {
            Log.warn(TAG, "[MovementOps] navigateTo: unknown or removed NPC {}", npcId);
            return CompletableFuture.completedFuture(null);
        }

        World world = WandscapeEngine.getWorld();
        if (world == null) {
            return CompletableFuture.completedFuture(null);
        }

        // Cancel any existing nav for this NPC
        cancelNavigation(npcId);

        double dx = npc.getX() - (x + 0.5);
        double dz = npc.getZ() - (z + 0.5);
        double hDistSq = dx * dx + dz * dz;

        Log.debug(LogCategory.TASK, "move", "navigateTo npc={} → ({},{},{}) hDist={}",
                npcId, x, y, z, (int) Math.sqrt(hDistSq));

        // Write NavigationState — NavigationSystem picks this up (always, even when in-range)
        NavigationState nav = world.get(npcId, NavigationState.class);
        if (nav == null) {
            nav = new NavigationState();
            world.addComponent(npcId, nav);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        nav.mode = NavigationState.Mode.PATHFINDING;
        nav.target = new GridPos(x, y, z);
        nav.future = future;

        Log.debug(LogCategory.TASK, "move", "nav queued for NPC {} — NavigationSystem will drive it", npcId);
        return future;
    }

    @Override
    public void cancelNavigation(long npcId) {
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(npcId);
        World world = WandscapeEngine.getWorld();
        if (world != null) {
            NavigationState nav = world.get(npcId, NavigationState.class);
            if (nav != null) nav.reset();
        }
        if (npc != null && !npc.isRemoved()) {
            npc.setAiWanderingEnabled(true);
        }
    }
}
