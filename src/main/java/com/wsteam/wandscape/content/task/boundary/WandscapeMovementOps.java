package com.wsteam.wandscape.content.task.boundary;
import com.wsteam.wandscape.content.building.ChunkLoadManager;
import com.wsteam.wandscape.content.npc.system.NavigationSystem;

import com.wsteam.wandscape.content.task.boundary.MovementOps;
import com.wsteam.wandscape.content.task.component.NavigationState;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.types.GridPos;
import com.wsteam.wandscape.content.npc.worker.ColonyWorker;
import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

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
        World world = com.wsteam.wandscape.content.task.ecs.World.getActive();
        if (world == null) {
            return CompletableFuture.completedFuture(null);
        }

        ColonyWorker worker = EntityComponentBridge.INSTANCE.getWorker(npcId);
        boolean isUnloaded = worker != null && worker.entity().isRemoved()
                && (worker.entity().getRemovalReason() == Entity.RemovalReason.UNLOADED_TO_CHUNK
                || worker.entity().getRemovalReason() == Entity.RemovalReason.UNLOADED_WITH_PLAYER);

        if (worker == null || (worker.entity().isRemoved() && !isUnloaded)) {
            Log.warn(TAG, "[MovementOps] navigateTo: unknown or permanently removed worker {}", npcId);
            return CompletableFuture.failedFuture(new IllegalStateException("Worker " + npcId + " is dead or destroyed"));
        }

        // Cancel any existing nav for this NPC
        cancelNavigation(npcId);

        // Write NavigationState — NavigationSystem picks this up
        NavigationState nav = world.get(npcId, NavigationState.class);
        if (nav == null) {
            nav = new NavigationState();
            world.addComponent(npcId, nav);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        nav.target = new GridPos(x, y, z);
        nav.future = future;

        if (isUnloaded) {
            // NPC 处于未加载区块：先临时租借其所在区块唤醒实体，随后 NavigationSystem 瞬移至现场施工
            Position pos = world.get(npcId, Position.class);
            int blockX = pos != null ? pos.pos().x() : worker.entity().getBlockX();
            int blockZ = pos != null ? pos.pos().z() : worker.entity().getBlockZ();
            int chunkX = SectionPos.blockToSectionCoord(blockX);
            int chunkZ = SectionPos.blockToSectionCoord(blockZ);

            ChunkLoadManager.get().acquireChunk(new ChunkPos(chunkX, chunkZ));
            nav.mode = NavigationState.Mode.WAKEUP;
            nav.wakeupChunkX = chunkX;
            nav.wakeupChunkZ = chunkZ;
            nav.hasWakeupChunk = true;
            nav.wakeupWaitTicks = 0;
            nav.startTick = 0;

            Log.info(TAG, "Worker {} in unloaded chunk ({}, {}) — acquired chunk lease, waiting for wakeup",
                    npcId, chunkX, chunkZ);
            return future;
        }

        double dx = worker.entity().getX() - (x + 0.5);
        double dz = worker.entity().getZ() - (z + 0.5);
        double hDistSq = dx * dx + dz * dz;

        Log.debug(LogCategory.TASK, "move", "navigateTo npc={} → ({},{},{}) hDist={}",
                npcId, x, y, z, (int) Math.sqrt(hDistSq));

        nav.mode = NavigationState.Mode.PATHFINDING;
        Log.debug(LogCategory.TASK, "move", "nav queued for NPC {} — NavigationSystem will drive it", npcId);
        return future;
    }

    @Override
    public void cancelNavigation(long npcId) {
        World world = com.wsteam.wandscape.content.task.ecs.World.getActive();
        if (world != null) {
            NavigationState nav = world.get(npcId, NavigationState.class);
            if (nav != null) {
                if (nav.hasWakeupChunk) {
                    ChunkLoadManager.get().releaseChunk(new ChunkPos(nav.wakeupChunkX, nav.wakeupChunkZ));
                    nav.hasWakeupChunk = false;
                }
                nav.reset();
            }
        }
        ColonyWorker worker = EntityComponentBridge.INSTANCE.getWorker(npcId);
        if (worker != null && !worker.entity().isRemoved()) {
            worker.setAiWanderingEnabled(true);
        }
    }
}
