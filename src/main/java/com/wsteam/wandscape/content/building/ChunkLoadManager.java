package com.wsteam.wandscape.content.building;

import com.wsteam.wandscape.content.building.internal.BuildingSavedData;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.content.building.event.BuildingRemovedEvent;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.NeoForge;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Force-loads building footprint chunks while a building has an active
 * construction/production task, releasing them when the queue drains.
 *
 * <p>This is what lets a colony keep building while its chunks are unloaded:
 * {@code BuildingTaskSource} leases the active building's footprint before
 * dispatching its task, so {@code TransformOp}s land in real, force-loaded
 * chunks instead of silently no-oping on unloaded ones.
 *
 * <p>Chunks are refcounted so multiple buildings sharing a chunk don't unload
 * each other. The registry is purely in-memory: vanilla {@code ForcedChunksSavedData}
 * survives crashes and restarts, so on the next server start we re-derive the
 * building footprints from {@link BuildingState#getBounds()} and release whatever
 * is still force-loaded before the normal poll flow re-leases it.
 */
public final class ChunkLoadManager {

    private static final String TAG = "ChunkLoadManager";
    private static final ChunkLoadManager INSTANCE = new ChunkLoadManager();

    @Nullable
    private ServerLevel level;

    /** chunk → refcount across all leased buildings. */
    private final Map<ChunkPos, Integer> refs = new HashMap<>();
    /** buildingId → footprint chunks currently leased. */
    private final Map<UUID, Set<ChunkPos>> leases = new HashMap<>();

    private ChunkLoadManager() {
        NeoForge.EVENT_BUS.addListener(BuildingRemovedEvent.class, this::onBuildingRemoved);
    }

    public static ChunkLoadManager get() {
        return INSTANCE;
    }

    /** Release the footprint lease when a building is demolished/unregistered. */
    private void onBuildingRemoved(BuildingRemovedEvent event) {
        if (leases.containsKey(event.getBuildingId())) {
            releaseBuilding(event.getBuildingId());
        }
    }

    // ---- Lifecycle ----

    /**
     * Called on server start. 释放上一个会话残留的建筑占地强加载（崩溃或停服时租约来不及释放），
     * 随后正常 poll 流程会在几 tick 内按需重新租用。
     */
    public void init(ServerLevel serverLevel) {
        this.level = serverLevel;
        refs.clear();
        leases.clear();
        int released;
        try {
            released = releaseStaleForceLoads();
        } catch (RuntimeException e) {
            // 清残留强加载只是尽力而为，任何异常都不该拖垮服务器启动。
            released = 0;
            Log.warn(TAG, "stale forced-chunk sweep failed: {}", e.toString());
        }
        Log.info(TAG, "ChunkLoadManager initialized — released {} stale forced chunks", released);
    }

    /**
     * 按建筑 bounds 现算上一个会话遗留的强加载区块并释放。旧实现为此单独落盘了一张租约表
     * （world/data/wandscape_chunk_leases.dat），但那张表 100% 可由
     * {@code BuildingState.getBounds().intersectingChunks()} 推导，故销毁冗余落盘、改为启动时现算。
     *
     * <p>尽力而为：读不到建筑数据就记 warn 返回，不阻断服务器启动。
     */
    private int releaseStaleForceLoads() {
        ServerLevel lvl = level;
        if (lvl == null) return 0;
        BuildingSavedData sd = BuildingSavedData.get(lvl);
        if (sd == null) {
            Log.warn(TAG, "stale forced-chunk sweep skipped — building data unavailable");
            return 0;
        }
        int released = 0;
        for (BuildingState state : sd.getAllBuildings()) {
            if (state == null || state.getBounds() == null) continue;
            for (ChunkPos cp : state.getBounds().intersectingChunks().toList()) {
                if (setForced(cp, false)) released++;
            }
        }
        return released;
    }

    /** Called on server stop. Drops all in-memory state (残留强加载由下次 init 清理). */
    public void reset() {
        level = null;
        refs.clear();
        leases.clear();
    }

    // ---- Building-level lease ----

    /**
     * Force-load every chunk under the building's footprint. Returns false if
     * the building no longer exists or has no footprint.
     */
    public boolean leaseBuilding(UUID buildingId) {
        ServerLevel lvl = level;
        if (lvl == null) return false;

        Set<ChunkPos> chunks = footprintChunks(buildingId);
        if (chunks == null || chunks.isEmpty()) {
            Log.warn(TAG, "leaseBuilding {} — no footprint, skipping", id8(buildingId));
            return false;
        }

        for (ChunkPos cp : chunks) {
            acquire(cp);
        }
        leases.put(buildingId, chunks);
        return true;
    }

    /** Release every chunk under the building's footprint (refcounted). */
    public void releaseBuilding(UUID buildingId) {
        Set<ChunkPos> chunks = leases.remove(buildingId);
        if (chunks == null) return;
        for (ChunkPos cp : chunks) {
            release(cp);
        }
    }

    public boolean isLeased(UUID buildingId) {
        return leases.containsKey(buildingId);
    }

    // ---- Temporary per-op lease (general safety net) ----

    /**
     * Force-load a single chunk for the duration of one block-op write. Refcounted,
     * so it is a no-op (no {@code setChunkForced} call) when a building lease already
     * holds the chunk — covers manual blueprints / road tasks that skip the building
     * lease path without adding cost to the main construction path.
     */
    public void acquireChunk(ChunkPos cp) {
        acquire(cp);
    }

    /** Release a {@link #acquireChunk(ChunkPos)} lease. */
    public void releaseChunk(ChunkPos cp) {
        release(cp);
    }

    // ---- Chunk-level refcount ----

    private void acquire(ChunkPos cp) {
        int n = refs.getOrDefault(cp, 0) + 1;
        refs.put(cp, n);
        if (n == 1) {
            setForced(cp, true);
        }
    }

    private void release(ChunkPos cp) {
        Integer n = refs.get(cp);
        if (n == null || n <= 0) return;
        if (n == 1) {
            refs.remove(cp);
            setForced(cp, false);
        } else {
            refs.put(cp, n - 1);
        }
    }

    private boolean setForced(ChunkPos cp, boolean add) {
        ServerLevel lvl = level;
        if (lvl == null) return false;
        return lvl.setChunkForced(cp.x, cp.z, add);
    }

    // ---- Helpers ----

    /** World-space footprint chunks of a building, or null when unknown. */
    @Nullable
    private Set<ChunkPos> footprintChunks(UUID buildingId) {
        ServerLevel lvl = level;
        if (lvl == null) return null;
        BuildingSavedData sd = BuildingSavedData.get(lvl);
        if (sd == null) return null;
        BuildingState state = sd.getBuilding(buildingId);
        if (state == null || state.getBounds() == null) return null;
        return state.getBounds().intersectingChunks().collect(Collectors.toSet());
    }

    private static String id8(UUID id) {
        return id.toString().substring(0, 8);
    }
}
