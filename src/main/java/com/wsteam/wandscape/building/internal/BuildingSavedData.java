package com.wsteam.wandscape.building.internal;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.data.MageHutResident;
import com.wsteam.wandscape.shared.data.ShopGoodDef;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.event.ColonyEvaluationChangedEvent;
import com.wsteam.wandscape.shared.log.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.IEventBus;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final String TAG = "BuildingSavedData";
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
    private static final String TAG_INTACT = "intact";
    private static final String TAG_EVER_COMPLETED = "ever_completed";
    private static final String TAG_CONSTRUCTION_STARTED = "construction_started";
    private static final String TAG_QUEUE = "queue";
    private static final String TAG_CURRENT_TASK = "current_task";
    private static final String TAG_COMFORT = "comfort";
    private static final String TAG_MAGIC = "magic";
    private static final String TAG_WONDER = "wonder";
    private static final String TAG_QUEUE_ITEM_BLUEPRINT = "blueprint";
    private static final String TAG_QUEUE_ITEM_PARAMS = "params_json";
    private static final String TAG_QUEUE_ITEM_PRIORITY = "priority";

    // NBT keys for shop inventory persistence
    private static final String TAG_SHOP_STOCK = "shop_stock";
    private static final String TAG_SHOP_MAX_STOCK = "shop_max_stock";

    // NBT key for pattern positions (precise overlap detection)
    private static final String TAG_PATTERN_POSITIONS = "pattern_pos";

    // NBT key for rotation steps
    private static final String TAG_ROTATION = "rotation";

    // NBT key for claimed first-free builds
    private static final String TAG_CLAIMED_FREE = "claimed_free";

    // NBT key for mage hut residents (buildingId → MageHutResident)
    private static final String TAG_MAGE_HUT_RESIDENTS = "mage_hut_residents";

    // NBT key for shared production queues (workstations by type, nodes by element)
    private static final String TAG_SHARED_QUEUES = "shared_queues";

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

    // ── First-free build tracking ──
    /** colonyId → set of buildingTypeIds whose first build was already claimed free. */
    private final Map<UUID, Set<String>> claimedFreeBuilds = new ConcurrentHashMap<>();

    // ── Mage hut residents ──
    /** buildingId → the single mage assigned to that mage hut (survives the mage's death). */
    private final Map<UUID, MageHutResident> mageHutResidents = new ConcurrentHashMap<>();

    // ── Shared production queues ──
    /**
     * A queue shared by all buildings of the same "(colony, groupKey)":
     * workstations share by {@code buildingTypeId}, element nodes by
     * {@code node_config.element()}. An idle member building claims the front
     * task (see {@link BuildingApiImpl#dequeueWork}).
     */
    private final Map<SharedGroup, Deque<WorkItem>> sharedQueues = new ConcurrentHashMap<>();

    /** Identity of a shared queue: which colony + which groupKey (type/element). */
    public record SharedGroup(UUID colonyId, String groupKey) {}

    @Nullable
    public MageHutResident getMageHutResident(UUID buildingId) {
        return mageHutResidents.get(buildingId);
    }

    /** Set (or clear with null) the mage hut resident for a building. */
    public void setMageHutResident(UUID buildingId, @Nullable MageHutResident resident) {
        if (resident == null) {
            mageHutResidents.remove(buildingId);
        } else {
            mageHutResidents.put(buildingId, resident);
        }
        setDirty();
    }

    public void removeMageHutResident(UUID buildingId) {
        mageHutResidents.remove(buildingId);
        setDirty();
    }

    // ── Shared production queues ──

    /** Whether a building's category participates in a shared queue. */
    public static boolean isSharedQueueCategory(String category) {
        return SHARED_QUEUE_CATEGORIES.contains(category);
    }

    private static final Set<String> SHARED_QUEUE_CATEGORIES =
            Set.of("workstation", "crafting_station", "magic_station", "node");

    /**
     * The shared-queue group key for a building, or null if it doesn't share a queue.
     * Workstation-family buildings share by {@code buildingTypeId}; node buildings
     * share by their {@code node_config.element()} so all nodes of an element fan out.
     */
    @Nullable
    public static String groupKeyFor(BuildingState state) {
        if (state == null || !isSharedQueueCategory(state.getCategory())) return null;
        if ("node".equals(state.getCategory())) {
            BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
            return config != null && config.nodeConfig() != null
                    ? config.nodeConfig().element() : null;
        }
        return state.getBuildingTypeId();
    }

    /** Shared queue for a group, created lazily. Returned queue is mutated by callers. */
    public Deque<WorkItem> sharedQueue(UUID colonyId, String groupKey) {
        return sharedQueues.computeIfAbsent(new SharedGroup(colonyId, groupKey), k -> new ArrayDeque<>());
    }

    /** Shared queue for a group only if one already exists, else null (no side effects). */
    @Nullable
    public Deque<WorkItem> peekSharedQueue(UUID colonyId, String groupKey) {
        return sharedQueues.get(new SharedGroup(colonyId, groupKey));
    }

    /** Whether a group has at least one queued (not yet claimed) task. */
    public boolean hasSharedWork(UUID colonyId, String groupKey) {
        Deque<WorkItem> q = sharedQueues.get(new SharedGroup(colonyId, groupKey));
        return q != null && !q.isEmpty();
    }

    /**
     * All buildings belonging to the given shared group (workstations of a type,
     * or nodes of an element). Used to aggregate running tasks for the panel.
     */
    public List<BuildingState> groupMembers(UUID colonyId, String groupKey) {
        List<BuildingState> result = new ArrayList<>();
        for (BuildingState state : buildings.values()) {
            if (state.getColonyId() == null || !colonyId.equals(state.getColonyId())) continue;
            if (!groupKey.equals(groupKeyFor(state))) continue;
            result.add(state);
        }
        return result;
    }

    /** NBT has no float array — store as int bits. */
    private static int[] floatBits(float[] values) {
        int[] bits = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            bits[i] = Float.floatToIntBits(values[i]);
        }
        return bits;
    }

    private static float[] floatFromBits(int[] bits) {
        float[] values = new float[bits.length];
        for (int i = 0; i < bits.length; i++) {
            values[i] = Float.intBitsToFloat(bits[i]);
        }
        return values;
    }

    public boolean isFirstFreeClaimed(UUID colonyId, String buildingTypeId) {
        Set<String> claimed = claimedFreeBuilds.get(colonyId);
        return claimed != null && claimed.contains(buildingTypeId);
    }

    public void claimFirstFree(UUID colonyId, String buildingTypeId) {
        claimedFreeBuilds.computeIfAbsent(colonyId, k -> ConcurrentHashMap.newKeySet())
                .add(buildingTypeId);
        setDirty();
    }

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
        // 1. Fast path: posIndex (populated from config.pattern() on register())
        UUID id = posIndex.get(pos);
        if (id != null) {
            BuildingState state = buildings.get(id);
            if (state != null) return state;
        }

        // 2. Fallback: chunkIndex + bounding box containment.
        // Needed after server restart (posIndex not persisted) or when
        // raycast hits a block inside the bounding box that isn't in the
        // pattern list.
        UUID fallbackId = getBuildingIdAt(pos);
        if (fallbackId != null) {
            BuildingState state = buildings.get(fallbackId);
            return state;
        }

        return null;
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
     * Finds a building whose boundary box covers the given position.
     * Clicking inside any building's bounding box (not just on pattern blocks)
     * counts as interacting with that building.
     *
     * @return buildingId if pos is within boundary of an intact building
     */
    @Nullable
    public UUID getBuildingIdInInteractionZone(BlockPos pos) {
        ChunkPos cp = new ChunkPos(pos);
        Set<UUID> chunkIds = chunkIndex.get(cp);
        if (chunkIds == null) return null;

        for (UUID candidate : chunkIds) {
            BuildingState state = buildings.get(candidate);
            if (state == null || !state.isStructureIntact()) continue;

            if (state.getBounds().isInside(pos)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Computes the tourist interaction target position for tourist AI:
     * 第一个 interact spot 的世界坐标（anchor + 旋转偏移）。
     * 0-spot 建筑对游客无效（无 spiral-scan 兜底）→ 返回 null。
     *
     * @param buildingId the building to target
     * @param level      the world level (for block-state queries)
     * @return a walkable BlockPos inside the bounding box, or the anchor as fallback
     */
    @Nullable
    public BlockPos getTouristInteractionTarget(UUID buildingId, Level level) {
        return getTouristInteractPoint(buildingId, level);
    }

    /**
     * Computes the precise tourist interaction position: 第一个 interact spot 的世界坐标
     * （anchor + 旋转偏移）。寻路目标 = 一个 spot 点。
     * 0-spot 建筑对游客无效（无 spiral-scan 兜底，用户拍板）→ 返回 null。
     */
    @Nullable
    public BlockPos getTouristInteractPoint(UUID buildingId, Level level) {
        BuildingState state = buildings.get(buildingId);
        if (state == null || !state.isStructureIntact()) return null;

        BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        if (config == null || config.interactSpots() == null || config.interactSpots().isEmpty()) {
            return null;
        }
        BuildingConfig.InteractSpot spot = config.interactSpots().get(0);
        BlockOffset rotated = com.wsteam.wandscape.projection.BuildingRotation
                .rotateOffset(spot.pos(), state.getRotationSteps());
        return state.getAnchor().offset(rotated.x(), rotated.y(), rotated.z());
    }

    /**
     * Computes the entry point for tourists to enter the building.
     * This is a walkable ground position OUTSIDE the building, suitable as
     * the macro-navigation destination before switching to indoor micro-navigation.
     *
     * <p>Uses {@code door_offsets} from building config if defined — each door's
     * world position is computed, then the adjacent outside walkable block is returned
     * (first walkable door wins). Otherwise falls back to heuristic spiral scan around
     * the outside of the bounding box.
     *
     * @param buildingId the building to enter
     * @param level      the world level (for block-state queries)
     * @return a walkable BlockPos outside the building, or the anchor as fallback
     */
    @Nullable
    public BlockPos getEntryPoint(UUID buildingId, Level level) {
        BuildingState state = buildings.get(buildingId);
        if (state == null || !state.isStructureIntact()) return null;

        BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        BoundingBox bounds = state.getBounds();
        BlockPos anchor = state.getAnchor();

        // 1. Use door_offsets if defined — first door with a walkable outside neighbor wins
        if (config != null && config.doorOffsets() != null && !config.doorOffsets().isEmpty()) {
            for (BlockOffset off : config.doorOffsets()) {
                BlockOffset rotated = com.wsteam.wandscape.projection.BuildingRotation
                        .rotateOffset(off, state.getRotationSteps());
                BlockPos doorWorld = anchor.offset(rotated.x(), rotated.y(), rotated.z());

                // Check all 4 horizontal neighbors; prefer one outside the building
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos candidate = doorWorld.relative(dir);
                    if (!bounds.isInside(candidate)) {
                        BlockPos ground = findGroundAt(candidate, level);
                        if (ground != null && !bounds.isInside(ground)) {
                            return ground;
                        }
                    }
                }
            }
            // If no outside neighbor is walkable, try any walkable neighbor of any door
            for (BlockOffset off : config.doorOffsets()) {
                BlockOffset rotated = com.wsteam.wandscape.projection.BuildingRotation
                        .rotateOffset(off, state.getRotationSteps());
                BlockPos doorWorld = anchor.offset(rotated.x(), rotated.y(), rotated.z());
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos candidate = doorWorld.relative(dir);
                    BlockPos ground = findGroundAt(candidate, level);
                    if (ground != null) return ground;
                }
            }
        }

        // 2. Fallback: heuristic spiral scan OUTSIDE bounding box (expanded by 1)
        BoundingBox expanded = new BoundingBox(
                bounds.minX() - 1, bounds.minY(), bounds.minZ() - 1,
                bounds.maxX() + 1, bounds.maxY(), bounds.maxZ() + 1);
        BlockPos outsideResult = spiralScanWalkableOutside(expanded, bounds, level);
        if (outsideResult != null) return outsideResult;

        return anchor;
    }

    /**
     * Spiral-scans for walkable ground in the outer shell of {@code expanded}
     * (positions in expanded but NOT in inner).
     */
    @Nullable
    private BlockPos spiralScanWalkableOutside(BoundingBox expanded, BoundingBox inner, Level level) {
        int bx = expanded.maxX() - expanded.minX();
        int bz = expanded.maxZ() - expanded.minZ();
        if (bx < 1) bx = 1;
        if (bz < 1) bz = 1;

        int cx = (expanded.minX() + expanded.maxX()) / 2;
        int cz = (expanded.minZ() + expanded.maxZ()) / 2;
        int maxR = Math.max(bx, bz) + 1;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();

        for (int r = 0; r <= maxR; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    int x = cx + dx;
                    int z = cz + dz;
                    // Must be in expanded but NOT in inner
                    if (inner.isInside(new BlockPos(x, inner.minY(), z))) continue;
                    if (x < expanded.minX() || x > expanded.maxX()
                            || z < expanded.minZ() || z > expanded.maxZ()) continue;

                    for (int y = expanded.maxY(); y >= expanded.minY(); y--) {
                        mp.set(x, y, z);
                        if (level.getBlockState(mp).isAir()
                                && level.getBlockState(mp.below()).isSolid()) {
                            return mp.immutable();
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Find walkable ground at or near the given position (air above solid). */
    @Nullable
    private static BlockPos findGroundAt(BlockPos pos, Level level) {
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        int topY = Math.min(level.getMaxBuildHeight() - 1, pos.getY() + 3);
        mp.set(pos.getX(), topY, pos.getZ());
        while (mp.getY() > level.getMinBuildHeight()) {
            if (level.getBlockState(mp).isAir()
                    && level.getBlockState(mp.below()).isSolid()) {
                return mp.immutable();
            }
            mp.move(0, -1, 0);
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
     * Register a new building. Builds all indexes, checks overlap using
     * precise pattern positions.
     *
     * @throws BuildingOverlapException if the building overlaps an existing one
     */
    public void register(BuildingState state, BuildingConfig config) {
        // Apply rotation to pattern
        java.util.List<BlockOffset> pattern = com.wsteam.wandscape.projection.BuildingRotation
                .rotateOffsets(config.pattern(), state.getRotationSteps());

        // Build pattern positions from rotated pattern
        Set<BlockPos> newPattern = new HashSet<>();
        for (BlockOffset off : pattern) {
            newPattern.add(state.getAnchor().offset(off.x(), off.y(), off.z()));
        }
        state.setPatternPositions(Collections.unmodifiableSet(newPattern));

        // Precise overlap check: pattern vs pattern (or AABB fallback for legacy)
        for (BuildingState existing : buildings.values()) {
            // A building being demolished no longer occupies the space — don't block placement.
            if (existing.isDemolishing()) continue;
            if (overlapsPattern(newPattern, existing)) {
                throw new BuildingOverlapException(
                        "Building " + state.getBuildingTypeId() + " at " + state.getAnchor()
                        + " overlaps with " + existing.getBuildingTypeId()
                        + " at " + existing.getAnchor());
            }
        }

        buildings.put(state.getBuildingId(), state);

        // Build posIndex from rotated pattern
        for (BlockOffset off : pattern) {
            BlockPos worldPos = state.getAnchor().offset(off.x(), off.y(), off.z());
            posIndex.put(worldPos, state.getBuildingId());
        }

        // Build chunkIndex from bounding box
        state.getBounds().intersectingChunks().forEach(cp -> {
            chunkIndex.computeIfAbsent(cp, k -> ConcurrentHashMap.newKeySet())
                    .add(state.getBuildingId());
        });

        setDirty();
    }

    /**
     * Check whether a set of world positions overlaps an existing building's
     * occupied blocks. Uses pattern positions for precise detection.
     */
    private static boolean overlapsPattern(Set<BlockPos> newPattern, BuildingState existing) {
        Set<BlockPos> existingPattern = existing.getPatternPositions();
        // Iterate the smaller set for efficiency
        if (newPattern.size() <= existingPattern.size()) {
            for (BlockPos pos : newPattern) {
                if (existingPattern.contains(pos)) return true;
            }
        } else {
            for (BlockPos pos : existingPattern) {
                if (newPattern.contains(pos)) return true;
            }
        }
        return false;
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
            entry.putBoolean(TAG_INTACT, state.isStructureIntact());
            entry.putBoolean(TAG_EVER_COMPLETED, state.hasEverCompleted());
            entry.putBoolean(TAG_CONSTRUCTION_STARTED, state.isConstructionStarted());
            entry.putInt(TAG_COMFORT, state.getComfort());
            entry.putInt(TAG_MAGIC, state.getMagic());
            entry.putInt(TAG_WONDER, state.getWonder());
            entry.putInt(TAG_ROTATION, state.getRotationSteps());
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

            // Pattern positions (for precise overlap detection)
            Set<BlockPos> patternPos = state.getPatternPositions();
            if (patternPos != null && !patternPos.isEmpty()) {
                long[] arr = patternPos.stream().mapToLong(BlockPos::asLong).toArray();
                entry.putLongArray(TAG_PATTERN_POSITIONS, arr);
            }

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

        // ── First-free builds ──
        CompoundTag freeTag = new CompoundTag();
        for (var entry : claimedFreeBuilds.entrySet()) {
            ListTag typesTag = new ListTag();
            for (String type : entry.getValue()) {
                typesTag.add(net.minecraft.nbt.StringTag.valueOf(type));
            }
            freeTag.put(entry.getKey().toString(), typesTag);
        }
        tag.put(TAG_CLAIMED_FREE, freeTag);

        // ── Mage hut residents ──
        CompoundTag hutTag = new CompoundTag();
        for (var entry : mageHutResidents.entrySet()) {
            MageHutResident r = entry.getValue();
            CompoundTag residentTag = new CompoundTag();
            if (r.npcId() != null) {
                residentTag.putUUID("npc_id", r.npcId());
            }
            residentTag.putUUID("colony_id", r.colonyId());
            residentTag.putString("name", r.mageName());
            residentTag.putInt("level", r.level());
            residentTag.putIntArray("base", floatBits(r.base()));
            hutTag.put(entry.getKey().toString(), residentTag);
        }
        tag.put(TAG_MAGE_HUT_RESIDENTS, hutTag);

        // ── Shared production queues ──
        ListTag sqTag = new ListTag();
        for (var entry : sharedQueues.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            SharedGroup grp = entry.getKey();
            CompoundTag grpTag = new CompoundTag();
            grpTag.putUUID("colony", grp.colonyId());
            grpTag.putString("group_key", grp.groupKey());
            ListTag itemsTag = new ListTag();
            for (WorkItem item : entry.getValue()) {
                itemsTag.add(workItemToTag(item));
            }
            grpTag.put("items", itemsTag);
            sqTag.add(grpTag);
        }
        if (!sqTag.isEmpty()) tag.put(TAG_SHARED_QUEUES, sqTag);

        return tag;
    }

    /** Serialize a single WorkItem to a CompoundTag (shared by building + shared queues). */
    private static CompoundTag workItemToTag(WorkItem item) {
        CompoundTag itemTag = new CompoundTag();
        itemTag.putString(TAG_QUEUE_ITEM_BLUEPRINT, item.blueprintId());
        itemTag.putInt(TAG_QUEUE_ITEM_PRIORITY, item.priority());
        itemTag.putString(TAG_QUEUE_ITEM_PARAMS, PARAMS_GSON.toJson(item.params()));
        return itemTag;
    }

    /** Deserialize a WorkItem from a CompoundTag, or null on malformed data. */
    @Nullable
    private static WorkItem workItemFromTag(CompoundTag itemTag) {
        String blueprint = itemTag.getString(TAG_QUEUE_ITEM_BLUEPRINT);
        if (blueprint.isEmpty()) return null;
        int priority = itemTag.getInt(TAG_QUEUE_ITEM_PRIORITY);
        Map<String, JsonElement> params = Collections.emptyMap();
        if (itemTag.contains(TAG_QUEUE_ITEM_PARAMS)) {
            String json = itemTag.getString(TAG_QUEUE_ITEM_PARAMS);
            params = PARAMS_GSON.fromJson(json, PARAMS_TYPE);
            if (params == null) params = Collections.emptyMap();
        }
        return new WorkItem(blueprint, params, priority);
    }

    private static BuildingSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        BuildingSavedData data = new BuildingSavedData();

        ListTag list = tag.getList(TAG_BUILDINGS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);

            UUID id = entry.getUUID(TAG_ID);
            String type = entry.getString(TAG_TYPE);
            // category 是建筑类型的派生属性：从当前 BuildingConfig 重取，使类别改名
            //（如 potion_station → magic_station）能自动迁移旧存档；类型已移除时回退存档值。
            BuildingConfig typeConfig = BuildingConfigLoader.getInstance().get(type);
            String category = typeConfig != null ? typeConfig.category() : entry.getString(TAG_CATEGORY);

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
            int rotationSteps = entry.getInt(TAG_ROTATION);

            BuildingState state = new BuildingState(id, type, category, anchor, bounds,
                    comfort, magic, wonder);
            state.setRotationSteps(rotationSteps);

            if (entry.hasUUID(TAG_COLONY)) {
                state.setColonyId(entry.getUUID(TAG_COLONY));
            }
            state.setStructureIntact(entry.getBoolean(TAG_INTACT));
            // Migration: older saves lack the flag; a currently-intact building
            // was necessarily built, so infer it completed construction.
            state.setHasEverCompleted(entry.contains(TAG_EVER_COMPLETED)
                    ? entry.getBoolean(TAG_EVER_COMPLETED)
                    : state.isStructureIntact());
            state.setConstructionStarted(entry.getBoolean(TAG_CONSTRUCTION_STARTED));
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

            // 维护费已删除：旧存档中"maintenance" 字段会被忽略，建筑不再可能因维护费停摆。
            // 旧存档残留的 shutdown 位一律忽略（建筑照常运转）。

            // Pattern positions (precise overlap detection)
            if (entry.contains(TAG_PATTERN_POSITIONS)) {
                long[] arr = entry.getLongArray(TAG_PATTERN_POSITIONS);
                if (arr != null && arr.length > 0) {
                    Set<BlockPos> positions = new HashSet<>(arr.length);
                    for (long l : arr) {
                        positions.add(BlockPos.of(l));
                    }
                    state.setPatternPositions(Collections.unmodifiableSet(positions));
                }
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

        Log.info(TAG, "Loaded {} buildings from saved data", data.buildings.size());

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
                    Log.warn(TAG, "Invalid building UUID in shop stock: {}", key);
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
                    Log.warn(TAG, "Invalid building UUID in shop max stock: {}", key);
                }
            }
        }

        // ── Load claimed first-free builds ──
        if (tag.contains(TAG_CLAIMED_FREE)) {
            CompoundTag freeTag = tag.getCompound(TAG_CLAIMED_FREE);
            for (String key : freeTag.getAllKeys()) {
                try {
                    UUID colonyId = UUID.fromString(key);
                    ListTag typesTag = freeTag.getList(key, Tag.TAG_STRING);
                    Set<String> types = ConcurrentHashMap.newKeySet();
                    for (int i = 0; i < typesTag.size(); i++) {
                        types.add(typesTag.getString(i));
                    }
                    data.claimedFreeBuilds.put(colonyId, types);
                } catch (IllegalArgumentException e) {
                    Log.warn(TAG, "Invalid colony UUID in claimed free builds: {}", key);
                }
            }
        }

        // ── Load mage hut residents ──
        if (tag.contains(TAG_MAGE_HUT_RESIDENTS)) {
            CompoundTag hutTag = tag.getCompound(TAG_MAGE_HUT_RESIDENTS);
            for (String key : hutTag.getAllKeys()) {
                try {
                    UUID buildingId = UUID.fromString(key);
                    CompoundTag rt = hutTag.getCompound(key);
                    UUID npcId = rt.hasUUID("npc_id") ? rt.getUUID("npc_id") : null;
                    UUID colonyId = rt.hasUUID("colony_id") ? rt.getUUID("colony_id") : new UUID(0, 0);
                    String name = rt.getString("name");
                    int level = rt.getInt("level");
                    float[] base = floatFromBits(rt.getIntArray("base"));
                    data.mageHutResidents.put(buildingId,
                            new MageHutResident(npcId, colonyId, name, level, base));
                } catch (IllegalArgumentException e) {
                    Log.warn(TAG, "Invalid building UUID in mage hut residents: {}", key);
                }
            }
        }

        // ── Load shared production queues ──
        if (tag.contains(TAG_SHARED_QUEUES)) {
            ListTag sqTag = tag.getList(TAG_SHARED_QUEUES, Tag.TAG_COMPOUND);
            for (int i = 0; i < sqTag.size(); i++) {
                CompoundTag grpTag = sqTag.getCompound(i);
                try {
                    UUID colonyId = grpTag.getUUID("colony");
                    String groupKey = grpTag.getString("group_key");
                    Deque<WorkItem> queue = data.sharedQueues.computeIfAbsent(
                            new SharedGroup(colonyId, groupKey), k -> new ArrayDeque<>());
                    ListTag itemsTag = grpTag.getList("items", Tag.TAG_COMPOUND);
                    for (int j = 0; j < itemsTag.size(); j++) {
                        WorkItem item = workItemFromTag(itemsTag.getCompound(j));
                        if (item != null) queue.addLast(item);
                    }
                } catch (IllegalArgumentException e) {
                    Log.warn(TAG, "Invalid shared queue entry: {}", e.getMessage());
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
        java.util.List<BlockOffset> pattern = com.wsteam.wandscape.projection.BuildingRotation
                .rotateOffsets(config.pattern(), state.getRotationSteps());
        for (BlockOffset off : pattern) {
            BlockPos worldPos = state.getAnchor().offset(off.x(), off.y(), off.z());
            posIndex.put(worldPos, state.getBuildingId());
        }
    }

    // ── Contribution tracking ────────────────────────────────────────────────

    /**
     * Returns the {@link BuildingContributionRegistry} owned by this data store.
     * Lazily initialises if accessed before any building transitions (e.g. fresh world).
     */
    public BuildingContributionRegistry getContributionRegistry() {
        if (contributionRegistry == null) {
            contributionRegistry = new BuildingContributionRegistry(
                    net.neoforged.neoforge.common.NeoForge.EVENT_BUS);
            contributionRegistry.setBuildSource(this::getAllBuildings);
            contributionRegistry.rebuildFrom(this::getAllBuildings);
        }
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
     * Record that a building transitioned away from intact state (removed / demolished).
     * Called by {@link BuildingApiImpl#unregisterState} when a building is removed.
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

    /**
     * Check if a world position is occupied by any building that is NOT the
     * excluded one. Uses pattern positions for precise detection.
     *
     * @param pos              world position to check
     * @param excludeBuildingId building to skip (the one being placed)
     * @return true if occupied by another building
     */
    public boolean isPositionOccupiedByOtherBuilding(BlockPos pos, UUID excludeBuildingId) {
        for (BuildingState existing : buildings.values()) {
            if (existing.getBuildingId().equals(excludeBuildingId)) continue;
            if (existing.getPatternPositions().contains(pos)) return true;
        }
        return false;
    }
}
