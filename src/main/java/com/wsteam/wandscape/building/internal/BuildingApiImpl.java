package com.wsteam.wandscape.building.internal;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.event.BuildingPlacedEvent;
import com.wsteam.wandscape.shared.event.BuildingRestartedEvent;
import com.wsteam.wandscape.shared.event.BuildingShutdownEvent;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Implementation of {@link BuildingApi} backed by {@link BuildingSavedData}.
 */
public class BuildingApiImpl implements BuildingApi {
    private static final String TAG = "BuildingApiImpl";

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
            if (colonyId == null || java.util.Objects.equals(colonyId, state.getColonyId())) {
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
            Log.warn(TAG, "Cannot register building — no server level available");
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
                    data.getQueueCapacity());
        }

        BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        if (config == null) {
            Log.warn(TAG, "Cannot register building — unknown type '{}'", state.getBuildingTypeId());
            return;
        }

        try {
            sd.register(state, config);
        } catch (BuildingOverlapException e) {
            Log.warn(TAG, e.getMessage());
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
        Log.debug(TAG, "registered building {} type={} at {}",
                state.getBuildingId(), state.getBuildingTypeId(), state.getAnchor());

        // Notify downstream systems (e.g. tourist spawner, colony evaluation)
        // so they react to building registration regardless of whether an NPC
        // built it or it was placed via command / admin tools.
        NeoForge.EVENT_BUS.post(new BuildingPlacedEvent(
                state.getBuildingId(), colonyId, state.getBuildingTypeId()));
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
            // Also remove from the contribution registry so evaluation values drop
            // if this was the last intact building of its type.
            sd.removeBuildingContribution(colonyId, state.getBuildingTypeId());
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
            String category = state.getCategory();
            if (cid != null) {
                Map<String, Integer> counts = colonyActiveCounts.get(cid);
                if (counts != null) {
                    counts.merge(state.getBuildingTypeId(), -1, Integer::sum);
                }
                // Apply category-specific graded shutdown penalties
                applyShutdownPenalties(sd, state, cid, category);
                NeoForge.EVENT_BUS.post(new BuildingShutdownEvent(buildingId));
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
                // Restore contributions that were zeroed on shutdown
                if (state.isStructureIntact()) {
                    sd.addBuildingContribution(cid, state.getBuildingTypeId());
                }
                NeoForge.EVENT_BUS.post(new BuildingRestartedEvent(buildingId));
            }
            sd.setDirty();
        }
        return true;
    }

    /**
     * Apply category-specific penalties when a building is shut down.
     * Categories that lose their three-value contribution: shop, basic, storage, tavern.
     * Other categories: effects are handled by their respective systems (C2-C4).
     */
    private void applyShutdownPenalties(BuildingSavedData sd, BuildingState state,
                                        UUID colonyId, String category) {
        switch (category) {
            case "shop", "basic", "storage", "tavern":
                sd.removeBuildingContribution(colonyId, state.getBuildingTypeId());
                Log.info(TAG, "[Shutdown] {} '{}': contribution zeroed",
                        category, state.getBuildingId().toString().substring(0, 8));
                break;
            case "decoration":
                // Radiation zeroed — DecorationBonusSystem checks isShutdown()
                break;
            case "wonder":
                // Global effects paused — WonderEffectApplier checks isShutdown()
                break;
            case "service":
                // Still usable but output halved — production module checks
                break;
            case "workstation", "node":
                // Work time +100%, output -50% — production/scheduler checks
                break;
            default:
                // Safe default: zero contribution for unknown categories
                sd.removeBuildingContribution(colonyId, state.getBuildingTypeId());
                Log.warn(TAG, "[Shutdown] Unknown category '{}': contribution zeroed",
                        category);
                break;
        }
    }

    // ---- Colony stats (three-value system) ----

    @Override
    public int getColonyComfort(UUID colonyId) {
        BuildingSavedData sd = getSavedData();
        return sd != null && colonyId != null
                ? sd.getContributionRegistry().getSnapshot(colonyId).comfort() : 0;
    }

    @Override
    public int getColonyMagic(UUID colonyId) {
        BuildingSavedData sd = getSavedData();
        return sd != null && colonyId != null
                ? sd.getContributionRegistry().getSnapshot(colonyId).magic() : 0;
    }

    @Override
    public int getColonyWonder(UUID colonyId) {
        BuildingSavedData sd = getSavedData();
        return sd != null && colonyId != null
                ? sd.getContributionRegistry().getSnapshot(colonyId).wonder() : 0;
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
            String id8 = state.getBuildingId().toString().substring(0, 8);
            if (colonyId != null && !java.util.Objects.equals(colonyId, state.getColonyId())) {
                Log.debug(TAG, "[BldgAPI] skip {} colony mismatch: filter={} state={}",
                        id8,
                        colonyId != null ? colonyId.toString().substring(0, 8) : "null",
                        state.getColonyId() != null ? state.getColonyId().toString().substring(0, 8) : "null");
                continue;
            }
            if (state.isShutdown()) {
                Log.debug(TAG, "[BldgAPI] skip {} isShutdown=true", id8);
                continue;
            }
            if (currentTasks.containsKey(state.getBuildingId())) {
                Log.debug(TAG, "[BldgAPI] skip {} has active task", id8);
                continue;
            }
            if (!state.hasWork()) {
                Log.debug(TAG, "[BldgAPI] skip {} queue={} shutdown={} noWork=true",
                        id8, state.getTaskQueue().size(), state.isShutdown());
                continue;
            }
            if (!serverLevel.isLoaded(state.getAnchor())) {
                Log.debug(TAG, "[BldgAPI] skip {} anchor={} not loaded",
                        id8, state.getAnchor());
                continue;
            }
            result.add(state.getBuildingId());
        }
        Log.info(TAG, "[BldgAPI] getBuildingsWithPendingWork(colonyId={}) → {} buildings: {}",
                colonyId != null ? colonyId.toString().substring(0, 8) : "null",
                result.size(),
                result.stream().map(u -> u.toString().substring(0, 8)).toList());
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

    /** Colony-wide capacity for construction/repair tasks (per building). */
    private static final int CONSTRUCTION_CAPACITY = 5;

    @Override
    public void enqueueWork(UUID buildingId, WorkItem work) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return;

        BuildingState state = sd.getBuilding(buildingId);
        if (state == null || state.isShutdown()) return;

        boolean isConstruction = work.blueprintId().startsWith("build:");

        if (isConstruction) {
            long buildCount = state.getTaskQueue().stream()
                    .filter(w -> w.blueprintId().startsWith("build:"))
                    .count();
            if (buildCount >= CONSTRUCTION_CAPACITY) {
                Log.warn(TAG, "enqueueWork: building {} construction queue full (capacity={})",
                        buildingId, CONSTRUCTION_CAPACITY);
                return;
            }
        } else {
            if (state.getTaskQueue().size() >= state.getQueueCapacity()) {
                Log.warn(TAG, "enqueueWork: building {} queue full (capacity={})",
                        buildingId, state.getQueueCapacity());
                return;
            }
        }

        state.getTaskQueue().addLast(work);
        sd.setDirty();
    }

    @Override
    public List<UUID> getBuildingsByCategory(@Nullable UUID colonyId, String category) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return List.of();

        List<UUID> result = new ArrayList<>();
        int total = 0, skippedColony = 0, skippedCat = 0;
        for (BuildingState state : sd.getAllBuildings()) {
            total++;
            if (colonyId != null && !java.util.Objects.equals(colonyId, state.getColonyId())&&(state.getColonyId()!=null)) {
                skippedColony++;
                continue;
            }
            if (!category.equals(state.getCategory())) {
                skippedCat++;
                continue;
            }
            result.add(state.getBuildingId());
        }

        Log.info(TAG, "[BldgAPI] getBuildingsByCategory(colonyId={} cat={}) → {} / {} total (skip_colony={} skip_cat={})",
                colonyId != null ? colonyId.toString().substring(0, 8) : "null",
                category, result.size(), total, skippedColony, skippedCat);
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
            Log.warn(TAG, "removeFromQueue: no saved data for {}", buildingId);
            return false;
        }

        BuildingState state = sd.getBuilding(buildingId);
        if (state == null) {
            Log.warn(TAG, "removeFromQueue: building {} not found", buildingId);
            return false;
        }
        if (state.isShutdown()) {
            Log.warn(TAG, "removeFromQueue: building {} is shutdown", buildingId);
            return false;
        }

        Deque<WorkItem> queue = state.getTaskQueue();
        if (index < 0 || index >= queue.size()) {
            Log.warn(TAG, "removeFromQueue: index {} out of range (size={}) for {}", index, queue.size(), buildingId);
            return false;
        }
        if (index == 0) {
            Log.warn(TAG, "removeFromQueue: refused to remove index 0 (current task) at {}", buildingId);
            return false;
        }

        // Convert deque to list, remove, then rebuild deque
        java.util.List<WorkItem> list = new ArrayList<>(queue);
        WorkItem removed = list.remove(index);
        queue.clear();
        queue.addAll(list);
        sd.setDirty();
        Log.info(TAG, "removeFromQueue: removed [{}] {} from building {}", index, removed.blueprintId(), buildingId);
        return true;
    }

    @Override
    public boolean moveUp(UUID buildingId, int index) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) {
            Log.warn(TAG, "moveUp: no saved data for {}", buildingId);
            return false;
        }

        BuildingState state = sd.getBuilding(buildingId);
        if (state == null) {
            Log.warn(TAG, "moveUp: building {} not found", buildingId);
            return false;
        }
        if (state.isShutdown()) {
            Log.warn(TAG, "moveUp: building {} is shutdown", buildingId);
            return false;
        }

        Deque<WorkItem> queue = state.getTaskQueue();
        if (index <= 0 || index >= queue.size()) {
            Log.warn(TAG, "moveUp: index {} out of range (size={}) for {}", index, queue.size(), buildingId);
            return false;
        }

        java.util.List<WorkItem> list = new ArrayList<>(queue);
        WorkItem upper = list.get(index - 1);
        WorkItem lower = list.get(index);
        java.util.Collections.swap(list, index, index - 1);
        queue.clear();
        queue.addAll(list);
        sd.setDirty();
        Log.info(TAG, "moveUp: [{}]{}↔[{}]{} at {}",
                index - 1, upper.blueprintId(), index, lower.blueprintId(), buildingId);
        return true;
    }

    @Override
    public boolean moveDown(UUID buildingId, int index) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) {
            Log.warn(TAG, "moveDown: no saved data for {}", buildingId);
            return false;
        }

        BuildingState state = sd.getBuilding(buildingId);
        if (state == null) {
            Log.warn(TAG, "moveDown: building {} not found", buildingId);
            return false;
        }
        if (state.isShutdown()) {
            Log.warn(TAG, "moveDown: building {} is shutdown", buildingId);
            return false;
        }

        Deque<WorkItem> queue = state.getTaskQueue();
        if (index < 0 || index >= queue.size() - 1) {
            Log.warn(TAG, "moveDown: index {} out of range (size={}) for {}", index, queue.size(), buildingId);
            return false;
        }

        java.util.List<WorkItem> list = new ArrayList<>(queue);
        WorkItem upper = list.get(index);
        WorkItem lower = list.get(index + 1);
        java.util.Collections.swap(list, index, index + 1);
        queue.clear();
        queue.addAll(list);
        sd.setDirty();
        Log.info(TAG, "moveDown: [{}]{}↔[{}]{} at {}",
                index, upper.blueprintId(), index + 1, lower.blueprintId(), buildingId);
        return true;
    }

    // ---- Helpers ----

    @Override
    public List<BlockPos> findBeds(UUID buildingId) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return List.of();

        BuildingState state = sd.getBuilding(buildingId);
        if (state == null) return List.of();

        Level level = this.serverLevel;
        if (level == null) level = getServerLevel();
        if (level == null) return List.of();

        BoundingBox bounds = state.getBounds();
        List<BlockPos> beds = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int total = 0, found = 0;

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    total++;
                    pos.set(x, y, z);
                    if (level.getBlockState(pos).is(BlockTags.BEDS)) {
                        beds.add(pos.immutable());
                        found++;
                    }
                }
            }
        }
        Log.debug(TAG, "[BldgAPI] findBeds({}) → {}/{} blocks in boundary", buildingId, found, total);
        return beds;
    }

    @Override
    public List<BlockPos> sampleWalkableGround(UUID buildingId, int count) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return List.of();

        BuildingState state = sd.getBuilding(buildingId);
        if (state == null) return List.of();

        Level level = this.serverLevel;
        if (level == null) level = getServerLevel();
        if (level == null) return List.of();

        BoundingBox bounds = state.getBounds();
        int bx = bounds.maxX() - bounds.minX();
        int by = bounds.maxY() - bounds.minY();
        int bz = bounds.maxZ() - bounds.minZ();

        if (bx < 1) bx = 1; if (bz < 1) bz = 1;
        if (by < 1) by = 1;

        Random rng = new Random();
        List<BlockPos> result = new ArrayList<>();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();

        for (int attempt = 0; attempt < count * 6 && result.size() < count; attempt++) {
            int x = bounds.minX() + rng.nextInt(bx + 1);
            int z = bounds.minZ() + rng.nextInt(bz + 1);
            int y = bounds.minY() + rng.nextInt(by + 1);

            // Walkable = solid block at y-1, air at y
            mp.set(x, y, z);
            if (level.getBlockState(mp).isAir()
                    && level.getBlockState(mp.below()).isSolid()
                    && !level.getBlockState(mp.below()).is(BlockTags.BEDS)) {
                if (result.stream().noneMatch(p -> p.distSqr(mp) < 4)) {
                    result.add(mp.immutable());
                }
            }
        }
        Log.debug(TAG, "[BldgAPI] sampleWalkableGround({}) → {} samples", buildingId, result.size());
        return result;
    }

    @Override
    @Nullable
    public BlockPos getInteractionTarget(UUID buildingId) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return null;

        Level level = this.serverLevel;
        if (level == null) level = getServerLevel();
        if (level == null) return null;

        return sd.getInteractionTarget(buildingId, level);
    }

    @Nullable
    private static Level getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return server.overworld();
    }
}
