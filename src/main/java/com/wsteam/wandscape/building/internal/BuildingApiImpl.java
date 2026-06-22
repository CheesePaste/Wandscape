package com.wsteam.wandscape.building.internal;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.WorkItem;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Implementation of {@link BuildingApi} backed by {@link BuildingSavedData}.
 */
public class BuildingApiImpl implements BuildingApi {
    private static final Logger LOGGER = LogUtils.getLogger();

    // Task tracking (engine taskId → buildingId)
    private final Map<UUID, UUID> currentTasks = new ConcurrentHashMap<>(); // buildingId → taskId

    // Three-value: per colony, which building types have ever been built
    private final Map<UUID, Set<String>> colonyUnlockedTypes = new ConcurrentHashMap<>();
    // Per colony, how many active (non-shutdown) buildings of each type
    private final Map<UUID, Map<String, Integer>> colonyActiveCounts = new ConcurrentHashMap<>();

    @Nullable
    private Level serverLevel;

    public void setLevel(@Nullable Level level) {
        this.serverLevel = level;
    }

    @Nullable
    private BuildingSavedData getSavedData() {
        if (serverLevel == null) {
            serverLevel = getServerLevel();
        }
        return serverLevel != null ? BuildingSavedData.get(serverLevel) : null;
    }

    // ---- Query ----

    @Override
    public BuildingData getBuilding(UUID buildingId) {
        BuildingSavedData sd = getSavedData();
        return sd != null ? sd.getBuilding(buildingId) : null;
    }

    @Override
    public BuildingData getBuildingAt(BlockPos pos) {
        BuildingSavedData sd = getSavedData();
        return sd != null ? sd.getBuildingAt(pos) : null;
    }

