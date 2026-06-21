package com.wsteam.wandscape.building.internal;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.be.AbstractWandscapeBE;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.WorkItem;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Implementation of {@link BuildingApi}.
 *
 * <p>Maintains three indexes:
 * <ul>
 *   <li>{@code buildingId → BuildingData} — primary registry</li>
 *   <li>{@code BlockPos → buildingId} — spatial lookup</li>
 *   <li>{@code colonyId → Set<buildingTypeId>} — per-colony unlocked types for three-value system</li>
 * </ul>
 */
public class BuildingApiImpl implements BuildingApi {
    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- Indexes ----
    private final Map<UUID, BuildingData> byId = new ConcurrentHashMap<>();
    private final Map<BlockPos, UUID> byPos = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> currentTasks = new ConcurrentHashMap<>(); // buildingId → taskId

    // Three-value: per colony, which building types have ever been built
    private final Map<UUID, Set<String>> colonyUnlockedTypes = new ConcurrentHashMap<>();
    // Per colony, how many active (non-shutdown) buildings of each type
    private final Map<UUID, Map<String, Integer>> colonyActiveCounts = new ConcurrentHashMap<>();

    // ---- Query ----

    @Override
    public BuildingData getBuilding(UUID buildingId) {
        return byId.get(buildingId);
    }

    @Override
    public BuildingData getBuildingAt(BlockPos pos) {
        UUID id = byPos.get(pos);
        return id != null ? byId.get(id) : null;
    }

    @Override
    public List<BuildingData> getColonyBuildings(UUID colonyId) {
        List<BuildingData> result = new ArrayList<>();
        for (BuildingData bd : byId.values()) {
            if (colonyId == null || colonyId.equals(bd.getColonyId())) {
                result.add(bd);
            }
        }
        return result;
    }

    // ---- Lifecycle ----

    @Override
    public void registerBuilding(BuildingData data) {
        byId.put(data.getBuildingId(), data);
        byPos.put(data.getPosition(), data.getBuildingId());

        UUID colonyId = data.getColonyId();
        if (colonyId != null) {
            colonyUnlockedTypes
                    .computeIfAbsent(colonyId, k -> ConcurrentHashMap.newKeySet())
                    .add(data.getBuildingTypeId());
            colonyActiveCounts
                    .computeIfAbsent(colonyId, k -> new ConcurrentHashMap<>())
                    .merge(data.getBuildingTypeId(), 1, Integer::sum);
        }
        LOGGER.debug("registered building {} type={} at {}", data.getBuildingId(), data.getBuildingTypeId(), data.getPosition());
    }

    @Override
    public void unregisterBuilding(BlockPos pos) {
        UUID id = byPos.remove(pos);
        if (id != null) {
            BuildingData data = byId.remove(id);
            if (data != null && data.getColonyId() != null) {
                Map<String, Integer> counts = colonyActiveCounts.get(data.getColonyId());
                if (counts != null) {
                    counts.merge(data.getBuildingTypeId(), -1, Integer::sum);
                    counts.remove(data.getBuildingTypeId(), 0);
                }
                // Note: we do NOT remove from colonyUnlockedTypes — types stay unlocked permanently
            }
            currentTasks.remove(id);
        }
    }

    // ---- Shutdown/Restart ----

    @Override
    public boolean shutdown(UUID buildingId) {
        BuildingData data = byId.get(buildingId);
        if (data == null) return false;

        BuildingDataImpl impl = (BuildingDataImpl) data;
        if (!impl.isShutdown()) {
            impl.setShutdown(true);
            // Decrement active count for three-value
            UUID cid = data.getColonyId();
            if (cid != null) {
                Map<String, Integer> counts = colonyActiveCounts.get(cid);
                if (counts != null) {
                    counts.merge(data.getBuildingTypeId(), -1, Integer::sum);
                }
            }
            // Persist on BE
            AbstractWandscapeBE be = getBeAt(data.getPosition());
            if (be != null) be.setShutdown(true);
        }
        return true;
    }

