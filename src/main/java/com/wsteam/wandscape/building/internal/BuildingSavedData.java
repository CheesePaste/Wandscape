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
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.MaintenanceCostConfig;
import com.wsteam.wandscape.shared.data.ShopGoodDef;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.event.ColonyEvaluationChangedEvent;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.IEventBus;

/**
 * Level-attached persistent storage for all building state.
 *
 * <p>Three indexes:
 * <ul>
 *   <li>{@code buildings} — buildingId → BuildingState</li>
 *   <li>{@code posIndex} — BlockPos → buildingId (O(1) spatial lookup)</li>
 *   <li>{@code chunkIndex} — ChunkPos → Set of buildingIds (block-unload awareness)</li>
 * </ul>
 *
 * <p>Also owns a {@link BuildingContributionRegistry} that tracks, per colony,
 * how many intact buildings exist for each type and fires
 * {@link ColonyEvaluationChangedEvent} whenever the 0↔1 boundary is crossed
 * for any type (i.e. the first intact building of a type is placed, or the
 * last one is destroyed/damaged).
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
    private static final String TAG_CAPACITY = "capacity";
    private static final String TAG_QUEUE_ITEM_BLUEPRINT = "blueprint";
    private static final String TAG_QUEUE_ITEM_PARAMS = "params_json";
    private static final String TAG_QUEUE_ITEM_PRIORITY = "priority";
    private static final String TAG_MAINTENANCE_INTERVAL = "maint_interval";
    private static final String TAG_MAINTENANCE_COSTS = "maint_costs";
    private static final String TAG_LAST_MAINTENANCE_TICK = "last_maint_tick";
    private static final String TAG_MAINTENANCE_PAID = "maint_paid";

    // NBT keys for shop inventory persistence
    private static final String TAG_SHOP_STOCK = "shop_stock";
    private static final String TAG_SHOP_MAX_STOCK = "shop_max_stock";

    private static final Gson PARAMS_GSON = new Gson();
    private static final java.lang.reflect.Type PARAMS_TYPE =
            new TypeToken<Map<String, JsonElement>>(){}.getType();

    // ── Indexes ──
    private final Map<UUID, BuildingState> buildings = new ConcurrentHashMap<>();
    private final Map<BlockPos, UUID> posIndex = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Set<UUID>> chunkIndex = new ConcurrentHashMap<>();

    // ── Contribution registry ──
    /**
     * Tracks intact-building presence per (colony, type).
     * Initialised in {@link #load}; accessed via {@link #getContributionRegistry()}.
     */
    @Nullable
    private BuildingContributionRegistry contributionRegistry;

    // ── Shop inventory persistence ──
    /** buildingId → (itemId → current stock). Only for shop-category buildings. */
    private final Map<UUID, Map<String, Integer>> shopStock = new ConcurrentHashMap<>();
    /** buildingId → (itemId → max stock). Player-configured max stock settings. */
    private final Map<UUID, Map<String, Integer>> shopMaxStock = new ConcurrentHashMap<>();

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

    /**
     * Finds a building whose interaction zone covers the given position.
     * <p>
     * The interaction zone is the building's bounding box interior. Clicking
     * inside any building's bounding box (not just on pattern blocks) counts
     * as interacting. Buildings with {@code interaction_radius > 0} additionally
     * extend the zone outward by that many blocks.
     *
     * @return buildingId if pos is within interaction zone of an intact non-shutdown building
     */
    @Nullable
    public UUID getBuildingIdInInteractionZone(BlockPos pos) {
        BuildingConfigLoader configLoader = BuildingConfigLoader.getInstance();
        ChunkPos cp = new ChunkPos(pos);
        Set<UUID> chunkIds = chunkIndex.get(cp);
        if (chunkIds == null) return null;

        UUID bestMatch = null;
        int bestRadius = -1;

        for (UUID candidate : chunkIds) {
            BuildingState state = buildings.get(candidate);
            if (state == null || state.isShutdown() || !state.isStructureIntact()) continue;

            var bb = state.getBounds();
            BuildingConfig config = configLoader.get(state.getBuildingTypeId());
            int radius = config != null ? config.interactionRadius() : 0;

            // Check bounding box interior (always checked) + expanded area if radius > 0
            if (pos.getX() >= bb.minX() - radius
                    && pos.getX() <= bb.maxX() + radius
                    && pos.getY() >= bb.minY() - radius
                    && pos.getY() <= bb.maxY() + radius
                    && pos.getZ() >= bb.minZ() - radius
                    && pos.getZ() <= bb.maxZ() + radius) {
                // Prefer the building with the smallest expansion (tighter match)
                if (radius > bestRadius) {
                    bestRadius = radius;
                    bestMatch = candidate;
                }
            }
        }
        return bestMatch;
    }

    /**
     * Computes the navigation target position for tourist AI to interact
     * with a building. Returns a walkable position inside the building's
     * bounding box — the tourist navigates here, then the interaction triggers.
     *
     * @param buildingId the building to target
     * @param level      the world level (for block-state queries)
     * @return a walkable BlockPos inside the bounding box, or the anchor as fallback
     */
    @Nullable
    public BlockPos getInteractionTarget(UUID buildingId, Level level) {
        BuildingState state = buildings.get(buildingId);
        if (state == null || state.isShutdown() || !state.isStructureIntact()) return null;

        BoundingBox bounds = state.getBounds();
        int bx = bounds.maxX() - bounds.minX();
        int bz = bounds.maxZ() - bounds.minZ();
        if (bx < 1) bx = 1;
        if (bz < 1) bz = 1;

        int cx = (bounds.minX() + bounds.maxX()) / 2;
        int cz = (bounds.minZ() + bounds.maxZ()) / 2;
        int maxR = Math.max(bx, bz) + 1;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();

        // Spiral outward from center, scanning Y for walkable ground
        for (int r = 0; r <= maxR; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    int x = cx + dx;
                    int z = cz + dz;
                    if (x < bounds.minX() || x > bounds.maxX()
                            || z < bounds.minZ() || z > bounds.maxZ()) continue;

                    for (int y = bounds.maxY(); y >= bounds.minY(); y--) {
                        mp.set(x, y, z);
                        if (level.getBlockState(mp).isAir()
                                && level.getBlockState(mp.below()).isSolid()) {
                            return mp.immutable();
                        }
                    }
                }
            }
        }

        return state.getAnchor();
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
            entry.putInt(TAG_CAPACITY, state.getQueueCapacity());
            if (state.getCurrentTaskId() != null) {
                entry.putUUID(TAG_CURRENT_TASK, state.getCurrentTaskId());
            }

            // Maintenance tracking
            entry.putInt(TAG_MAINTENANCE_INTERVAL, state.getMaintenanceCost().intervalTicks());
            CompoundTag costsTag = new CompoundTag();
            for (var costEntry : state.getMaintenanceCost().costs().entrySet()) {
                costsTag.putInt(costEntry.getKey().name(), costEntry.getValue());
            }
            entry.put(TAG_MAINTENANCE_COSTS, costsTag);
            entry.putLong(TAG_LAST_MAINTENANCE_TICK, state.getLastMaintenanceTick());
            entry.putBoolean(TAG_MAINTENANCE_PAID, state.isMaintenancePaid());

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

        // ── Shop inventory persistence ──
        CompoundTag stockTag = new CompoundTag();
        for (var entry : shopStock.entrySet()) {
            CompoundTag itemsTag = new CompoundTag();
            for (var item : entry.getValue().entrySet()) {
                itemsTag.putInt(item.getKey(), item.getValue());
            }
            stockTag.put(entry.getKey().toString(), itemsTag);
        }
        tag.put(TAG_SHOP_STOCK, stockTag);

        CompoundTag maxStockTag = new CompoundTag();
        for (var entry : shopMaxStock.entrySet()) {
            CompoundTag itemsTag = new CompoundTag();
            for (var item : entry.getValue().entrySet()) {
                itemsTag.putInt(item.getKey(), item.getValue());
            }
            maxStockTag.put(entry.getKey().toString(), itemsTag);
        }
        tag.put(TAG_SHOP_MAX_STOCK, maxStockTag);

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
            int capacity = entry.getInt(TAG_CAPACITY);

            BuildingState state = new BuildingState(id, type, category, anchor, bounds,
                    comfort, magic, wonder, capacity);

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

            // Maintenance tracking
            if (entry.contains(TAG_MAINTENANCE_INTERVAL)) {
                int interval = entry.getInt(TAG_MAINTENANCE_INTERVAL);
                Map<ElementType, Integer> costsMap = new HashMap<>();
                if (entry.contains(TAG_MAINTENANCE_COSTS)) {
                    CompoundTag costsTag = entry.getCompound(TAG_MAINTENANCE_COSTS);
                    for (String key : costsTag.getAllKeys()) {
                        costsMap.put(ElementType.valueOf(key), costsTag.getInt(key));
                    }
                }
                state.setMaintenanceCost(new MaintenanceCostConfig(interval, costsMap));
            }
            state.setLastMaintenanceTick(entry.getLong(TAG_LAST_MAINTENANCE_TICK));
            if (entry.contains(TAG_MAINTENANCE_PAID)) {
                state.setMaintenancePaid(entry.getBoolean(TAG_MAINTENANCE_PAID));
            }

            // Register into indexes (no overlap check needed on load)
            data.buildings.put(id, state);
            data.rebuildIndexes(state);
        }

        // Initialise the contribution registry and rebuild from world state
        IEventBus bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        data.contributionRegistry = new BuildingContributionRegistry(bus);
        data.contributionRegistry.setBuildSource(data::getAllBuildings);
        data.contributionRegistry.rebuildFrom(data::getAllBuildings);

        LOGGER.info("Loaded {} buildings from saved data", data.buildings.size());

        // ── Load shop inventory persistence ──
        if (tag.contains(TAG_SHOP_STOCK)) {
            CompoundTag stockTag = tag.getCompound(TAG_SHOP_STOCK);
            for (String key : stockTag.getAllKeys()) {
                try {
                    UUID buildingId = UUID.fromString(key);
                    CompoundTag itemsTag = stockTag.getCompound(key);
                    Map<String, Integer> items = new HashMap<>();
                    for (String itemId : itemsTag.getAllKeys()) {
                        items.put(itemId, itemsTag.getInt(itemId));
                    }
                    data.shopStock.put(buildingId, items);
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Invalid building UUID in shop stock: {}", key);
                }
            }
        }
        if (tag.contains(TAG_SHOP_MAX_STOCK)) {
            CompoundTag maxStockTag = tag.getCompound(TAG_SHOP_MAX_STOCK);
            for (String key : maxStockTag.getAllKeys()) {
                try {
                    UUID buildingId = UUID.fromString(key);
                    CompoundTag itemsTag = maxStockTag.getCompound(key);
                    Map<String, Integer> items = new HashMap<>();
                    for (String itemId : itemsTag.getAllKeys()) {
                        items.put(itemId, itemsTag.getInt(itemId));
                    }
                    data.shopMaxStock.put(buildingId, items);
                } catch (IllegalArgumentException e) {
                    LOGGER.warn("Invalid building UUID in shop max stock: {}", key);
                }
            }
        }

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

    // ── Contribution tracking ────────────────────────────────────────────────

    /**
     * Returns the {@link BuildingContributionRegistry} owned by this data store.
     */
    public BuildingContributionRegistry getContributionRegistry() {
        return contributionRegistry;
    }

    // ── Shop inventory persistence ──

    /**
     * Returns a snapshot of the shop's current stock (itemId → count).
     * Returns an empty map if this building has no stock data.
     */
    public Map<String, Integer> getShopStock(UUID buildingId) {
        Map<String, Integer> s = shopStock.get(buildingId);
        return s != null ? Map.copyOf(s) : Map.of();
    }

    /**
     * Returns the mutable stock map for a shop building.
     * Creates an empty map if none exists. Used internally by ShopStockManager.
     */
    Map<String, Integer> getOrCreateShopStock(UUID buildingId) {
        return shopStock.computeIfAbsent(buildingId, k -> new ConcurrentHashMap<>());
    }

    /** Returns true if the shop has any item with stock > 0. */
    public boolean hasShopStock(UUID buildingId) {
        Map<String, Integer> s = shopStock.get(buildingId);
        return s != null && s.values().stream().anyMatch(v -> v > 0);
    }

    /**
     * Returns the max stock for a specific good.
     * Returns the default (0) if no player-configured setting exists.
     */
    public int getShopMaxStock(UUID buildingId, String itemId) {
        Map<String, Integer> perBuilding = shopMaxStock.get(buildingId);
        if (perBuilding != null) {
            Integer v = perBuilding.get(itemId);
            if (v != null) return v;
        }
        return ShopGoodDef.DEFAULT_MAX_STOCK;
    }

    /**
     * Returns all max stock settings for a shop (itemId → maxStock).
     * Only includes goods in the building's config, with defaults for unset ones.
     */
    public Map<String, Integer> getAllShopMaxStocks(UUID buildingId) {
        Map<String, Integer> perBuilding = shopMaxStock.get(buildingId);
        // If no settings at all, return empty — caller handles defaults
        if (perBuilding == null || perBuilding.isEmpty()) return Map.of();
        return Map.copyOf(perBuilding);
    }

    /** Returns the raw max-stock map for internal mutation. */
    Map<String, Integer> getOrCreateShopMaxStock(UUID buildingId) {
        return shopMaxStock.computeIfAbsent(buildingId, k -> new ConcurrentHashMap<>());
    }

    /**
     * Sets the max stock for a specific good in a shop. Clamped to 0–64.
     * Marks the data as dirty for persistence.
     */
    public void setShopMaxStock(UUID buildingId, String itemId, int newMax) {
        newMax = Math.clamp(newMax, 0, 64);
        Map<String, Integer> perBuilding = shopMaxStock.computeIfAbsent(
                buildingId, k -> new ConcurrentHashMap<>());
        perBuilding.put(itemId, newMax);
        setDirty();
    }

    /** Removes all stock data for a building (used when a shop building is removed). */
    public void removeShopData(UUID buildingId) {
        shopStock.remove(buildingId);
        shopMaxStock.remove(buildingId);
        setDirty();
    }

    /**
     * Record that a building transitioned to intact state.
     * Called by {@link BuildCompleteListener} after structure verification passes.
     */
    public boolean addBuildingContribution(UUID colonyId, String buildingTypeId) {
        if (contributionRegistry == null) {
            IEventBus bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
            contributionRegistry = new BuildingContributionRegistry(bus);
            contributionRegistry.setBuildSource(this::getAllBuildings);
            contributionRegistry.rebuildFrom(this::getAllBuildings);
        }
        boolean changed = contributionRegistry.recordIntactChange(colonyId, buildingTypeId, true);
        if (changed) setDirty();
        return changed;
    }

    /**
     * Record that a building transitioned away from intact state (damaged or destroyed).
     * Called by {@link BuildingBreakHandler}.
     */
    public boolean removeBuildingContribution(UUID colonyId, String buildingTypeId) {
        if (contributionRegistry == null) {
            IEventBus bus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
            contributionRegistry = new BuildingContributionRegistry(bus);
            contributionRegistry.setBuildSource(this::getAllBuildings);
            contributionRegistry.rebuildFrom(this::getAllBuildings);
        }
        boolean changed = contributionRegistry.recordIntactChange(colonyId, buildingTypeId, false);
        if (changed) setDirty();
        return changed;
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
