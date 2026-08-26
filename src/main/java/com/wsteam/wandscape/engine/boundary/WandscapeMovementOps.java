package com.wsteam.wandscape.engine.boundary;

import java.util.concurrent.CompletableFuture;

import com.wsteam.wandscape.core.boundary.MovementOps;
import com.wsteam.wandscape.core.component.NavigationState;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.nav.LevelTerrainView;
import com.wsteam.wandscape.engine.nav.StandableTerrain;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.server.level.ServerLevel;

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

        // ── 目标 Y 吸附到真实可站立表面 ──
        // 任务站位（computeTaskStance）从包围盒推导，地形任务（铲平/填方）的站位 Y 会落在周围
        // 山体内部；逐 op 目标也可能指向实体方块。仅当原 Y 不可站立时才吸附，守卫 findEngagePos
        // 等「已刻意选好可行 Y」的调用不受影响。结果采用 feet-Y（见 StandableTerrain）。
        if (npc.level() instanceof ServerLevel serverLevel) {
            Integer standY = StandableTerrain.nearestStandableY(
                    new LevelTerrainView(serverLevel), x, y, z);
            if (standY != null && standY != y) {
                Log.info(TAG, "[MovementOps] navigateTo npc={} snap Y {} → {} @({},{})",
                        npcId, y, standY, x, z);
                y = standY;
            }
        }

        // Cancel any existing nav for this NPC
        cancelNavigation(npcId);

        double dx = npc.getX() - (x + 0.5);
        double dz = npc.getZ() - (z + 0.5);
        double hDistSq = dx * dx + dz * dz;

        Log.info(TAG, "[MovementOps] navigateTo npc={} → ({},{},{}) hDist={}",
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

        Log.info(TAG, "[MovementOps] nav queued for NPC {} — NavigationSystem will drive it", npcId);
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