    @Override
    public List<BuildingData> getColonyBuildings(UUID colonyId) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return List.of();
        List<BuildingData> result = new ArrayList<>();
        for (BuildingState state : sd.getAllBuildings()) {
            if (colonyId == null || colonyId.equals(state.getColonyId())) {
                result.add(state);
            }
        }
        return result;
    }

    // ---- Lifecycle ----

    @Override
    public void registerBuilding(BuildingData data) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) {
            LOGGER.warn("Cannot register building — no server level available");
            return;
        }

        BuildingState state;
        if (data instanceof BuildingState bs) {
            state = bs;
        } else {
            // Legacy path: wrap BuildingData into BuildingState
            BuildingConfigLoader configLoader = BuildingConfigLoader.getInstance();
            BuildingConfig config = configLoader.get(data.getBuildingTypeId());
            BlockPos anchor = data.getPosition();
            net.minecraft.world.level.levelgen.structure.BoundingBox bounds;
            if (config != null && config.boundary() != null) {
                bounds = BuildingSavedData.computeWorldBox(anchor, config.boundary());
            } else {
                bounds = new net.minecraft.world.level.levelgen.structure.BoundingBox(anchor);
            }
            state = new BuildingState(
                    data.getBuildingId(), data.getBuildingTypeId(), data.getCategory(),
                    anchor, bounds,
                    data.getComfort(), data.getMagic(), data.getWonder(),
                    data.getMaintenanceCost(), data.getQueueCapacity());
        }

        BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        if (config == null) {
            LOGGER.warn("Cannot register building — unknown type '{}'", state.getBuildingTypeId());
            return;
        }

        try {
            sd.register(state, config);
        } catch (BuildingOverlapException e) {
            LOGGER.warn(e.getMessage());
            throw e;
        }

        UUID colonyId = state.getColonyId();
        if (colonyId != null) {
            colonyUnlockedTypes
                    .computeIfAbsent(colonyId, k -> ConcurrentHashMap.newKeySet())
                    .add(state.getBuildingTypeId());
            colonyActiveCounts
                    .computeIfAbsent(colonyId, k -> new ConcurrentHashMap<>())
                    .merge(state.getBuildingTypeId(), 1, Integer::sum);
        }
        LOGGER.debug("registered building {} type={} at {}",
                state.getBuildingId(), state.getBuildingTypeId(), state.getAnchor());
    }

    @Override
    public void unregisterBuilding(BlockPos pos) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return;

        BuildingState state = sd.getBuildingAt(pos);
        if (state == null) return;

        UUID colonyId = state.getColonyId();
        if (colonyId != null) {
            Map<String, Integer> counts = colonyActiveCounts.get(colonyId);
            if (counts != null) {
                counts.merge(state.getBuildingTypeId(), -1, Integer::sum);
                counts.remove(state.getBuildingTypeId(), 0);
            }
        }
        currentTasks.remove(state.getBuildingId());
        sd.unregister(state.getBuildingId());
    }

    // ---- Shutdown/Restart ----

    @Override
    public boolean shutdown(UUID buildingId) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return false;

        BuildingState state = sd.getBuilding(buildingId);
        if (state == null) return false;

        if (!state.isShutdown()) {
            state.setShutdown(true);
            UUID cid = state.getColonyId();
            if (cid != null) {
                Map<String, Integer> counts = colonyActiveCounts.get(cid);
                if (counts != null) {
                    counts.merge(state.getBuildingTypeId(), -1, Integer::sum);
                }
            }
            sd.setDirty();
        }
        return true;
    }

    @Override
    public boolean restart(UUID buildingId) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return false;

        BuildingState state = sd.getBuilding(buildingId);
        if (state == null) return false;

        if (state.isShutdown()) {
            state.setShutdown(false);
            UUID cid = state.getColonyId();
            if (cid != null) {
                colonyActiveCounts
                        .computeIfAbsent(cid, k -> new ConcurrentHashMap<>())
                        .merge(state.getBuildingTypeId(), 1, Integer::sum);
            }
            sd.setDirty();
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
        BuildingSavedData sd = getSavedData();
        if (sd == null || serverLevel == null) return List.of();

        List<UUID> result = new ArrayList<>();
        for (BuildingState state : sd.getAllBuildings()) {
            if (colonyId != null && !colonyId.equals(state.getColonyId())) continue;
            if (state.isShutdown()) continue;
            if (currentTasks.containsKey(state.getBuildingId())) continue;
            if (!state.hasWork()) continue;
            if (!serverLevel.isLoaded(state.getAnchor())) continue;
            result.add(state.getBuildingId());
        }
        return result;
    }

    @Override
    @Nullable
    public WorkItem dequeueWork(UUID buildingId) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return null;

        BuildingState state = sd.getBuilding(buildingId);
        if (state == null || state.isShutdown()) return null;

        WorkItem item = state.getTaskQueue().pollFirst();
        if (item != null) sd.setDirty();
        return item;
    }

    @Override
    public void enqueueWork(UUID buildingId, WorkItem work) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return;

        BuildingState state = sd.getBuilding(buildingId);
        if (state == null || state.isShutdown()) return;

        if (state.getTaskQueue().size() >= state.getQueueCapacity()) {
            LOGGER.warn("enqueueWork: building {} queue full (capacity={})",
                    buildingId, state.getQueueCapacity());
            return;
        }
        state.getTaskQueue().addLast(work);
        sd.setDirty();
    }

    @Override
    public List<UUID> getBuildingsByCategory(@Nullable UUID colonyId, String category) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return List.of();

        List<UUID> result = new ArrayList<>();
        for (BuildingState state : sd.getAllBuildings()) {
            if (colonyId != null && !colonyId.equals(state.getColonyId())) continue;
            if (category.equals(state.getCategory())) {
                result.add(state.getBuildingId());
            }
        }
        return result;
    }

    @Override
    public void setCurrentTask(UUID buildingId, UUID taskId) {
        currentTasks.put(buildingId, taskId);
        BuildingSavedData sd = getSavedData();
        if (sd != null) {
            BuildingState state = sd.getBuilding(buildingId);
            if (state != null) {
                state.setCurrentTaskId(taskId);
                sd.setDirty();
            }
        }
    }

    @Override
    public void clearCurrentTask(UUID buildingId) {
        currentTasks.remove(buildingId);
        BuildingSavedData sd = getSavedData();
        if (sd != null) {
            BuildingState state = sd.getBuilding(buildingId);
            if (state != null) {
                state.setCurrentTaskId(null);
                sd.setDirty();
            }
        }
    }

    @Override
    public List<WorkItem> getQueue(UUID buildingId) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return List.of();

        BuildingState state = sd.getBuilding(buildingId);
        if (state == null) return List.of();

        return new ArrayList<>(state.getTaskQueue());
    }

    @Override
    public boolean removeFromQueue(UUID buildingId, int index) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) {
            LOGGER.warn("removeFromQueue: no saved data for {}", buildingId);
            return false;
        }

        BuildingState state = sd.getBuilding(buildingId);
        if (state == null) {
            LOGGER.warn("removeFromQueue: building {} not found", buildingId);
            return false;
        }
        if (state.isShutdown()) {
            LOGGER.warn("removeFromQueue: building {} is shutdown", buildingId);
            return false;
        }

        Deque<WorkItem> queue = state.getTaskQueue();
        if (index < 0 || index >= queue.size()) {
            LOGGER.warn("removeFromQueue: index {} out of range (size={}) for {}", index, queue.size(), buildingId);
            return false;
        }
        if (index == 0) {
            LOGGER.warn("removeFromQueue: refused to remove index 0 (current task) at {}", buildingId);
            return false;
        }

        // Convert deque to list, remove, then rebuild deque
        java.util.List<WorkItem> list = new ArrayList<>(queue);
        WorkItem removed = list.remove(index);
        queue.clear();
        queue.addAll(list);
        sd.setDirty();
        LOGGER.info("removeFromQueue: removed [{}] {} from building {}", index, removed.blueprintId(), buildingId);
        return true;
    }

    @Override
    public boolean moveUp(UUID buildingId, int index) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) {
            LOGGER.warn("moveUp: no saved data for {}", buildingId);
            return false;
        }

        BuildingState state = sd.getBuilding(buildingId);
        if (state == null) {
            LOGGER.warn("moveUp: building {} not found", buildingId);
            return false;
        }
        if (state.isShutdown()) {
            LOGGER.warn("moveUp: building {} is shutdown", buildingId);
            return false;
        }

        Deque<WorkItem> queue = state.getTaskQueue();
        if (index <= 0 || index >= queue.size()) {
            LOGGER.warn("moveUp: index {} out of range (size={}) for {}", index, queue.size(), buildingId);
            return false;
        }

        java.util.List<WorkItem> list = new ArrayList<>(queue);
        WorkItem upper = list.get(index - 1);
        WorkItem lower = list.get(index);
        java.util.Collections.swap(list, index, index - 1);
        queue.clear();
        queue.addAll(list);
        sd.setDirty();
        LOGGER.info("moveUp: [{}]{}↔[{}]{} at {}",
                index - 1, upper.blueprintId(), index, lower.blueprintId(), buildingId);
        return true;
    }

    @Override
    public boolean moveDown(UUID buildingId, int index) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) {
            LOGGER.warn("moveDown: no saved data for {}", buildingId);
            return false;
        }

        BuildingState state = sd.getBuilding(buildingId);
        if (state == null) {
            LOGGER.warn("moveDown: building {} not found", buildingId);
            return false;
        }
        if (state.isShutdown()) {
            LOGGER.warn("moveDown: building {} is shutdown", buildingId);
            return false;
        }

        Deque<WorkItem> queue = state.getTaskQueue();
        if (index < 0 || index >= queue.size() - 1) {
            LOGGER.warn("moveDown: index {} out of range (size={}) for {}", index, queue.size(), buildingId);
            return false;
        }

        java.util.List<WorkItem> list = new ArrayList<>(queue);
        WorkItem upper = list.get(index);
        WorkItem lower = list.get(index + 1);
        java.util.Collections.swap(list, index, index + 1);
        queue.clear();
        queue.addAll(list);
        sd.setDirty();
        LOGGER.info("moveDown: [{}]{}↔[{}]{} at {}",
                index, upper.blueprintId(), index + 1, lower.blueprintId(), buildingId);
        return true;
    }

    // ---- Helpers ----

    @Nullable
    private static Level getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return server.overworld();
    }
}
