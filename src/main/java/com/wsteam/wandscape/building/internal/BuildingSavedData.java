package com.wsteam.wandscape.building.internal;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.data.WorkItem;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Level-attached persistent storage for all building state.
 *
 * <p>Three indexes:
 * <ul>
 *   <li>{@code buildings} — buildingId → BuildingState</li>
 *   <li>{@code posIndex} — BlockPos → buildingId (O(1) spatial lookup)</li>
 *   <li>{@code chunkIndex} — ChunkPos → Set of buildingIds (block-unload awareness)</li>
 * </ul>
 */
public class BuildingSavedData extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DATA_NAME = "wandscape_buildings";

    // NBT keys
    private static final String TAG_BUILDINGS = "buildings";
    private static final String TAG_ID = "id";
    private static final String TAG_TYPE = "type";
    private static final String TAG_CATEGORY = "category";
    private static final String TAG_ANCHOR = "anchor";
    private static final String TAG_BOUNDS_MIN = "bounds_min";
    private static final String TAG_BOUNDS_MAX = "bounds_max";
    private static final String TAG_COLONY = "colony";
    private static final String TAG_SHUTDOWN = "shutdown";
    private static final String TAG_INTACT = "intact";
    private static final String TAG_QUEUE = "queue";
    private static final String TAG_CURRENT_TASK = "current_task";
    private static final String TAG_COMFORT = "comfort";
    private static final String TAG_MAGIC = "magic";
    private static final String TAG_WONDER = "wonder";
    private static final String TAG_MAINTENANCE = "maintenance";
    private static final String TAG_CAPACITY = "capacity";
    private static final String TAG_QUEUE_ITEM_BLUEPRINT = "blueprint";
    private static final String TAG_QUEUE_ITEM_PARAMS = "params_json";
    private static final String TAG_QUEUE_ITEM_PRIORITY = "priority";

    private static final Gson PARAMS_GSON = new Gson();
    private static final java.lang.reflect.Type PARAMS_TYPE =
            new TypeToken<Map<String, JsonElement>>(){}.getType();

    // ── Indexes ──
    private final Map<UUID, BuildingState> buildings = new ConcurrentHashMap<>();
    private final Map<BlockPos, UUID> posIndex = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Set<UUID>> chunkIndex = new ConcurrentHashMap<>();

    // ── Factory ──

    public static final Factory<BuildingSavedData> FACTORY = new Factory<>(
            BuildingSavedData::new,
            BuildingSavedData::load,
            null
    );

    public static BuildingSavedData get(Level level) {
        return level.getServer().overworld()
                .getDataStorage()
                .computeIfAbsent(FACTORY, DATA_NAME);
    }

    // ── Query ──

    @Nullable
    public BuildingState getBuilding(UUID buildingId) {
        return buildings.get(buildingId);
    }

    @Nullable
    public BuildingState getBuildingAt(BlockPos pos) {
        UUID id = posIndex.get(pos);
        return id != null ? buildings.get(id) : null;
    }

    @Nullable
    public UUID getBuildingIdAt(BlockPos pos) {
        UUID id = posIndex.get(pos);
        if (id != null) return id;

        // Fallback via chunkIndex: after server restart, posIndex is not rebuilt
        // (requires BuildingConfig pattern). Walk buildings in the same chunk
        // and check bounding box containment.
        ChunkPos cp = new ChunkPos(pos);
        Set<UUID> chunkIds = chunkIndex.get(cp);
        if (chunkIds == null) return null;

        for (UUID candidate : chunkIds) {
            BuildingState state = buildings.get(candidate);
            if (state != null && state.getBounds().isInside(pos)) {
                // Cache in posIndex for next lookup
                posIndex.put(pos, candidate);
                return candidate;
            }
        }
        return null;
    }

    public Collection<BuildingState> getAllBuildings() {
        return Collections.unmodifiableCollection(buildings.values());
    }

    /** Buildings whose anchor is in the given chunk. */
    public List<BuildingState> getBuildingsInChunk(ChunkPos chunkPos) {
        Set<UUID> ids = chunkIndex.get(chunkPos);
        if (ids == null) return List.of();
        List<BuildingState> result = new ArrayList<>();
        for (UUID id : ids) {
            BuildingState state = buildings.get(id);
            if (state != null) result.add(state);
        }
        return result;
    }

    // ── Register / Unregister ──

    /**
     * Register a new building. Builds all indexes, checks AABB overlap.
     *
     * @throws BuildingOverlapException if the building's world AABB overlaps an existing one
     */
    public void register(BuildingState state, BuildingConfig config) {
        // AABB overlap check
        for (BuildingState existing : buildings.values()) {
            if (state.getBounds().intersects(existing.getBounds())) {
                throw new BuildingOverlapException(
                        "Building " + state.getBuildingTypeId() + " at " + state.getAnchor()
                        + " overlaps with " + existing.getBuildingTypeId()
                        + " at " + existing.getAnchor());
            }
        }

        buildings.put(state.getBuildingId(), state);

        // Build posIndex from config pattern
        for (BlockOffset off : config.pattern()) {
            BlockPos worldPos = state.getAnchor().offset(off.x(), off.y(), off.z());
            posIndex.put(worldPos, state.getBuildingId());
        }

        // Build chunkIndex from bounding box
        state.getBounds().intersectingChunks().forEach(cp -> {
            chunkIndex.computeIfAbsent(cp, k -> ConcurrentHashMap.newKeySet())
                    .add(state.getBuildingId());
        });

        setDirty();
        LOGGER.debug("registered building {} type={} at {} posIndexSize={}",
                state.getBuildingId().toString().substring(0, 8),
                state.getBuildingTypeId(), state.getAnchor(), posIndex.size());
    }

    /**
     * Remove a building and clean up all indexes.
     * @return the removed BuildingState, or null if not found
     */
    @Nullable
    public BuildingState unregister(UUID buildingId) {
        BuildingState state = buildings.remove(buildingId);
        if (state == null) return null;

        // Clean posIndex — remove all entries pointing to this building
        posIndex.values().removeIf(id -> id.equals(buildingId));

        // Clean chunkIndex — remove buildingId from all chunk sets
        for (Iterator<Set<UUID>> it = chunkIndex.values().iterator(); it.hasNext(); ) {
            Set<UUID> ids = it.next();
            ids.remove(buildingId);
            if (ids.isEmpty()) it.remove();
        }

        setDirty();
        LOGGER.debug("unregistered building {} type={} at {}",
                buildingId.toString().substring(0, 8),
                state.getBuildingTypeId(), state.getAnchor());
        return state;
    }

    // ── NBT persistence ──

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (BuildingState state : buildings.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_ID, state.getBuildingId());
            entry.putString(TAG_TYPE, state.getBuildingTypeId());
            entry.putString(TAG_CATEGORY, state.getCategory());
            entry.putIntArray(TAG_ANCHOR, new int[]{
                    state.getAnchor().getX(), state.getAnchor().getY(), state.getAnchor().getZ()});
            entry.putIntArray(TAG_BOUNDS_MIN, new int[]{
                    state.getBounds().minX(), state.getBounds().minY(), state.getBounds().minZ()});
            entry.putIntArray(TAG_BOUNDS_MAX, new int[]{
                    state.getBounds().maxX(), state.getBounds().maxY(), state.getBounds().maxZ()});
            if (state.getColonyId() != null) {
                entry.putUUID(TAG_COLONY, state.getColonyId());
            }
            entry.putBoolean(TAG_SHUTDOWN, state.isShutdown());
            entry.putBoolean(TAG_INTACT, state.isStructureIntact());
            entry.putInt(TAG_COMFORT, state.getComfort());
            entry.putInt(TAG_MAGIC, state.getMagic());
            entry.putInt(TAG_WONDER, state.getWonder());
            entry.putInt(TAG_MAINTENANCE, state.getMaintenanceCost());
            entry.putInt(TAG_CAPACITY, state.getQueueCapacity());
            if (state.getCurrentTaskId() != null) {
                entry.putUUID(TAG_CURRENT_TASK, state.getCurrentTaskId());
            }

            // Task queue
            ListTag queueTag = new ListTag();
            for (WorkItem item : state.getTaskQueue()) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putString(TAG_QUEUE_ITEM_BLUEPRINT, item.blueprintId());
                itemTag.putInt(TAG_QUEUE_ITEM_PRIORITY, item.priority());
                itemTag.putString(TAG_QUEUE_ITEM_PARAMS, PARAMS_GSON.toJson(item.params()));
                queueTag.add(itemTag);
            }
            entry.put(TAG_QUEUE, queueTag);

            list.add(entry);
        }
        tag.put(TAG_BUILDINGS, list);
        return tag;
    }

    private static BuildingSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        BuildingSavedData data = new BuildingSavedData();

        ListTag list = tag.getList(TAG_BUILDINGS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);

            UUID id = entry.getUUID(TAG_ID);
            String type = entry.getString(TAG_TYPE);
            String category = entry.getString(TAG_CATEGORY);

            int[] anchorArr = entry.getIntArray(TAG_ANCHOR);
            BlockPos anchor = new BlockPos(anchorArr[0], anchorArr[1], anchorArr[2]);

            int[] boundsMin = entry.getIntArray(TAG_BOUNDS_MIN);
            int[] boundsMax = entry.getIntArray(TAG_BOUNDS_MAX);
            BoundingBox bounds = new BoundingBox(
                    boundsMin[0], boundsMin[1], boundsMin[2],
                    boundsMax[0], boundsMax[1], boundsMax[2]);

            int comfort = entry.getInt(TAG_COMFORT);
            int magic = entry.getInt(TAG_MAGIC);
            int wonder = entry.getInt(TAG_WONDER);
            int maintenance = entry.getInt(TAG_MAINTENANCE);
            int capacity = entry.getInt(TAG_CAPACITY);

            BuildingState state = new BuildingState(id, type, category, anchor, bounds,
                    comfort, magic, wonder, maintenance, capacity);

            if (entry.hasUUID(TAG_COLONY)) {
                state.setColonyId(entry.getUUID(TAG_COLONY));
            }
            state.setShutdown(entry.getBoolean(TAG_SHUTDOWN));
            state.setStructureIntact(entry.getBoolean(TAG_INTACT));
            if (entry.hasUUID(TAG_CURRENT_TASK)) {
                state.setCurrentTaskId(entry.getUUID(TAG_CURRENT_TASK));
            }

            // Task queue
            ListTag queueTag = entry.getList(TAG_QUEUE, Tag.TAG_COMPOUND);
            for (int j = 0; j < queueTag.size(); j++) {
                CompoundTag itemTag = queueTag.getCompound(j);
                String blueprint = itemTag.getString(TAG_QUEUE_ITEM_BLUEPRINT);
                int priority = itemTag.getInt(TAG_QUEUE_ITEM_PRIORITY);

                Map<String, JsonElement> params = Collections.emptyMap();
                if (itemTag.contains(TAG_QUEUE_ITEM_PARAMS)) {
                    String json = itemTag.getString(TAG_QUEUE_ITEM_PARAMS);
                    params = PARAMS_GSON.fromJson(json, PARAMS_TYPE);
                    if (params == null) params = Collections.emptyMap();
                }
                state.getTaskQueue().addLast(new WorkItem(blueprint, params, priority));
            }

            // Register into indexes (no overlap check needed on load)
            data.buildings.put(id, state);
            data.rebuildIndexes(state);
        }

        LOGGER.info("Loaded {} buildings from saved data", data.buildings.size());
        return data;
    }

    /** Rebuild posIndex and chunkIndex for a single building (used during load). */
    private void rebuildIndexes(BuildingState state) {
        state.getBounds().intersectingChunks().forEach(cp -> {
            chunkIndex.computeIfAbsent(cp, k -> ConcurrentHashMap.newKeySet())
                    .add(state.getBuildingId());
        });
        // Note: posIndex cannot be fully rebuilt without BuildingConfig pattern.
        // The posIndex is rebuilt on register(), not on load(). This means after
        // a server restart, posIndex won't have entries until re-registration.
        // Right-click lookups during this window use chunkIndex fallback.
        // In practice, buildings should not be queried via posIndex between
        // load and re-registration — and re-registration happens via
        // EnqueueHelper.registerIfAbsent which is a no-op if building exists.
    }

    /** Rebuild posIndex entry for a building (used when BuildingConfig is available). */
    void rebuildPosIndex(BuildingState state, BuildingConfig config) {
        for (BlockOffset off : config.pattern()) {
            BlockPos worldPos = state.getAnchor().offset(off.x(), off.y(), off.z());
            posIndex.put(worldPos, state.getBuildingId());
        }
    }

    // ── Helpers ──

    /** Compute world-space BoundingBox from anchor + config boundary. */
    public static BoundingBox computeWorldBox(BlockPos anchor, BuildingConfig.BoundaryBox boundary) {
        return new BoundingBox(
                anchor.getX() + boundary.min().x(),
                anchor.getY() + boundary.min().y(),
                anchor.getZ() + boundary.min().z(),
                anchor.getX() + boundary.max().x(),
                anchor.getY() + boundary.max().y(),
                anchor.getZ() + boundary.max().z());
    }
}
