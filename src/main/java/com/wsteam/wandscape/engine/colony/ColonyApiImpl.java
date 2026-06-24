package com.wsteam.wandscape.engine.colony;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.data.BuildingData;

import net.minecraft.core.BlockPos;

public final class ColonyApiImpl implements ColonyApi {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_COLONY_RANGE = 256;
    private static volatile ColonyApiImpl instance;

    private final Map<BlockPos, UUID> townHalls = new ConcurrentHashMap<>();
    private final Map<UUID, BlockPos> colonyToHall = new ConcurrentHashMap<>();

    private ColonyApiImpl() {}

    // ── Query ───────────────────────────────────────────────────────────────

    @Override
    public UUID createColony(BlockPos townHallPos) {
        UUID existing = townHalls.get(townHallPos);
        if (existing != null) return existing;

        UUID colonyId = UUID.randomUUID();
        townHalls.put(townHallPos, colonyId);
        colonyToHall.put(colonyId, townHallPos);
        LOGGER.info("[Colony] Created colony {} at town_hall {}",
                colonyId.toString().substring(0, 8), townHallPos);
        return colonyId;
    }

    @Override
    @Nullable
    public UUID getColonyId(BlockPos pos) {
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (BlockPos hall : townHalls.keySet()) {
            double d = Math.sqrt(pos.distSqr(hall));
            if (d < nearestDist && d <= MAX_COLONY_RANGE) {
                nearestDist = d;
                nearest = hall;
            }
        }
        return nearest != null ? townHalls.get(nearest) : null;
    }

    @Override
    public boolean isColonyBlock(BlockPos pos) {
        return townHalls.containsKey(pos);
    }

    @Override
    public void deleteColony(UUID colonyId) {
        BlockPos hall = colonyToHall.remove(colonyId);
        if (hall != null) {
            townHalls.remove(hall);
            LOGGER.info("[Colony] Deleted colony {} (was at town_hall {})",
                    colonyId.toString().substring(0, 8), hall);
            BuildingSavedData sd = getSavedData();
            if (sd != null) {
                boolean dirty = false;
                for (BuildingData bd : sd.getAllBuildings()) {
                    if (colonyId.equals(bd.getColonyId())) {
                        setColonyId(bd.getPosition(), null);
                        dirty = true;
                    }
                }
                if (dirty) sd.setDirty();
            }
        }
    }

    // ── Event hooks ─────────────────────────────────────────────────────────

    @Override
    @Nullable
    public UUID onBuildingIntact(BuildingData data) {
        if ("town_hall".equals(data.getBuildingTypeId())) {
            return createColony(data.getPosition());
        }
        UUID colonyId = getColonyId(data.getPosition());
        if (colonyId != null) {
            setColonyId(data.getPosition(), colonyId);
            LOGGER.info("[Colony] Assigned {} at {} to colony {}",
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
            setColonyId(data.getPosition(), colonyId);
        }
    }

    @Override
    public void rebuildFromSavedData() {
        townHalls.clear();
        colonyToHall.clear();
        BuildingSavedData sd = getSavedData();
        if (sd == null) return;
        for (BuildingData bd : sd.getAllBuildings()) {
            if ("town_hall".equals(bd.getBuildingTypeId())
                    && bd.isStructureIntact()
                    && bd.getColonyId() != null) {
                townHalls.put(bd.getPosition(), bd.getColonyId());
                colonyToHall.put(bd.getColonyId(), bd.getPosition());
            }
        }
        LOGGER.info("[Colony] Rebuilt index: {} colonies from saved data", townHalls.size());
    }

    // ── Singleton ───────────────────────────────────────────────────────────

    public static ColonyApiImpl get() {
        if (instance == null) {
            synchronized (ColonyApiImpl.class) {
                if (instance == null) instance = new ColonyApiImpl();
            }
        }
        return instance;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    @Nullable
    private static BuildingSavedData getSavedData() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return BuildingSavedData.get(server.overworld());
    }

    private void setColonyId(BlockPos pos, @Nullable UUID colonyId) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return;
        BuildingData bd = sd.getBuildingAt(pos);
        if (bd instanceof com.wsteam.wandscape.building.internal.BuildingState bs) {
            bs.setColonyId(colonyId);
            sd.setDirty();
        }
    }
}
