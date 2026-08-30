package com.wsteam.wandscape.engine.service;

import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.shared.event.BuildingRemovedEvent;
import com.wsteam.wandscape.shared.log.Log;
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
 * each other. The lease registry is persisted ({@link ChunkLeaseData}) because
 * vanilla {@code ForcedChunksSavedData} survives crashes — the registry lets us
 * release stale force-loads on the next server start.
 */
public final class ChunkLoadManager {

    private static final String TAG = "ChunkLoadManager";
    private static final ChunkLoadManager INSTANCE = new ChunkLoadManager();

    @Nullable
    private ServerLevel level;
    @Nullable
    private ChunkLeaseData leaseData;

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
     * Called on server start. Releases every stale lease recorded in the
     * previous session (clearing any force-loads left behind by a crash);
     * the normal poll flow re-acquires leases within a few ticks.
     */
    public void init(ServerLevel serverLevel) {
        this.level = serverLevel;
        this.leaseData = ChunkLeaseData.getOrCreate(serverLevel);
        int released = 0;
        for (var entry : leaseData.getLeases().entrySet()) {
            for (ChunkPos cp : entry.getValue()) {
                if (setForced(cp, false)) released++;
            }
        }
        leaseData.clearAll();
        refs.clear();
        leases.clear();
        Log.info(TAG, "ChunkLoadManager initialized — released {} stale forced chunks", released);
    }

    /** Called on server stop. Drops all state (registry stays on disk until next init). */
    public void reset() {
        level = null;
        leaseData = null;
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
        if (leaseData != null) {
            leaseData.addLease(buildingId, chunks);
        }
        Log.info(TAG, "leaseBuilding {} — {} chunks force-loaded", id8(buildingId), chunks.size());
        return true;
    }

    /** Release every chunk under the building's footprint (refcounted). */
    public void releaseBuilding(UUID buildingId) {
        Set<ChunkPos> chunks = leases.remove(buildingId);
        if (chunks == null) return;
        for (ChunkPos cp : chunks) {
            release(cp);
        }
        if (leaseData != null) {
            leaseData.removeLease(buildingId);
        }
        Log.info(TAG, "releaseBuilding {} — released {} chunks", id8(buildingId), chunks.size());
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