    @Override
    public boolean restart(UUID buildingId) {
        BuildingData data = byId.get(buildingId);
        if (data == null) return false;

        BuildingDataImpl impl = (BuildingDataImpl) data;
        if (impl.isShutdown()) {
            impl.setShutdown(false);
            UUID cid = data.getColonyId();
            if (cid != null) {
                colonyActiveCounts
                        .computeIfAbsent(cid, k -> new ConcurrentHashMap<>())
                        .merge(data.getBuildingTypeId(), 1, Integer::sum);
            }
            AbstractWandscapeBE be = getBeAt(data.getPosition());
            if (be != null) be.setShutdown(false);
        }
        return true;
    }

    // ---- Colony stats (three-value system) ----

    @Override
    public int getColonyComfort(UUID colonyId) {
        return computeColonyStat(colonyId, BuildingConfig::comfort);
    }

    @Override
    public int getColonyMagic(UUID colonyId) {
        return computeColonyStat(colonyId, BuildingConfig::magic);
    }

    @Override
    public int getColonyWonder(UUID colonyId) {
        return computeColonyStat(colonyId, BuildingConfig::wonder);
    }

    private int computeColonyStat(UUID colonyId, java.util.function.ToIntFunction<BuildingConfig> extractor) {
        Set<String> types = colonyUnlockedTypes.get(colonyId);
        if (types == null || types.isEmpty()) return 0;

        Map<String, Integer> active = colonyActiveCounts.get(colonyId);
        BuildingConfigLoader configLoader = BuildingConfigLoader.getInstance();

        int total = 0;
        for (String typeId : types) {
            int activeCount = active != null ? active.getOrDefault(typeId, 0) : 0;
            if (activeCount > 0) {
                BuildingConfig cfg = configLoader.get(typeId);
                if (cfg != null) {
                    total += extractor.applyAsInt(cfg);
                }
            }
        }
        return total;
    }

    // ---- Task bridge ----

    @Override
    public boolean isBuildingOccupied(UUID buildingId) {
        return currentTasks.containsKey(buildingId);
    }

    @Override
    public List<UUID> getBuildingsWithPendingWork(UUID colonyId) {
        List<UUID> result = new ArrayList<>();
        for (BuildingData data : byId.values()) {
            if (colonyId != null && !colonyId.equals(data.getColonyId())) continue;
            if (data.isShutdown()) continue;
            if (currentTasks.containsKey(data.getBuildingId())) continue;

            AbstractWandscapeBE be = getBeAt(data.getPosition());
            if (be != null && be.hasWork()) {
                result.add(data.getBuildingId());
            }
        }
        return result;
    }

    @Override
    @Nullable
    public WorkItem dequeueWork(UUID buildingId) {
        BuildingData data = byId.get(buildingId);
        if (data == null) return null;

        AbstractWandscapeBE be = getBeAt(data.getPosition());
        return be != null ? be.dequeueWork() : null;
    }

    @Override
    public void enqueueWork(UUID buildingId, WorkItem work) {
        BuildingData data = byId.get(buildingId);
        if (data == null) return;

        AbstractWandscapeBE be = getBeAt(data.getPosition());
        if (be != null) be.enqueueWork(work);
    }

    @Override
    public List<UUID> getBuildingsByCategory(@Nullable UUID colonyId, String category) {
        List<UUID> result = new ArrayList<>();
        for (BuildingData data : byId.values()) {
            if (colonyId != null && !colonyId.equals(data.getColonyId())) continue;
            if (category.equals(data.getCategory())) {
                result.add(data.getBuildingId());
            }
        }
        return result;
    }

    @Override
    public void setCurrentTask(UUID buildingId, UUID taskId) {
        currentTasks.put(buildingId, taskId);
        BuildingData data = byId.get(buildingId);
        if (data != null) {
            AbstractWandscapeBE be = getBeAt(data.getPosition());
            if (be != null) be.setCurrentTaskId(taskId);
        }
    }

    @Override
    public void clearCurrentTask(UUID buildingId) {
        currentTasks.remove(buildingId);
        BuildingData data = byId.get(buildingId);
        if (data != null) {
            AbstractWandscapeBE be = getBeAt(data.getPosition());
            if (be != null) be.setCurrentTaskId(null);
        }
    }

    // ---- Helpers ----

    @Nullable
    private AbstractWandscapeBE getBeAt(BlockPos pos) {
        Level level = getServerLevel();
        if (level == null) return null;
        if (level.getBlockEntity(pos) instanceof AbstractWandscapeBE be) {
            return be;
        }
        return null;
    }

    @Nullable
    private static Level getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return server.overworld();
    }
}
