package com.wsteam.wandscape.engine;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.data.BuildingData;

import com.wsteam.wandscape.shared.data.NarrativeEventType;
import net.minecraft.core.BlockPos;
import com.wsteam.wandscape.shared.log.Log;

public final class ColonyApiImpl implements ColonyApi {

    private static final String TAG = "ColonyApiImpl";
    private static final int MAX_COLONY_RANGE = 256;
    private static volatile ColonyApiImpl instance;

    /** Colony origin → colony UUID (spatial index). */
    private final Map<BlockPos, UUID> colonyOrigins = new ConcurrentHashMap<>();

    /** Colony UUID → origin position (reverse lookup). */
    private final Map<UUID, BlockPos> colonyToOrigin = new ConcurrentHashMap<>();

    private ColonyApiImpl() {}

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    public UUID createColony(BlockPos origin) {
        UUID existing = colonyOrigins.get(origin);
        if (existing != null) return existing;

        UUID colonyId = UUID.randomUUID();
        colonyOrigins.put(origin, colonyId);
        colonyToOrigin.put(colonyId, origin);
        Log.info(TAG, "[Colony] Created colony {} at origin {}",
                colonyId.toString().substring(0, 8), origin);
        return colonyId;
    }

    @Override
    public void deleteColony(UUID colonyId) {
        BlockPos origin = colonyToOrigin.remove(colonyId);
        if (origin != null) {
            colonyOrigins.remove(origin);
            Log.info(TAG, "[Colony] Deleted colony {} (origin {})",
                    colonyId.toString().substring(0, 8), origin);
            BuildingSavedData sd = getSavedData();
            if (sd != null) {
                for (BuildingData bd : sd.getAllBuildings()) {
                    if (colonyId.equals(bd.getColonyId())) {
                        setColonyId(bd, null);
                    }
                }
            }
        }
    }

    // ── Spatial lookup ────────────────────────────────────────────────────

    @Override
    @Nullable
    public UUID getColonyId(BlockPos pos) {
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (BlockPos origin : colonyOrigins.keySet()) {
            double d = Math.sqrt(pos.distSqr(origin));
            if (d < nearestDist && d <= MAX_COLONY_RANGE) {
                nearestDist = d;
                nearest = origin;
            }
        }
        return nearest != null ? colonyOrigins.get(nearest) : null;
    }

    @Override
    public boolean isColonyOrigin(BlockPos pos) {
        return colonyOrigins.containsKey(pos);
    }

    // ── Event hooks ───────────────────────────────────────────────────────

    @Override
    @Nullable
    public UUID onBuildingIntact(BuildingData data) {
        if ("town_hall".equals(data.getBuildingTypeId())) {
            // NEVER auto-create colonies. Only /wandscape colony create does that.
            // If a town_hall is built outside any existing colony range,
            // it's an orphan — refuse to assign it.
            UUID existing = getColonyId(data.getPosition());
            if (existing != null) {
                colonyOrigins.put(data.getPosition(), existing);
                colonyToOrigin.put(existing, data.getPosition());
                setColonyId(data, existing);
                Log.info(TAG, "[Colony] Town hall at {} linked to colony {}",
                        data.getPosition(), existing.toString().substring(0, 8));
                return existing;
            }
            Log.error(TAG, "[Colony] Town hall built at {} but NO colony nearby! "
                    + "Use '/wandscape colony create <name>' first, then build within 256 blocks.",
                    data.getPosition());
            return null;
        }
        UUID colonyId = getColonyId(data.getPosition());
        if (colonyId != null) {
            setColonyId(data, colonyId);
            Log.info(TAG, "[Colony] Assigned {} at {} to colony {}",
                    data.getBuildingTypeId(), data.getPosition(),
                    colonyId.toString().substring(0, 8));
        }
        return colonyId;
    }

    @Override
    public void onBuildingDestroyed(BuildingData data) {
        if ("town_hall".equals(data.getBuildingTypeId())
                && data.getColonyId() != null) {
            deleteColony(data.getColonyId());
        }
    }

    @Override
    public void assignColonyIfPossible(BuildingData data) {
        if (data.getColonyId() != null) return;
        UUID colonyId = getColonyId(data.getPosition());
        if (colonyId != null) {
            setColonyId(data, colonyId);
        }
    }

    @Override
    public void rebuildFromSavedData() {
        colonyOrigins.clear();
        colonyToOrigin.clear();
        BuildingSavedData sd = getSavedData();
        if (sd == null) return;
        for (BuildingData bd : sd.getAllBuildings()) {
            if ("town_hall".equals(bd.getBuildingTypeId())
                    && bd.isStructureIntact()
                    && bd.getColonyId() != null) {
                colonyOrigins.put(bd.getPosition(), bd.getColonyId());
                colonyToOrigin.put(bd.getColonyId(), bd.getPosition());
            }
        }
        Log.info(TAG, "[Colony] Rebuilt index: {} colonies from saved data", colonyOrigins.size());
    }

    // ── Singleton ─────────────────────────────────────────────────────────

    public static ColonyApiImpl get() {
        if (instance == null) {
            synchronized (ColonyApiImpl.class) {
                if (instance == null) instance = new ColonyApiImpl();
            }
        }
        return instance;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    @Nullable
    private static BuildingSavedData getSavedData() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return BuildingSavedData.get(server.overworld());
    }

    /**
     * Set colonyId directly on a {@link BuildingState} reference.
     *
     * <p><b>IMPORTANT:</b> Do NOT use {@code getBuildingAt(pos)} for this — the
     * positional lookup relies on {@code posIndex} (only rebuilt from
     * BuildingConfig patterns, empty after server restart) and
     * {@code chunkIndex → bounds.isInside(anchor)} (only works when the anchor
     * lies inside the bounding box).  The caller already holds the live
     * {@link BuildingState} reference, so use it directly.
     */
    private void setColonyId(BuildingData data, @Nullable UUID colonyId) {
        if (data instanceof BuildingState bs) {
            bs.setColonyId(colonyId);
            BuildingSavedData sd = getSavedData();
            if (sd != null) sd.setDirty();
        }
    }
}
