package com.wsteam.wandscape.content.building.internal;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.component.NpcInventory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.wsteam.wandscape.content.building.data.BlockOffset;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.projection.BuildingRotation;
import com.wsteam.wandscape.content.npc.data.MageHutResident;
import com.wsteam.wandscape.content.building.data.ShopGoodDef;
import com.wsteam.wandscape.content.building.data.WorkItem;
import com.wsteam.wandscape.content.colony.event.ColonyEvaluationChangedEvent;
import com.wsteam.wandscape.foundation.log.Log;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private static final String TAG_AUTO_SUPPLY_DONE = "auto_supply_done";
    private static final String TAG_CHARGED_MATERIALS = "charged_materials";
    private static final String TAG_QUEUE = "queue";
    private static final String TAG_CURRENT_TASK = "current_task";
    private static final String TAG_COMFORT = "comfort";
    private static final String TAG_MAGIC = "magic";
    private static final String TAG_WONDER = "wonder";
    private static final String TAG_QUEUE_ITEM_BLUEPRINT = "blueprint";
    private static final String TAG_QUEUE_ITEM_PARAMS = "params_json";
    private static final String TAG_QUEUE_ITEM_PRIORITY = "priority";
    /** 超长参数（> {@link #MAX_PARAM_JSON_BYTES}）的 gzip 落点，与 {@code TaskPoolSavedData} 同名同义。 */
    private static final String TAG_PARAMS_C = "params_c";
    /**
     * 参数 JSON 超这个 UTF-8 字节数就 gzip。**不能按 {@code String.length()} 判断**——
     * 触发条件是 NBT 的 UTF 字节长度上限 65535，中文/代理对下字符数与字节数不等。
     */
    private static final int MAX_PARAM_JSON_BYTES = 60000;

    // NBT keys for shop inventory persistence
    private static final String TAG_SHOP_STOCK = "shop_stock";
    private static final String TAG_SHOP_MAX_STOCK = "shop_max_stock";

    // NBT key for rotation steps
    private static final String TAG_ROTATION = "rotation";

    // NBT key for claimed first-free builds
    private static final String TAG_CLAIMED_FREE = "claimed_free";

    // NBT key for mage hut residents (buildingId → MageHutResident)
    private static final String TAG_MAGE_HUT_RESIDENTS = "mage_hut_residents";

    // NBT key for the delegated mage of a building (buildingId → NPC UUID)
    private static final String TAG_DELEGATED_MAGE = "delegated_mage";

    // NBT key for shared production queues (workstations by type, nodes by element)
    private static final String TAG_SHARED_QUEUES = "shared_queues";

    /** 逐条参数用 {@code JsonElement} 直接读写（{@link #writeParams} / {@link #readParams}），不再需要整包 TypeToken。 */
    private static final Gson PARAMS_GSON = new Gson();

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

    // ── Building delegation（建筑委派）──
    /**
     * mageUuid → buildingId 反查索引：调度器每拍要按法师问「你被委派到哪座建筑」，
     * 遍历全部建筑是 O(建筑数)；本表只做 O(1) 反查。{@link #buildings} 里的
     * {@link BuildingState#getDelegatedMage()} 仍是唯一真源，三处同步：
     * {@link #setDelegatedMage}、{@link #unregister}、{@link #rebuildIndexes}。
     */
    private final Map<UUID, UUID> delegationByMage = new ConcurrentHashMap<>();

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

    // ── Building delegation（建筑委派）──

    /**
     * 记录（或清除）一座建筑的被委派法师，并同步反查索引。
     *
     * <p>只写事实、不做合法性判断——类别白名单、法师在世/同镇等规则在
     * {@link BuildingDelegation} 一处判定。这里同时摘掉「该法师原先委派到别处」的旧条目，
     * 保证「一名法师只服务一座建筑」这条不变式在存储层就成立。
     */
    public void setDelegatedMage(UUID buildingId, @Nullable UUID mageUuid) {
        BuildingState state = buildings.get(buildingId);
        if (state == null) return;
        UUID previous = state.getDelegatedMage();
        if (java.util.Objects.equals(previous, mageUuid)) return;

        if (previous != null) {
            delegationByMage.remove(previous, buildingId);
        }
        state.setDelegatedMage(mageUuid);
        if (mageUuid != null) {
            // 该法师若已在别的建筑名下，先摘掉那一条（一个法师只有一座建筑）
            UUID oldBuilding = delegationByMage.put(mageUuid, buildingId);
            if (oldBuilding != null && !oldBuilding.equals(buildingId)) {
                BuildingState old = buildings.get(oldBuilding);
                if (old != null) old.setDelegatedMage(null);
            }
        }
        setDirty();
    }

    /** 该建筑被委派给的法师（NPC UUID）；未委派返回 null。 */
    @Nullable
    public UUID getDelegatedMage(UUID buildingId) {
        BuildingState state = buildings.get(buildingId);
        return state != null ? state.getDelegatedMage() : null;
    }

    /** 该法师被委派到的建筑；未委派返回 null。 */
    @Nullable
    public UUID getDelegatedBuildingOfMage(UUID mageUuid) {
        return mageUuid != null ? delegationByMage.get(mageUuid) : null;
    }

    /**
     * 法师不再存在（阵亡/解散）时摘掉其委派，返回被解约的建筑 id（本就没委派返回 null）。
     * 调用方据此让那座建筑退回「谁都能接」的常规调度，不留一座只等死人的空岗。
     */
    @Nullable
    public UUID clearDelegationOfMage(UUID mageUuid) {
        if (mageUuid == null) return null;
        UUID buildingId = delegationByMage.remove(mageUuid);
        if (buildingId == null) return null;
        BuildingState state = buildings.get(buildingId);
        if (state != null && mageUuid.equals(state.getDelegatedMage())) {
            state.setDelegatedMage(null);
        }
        setDirty();
        return buildingId;
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
     * Item Workshop-family buildings share by {@code buildingTypeId}; node buildings
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

    /**
     * Shared queue for a group only if it currently holds **待领** work, else null.
     *
     * <p>空组队列按「不存在」处理并顺手摘掉条目：{@link #save} 对空队列直接 {@code continue}
     * （不落盘），所以空条目是纯运行时残留。若让它继续参与「共享队列优先于自有队列」的判定，
     * 就会造出「本次会话看不到、重进世界才看得到」的状态分歧——拆除/复原任务被空组队列遮蔽、
     * 永不发布正是由此而来。摘掉后运行时状态与存档态一致。
     */
    @Nullable
    public Deque<WorkItem> peekSharedQueue(UUID colonyId, String groupKey) {
        SharedGroup key = new SharedGroup(colonyId, groupKey);
        Deque<WorkItem> queue = sharedQueues.get(key);
        if (queue == null) return null;
        if (queue.isEmpty()) {
            sharedQueues.remove(key, queue);
            return null;
        }
        return queue;
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

        // Fallback: posIndex 已在读档时按建筑 JSON 现算填满，所以这条路只在「准心落在某栋的
        // 包围盒内、但不在它的 pattern 格上」时才走。优先认精确的 pattern 格主人；
        // 否则认最内层的包围盒，让嵌套建筑里的位置有确定归属。
        ChunkPos cp = new ChunkPos(pos);
        Set<UUID> chunkIds = chunkIndex.get(cp);
        if (chunkIds == null) return null;

        UUID boundsMatch = null;
        long bestVolume = Long.MAX_VALUE;
        for (UUID candidate : chunkIds) {
            BuildingState state = buildings.get(candidate);
            if (state == null) continue;
            Set<BlockPos> pattern = materializePatternPositions(state);
            if (pattern.contains(pos)) {
                // Cache in posIndex for next lookup
                posIndex.put(pos, candidate);
                return candidate;
            }
            if (state.getBounds().isInside(pos)) {
                long volume = boxVolume(state.getBounds());
                if (volume < bestVolume) {
                    bestVolume = volume;
                    boundsMatch = candidate;
                }
            }
        }
        if (boundsMatch != null) {
            posIndex.put(pos, boundsMatch);
            return boundsMatch;
        }
        return null;
    }

    /**
     * Finds a building whose boundary box covers the given position.
     * Clicking inside any building's bounding box (not just on pattern blocks)
     * counts as interacting with that building.
     *
     * <p>When multiple intact buildings' boxes cover the position (nested /
     * overlapping buildings are allowed), the <b>innermost</b> (smallest) box
     * wins, so clicking inside a room inside a larger shell targets the room.
     *
     * @return buildingId if pos is within boundary of an intact building
     */
    @Nullable
    public UUID getBuildingIdInInteractionZone(BlockPos pos) {
        ChunkPos cp = new ChunkPos(pos);
        Set<UUID> chunkIds = chunkIndex.get(cp);
        if (chunkIds == null) return null;

        UUID best = null;
        long bestVolume = Long.MAX_VALUE;
        for (UUID candidate : chunkIds) {
            BuildingState state = buildings.get(candidate);
            if (state == null || !state.isStructureIntact()) continue;

            if (state.getBounds().isInside(pos)) {
                long volume = boxVolume(state.getBounds());
                if (volume < bestVolume) {
                    bestVolume = volume;
                    best = candidate;
                }
            }
        }
        return best;
    }

    /** Inclusive volume of a bounding box (counted in voxels). */
    private static long boxVolume(BoundingBox b) {
        return (long) (b.maxX() - b.minX() + 1)
                * (b.maxY() - b.minY() + 1)
                * (b.maxZ() - b.minZ() + 1);
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
        BlockOffset rotated = BuildingRotation
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
                BlockOffset rotated = BuildingRotation
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
                BlockOffset rotated = BuildingRotation
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
     * Register a new building. Builds all indexes and reserves the building's
     * occupied voxels. Two-phase overlap check: a world voxel may belong to at
     * most one building, but bounding boxes may overlap freely.
     *
     * @throws BuildingOverlapException if the building shares an occupied voxel
     *                                  with an existing one
     */
    public void register(BuildingState state, BuildingConfig config) {
        BlockPos anchor = state.getAnchor();
        // Occupied voxels of the rotated building + their broad-phase AABB.
        BuildingVoxels.Occupancy occupancy =
                BuildingVoxels.compute(config, anchor, state.getRotationSteps());
        state.setPatternPositions(occupancy.positions());
        state.setPatternExtent(occupancy.extent());

        // Two-phase overlap gate (shared logic in BuildingVoxels): reject a
        // building whose occupied voxels collide with an existing one — bounding
        // boxes may overlap freely, a world voxel may not be shared.
        for (BuildingState existing : buildings.values()) {
            // A building being demolished no longer occupies the space — don't block placement.
            if (existing.isDemolishing()) continue;
            if (conflictsWith(occupancy, existing, this)) {
                throw new BuildingOverlapException(
                        "Building " + state.getBuildingTypeId() + " at " + anchor
                        + " overlaps with " + existing.getBuildingTypeId()
                        + " at " + existing.getAnchor());
            }
        }

        buildings.put(state.getBuildingId(), state);

        // Build posIndex from rotated pattern
        for (BlockPos worldPos : occupancy.positions()) {
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
     * Whether the new building's occupied voxels collide with an existing one
     * (BuildingVoxels two-phase test).
     *
     * <p>占地格现算自建筑 JSON（见 {@link #materializePatternPositions}），不再是「存档里有没有
     * 存过 pattern」决定的：查不到 type 的作废建筑会被算成空集——它确实不再占任何世界格，
     * 所以不该像过去那样退回「整盒保守占用」去挡住邻栋建造。
     */
    private static boolean conflictsWith(BuildingVoxels.Occupancy mine, BuildingState existing, BuildingSavedData owner) {
        Set<BlockPos> existingPattern = owner.materializePatternPositions(existing);
        if (existingPattern.isEmpty() || mine.isEmpty()) return false;
        return BuildingVoxels.overlaps(mine.positions(), mine.extent(), existingPattern, existing.getPatternExtent());
    }

    /**
     * Remove a building and clean up all indexes.
     * @return the removed BuildingState, or null if not found
     */
    @Nullable
    public BuildingState unregister(UUID buildingId) {
        BuildingState state = buildings.remove(buildingId);
        if (state == null) return null;

        // 建筑没了，它的委派也随之消失：反查索引必须一起摘，否则那座建筑再被
        // 建回来（同 id 不会复用，但索引会留垃圾）前，那个法师会一直"被占着"
        if (state.getDelegatedMage() != null) {
            delegationByMage.remove(state.getDelegatedMage(), buildingId);
        }

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
            entry.putBoolean(TAG_AUTO_SUPPLY_DONE, state.isAutoSupplyDone());
            // 已扣建材账本（撤销建造的退还上限）
            Map<String, Integer> charged = state.getChargedMaterials();
            if (!charged.isEmpty()) {
                CompoundTag chargedTag = new CompoundTag();
                for (var e : charged.entrySet()) {
                    chargedTag.putInt(e.getKey(), e.getValue());
                }
                entry.put(TAG_CHARGED_MATERIALS, chargedTag);
            }
            entry.putInt(TAG_COMFORT, state.getComfort());
            entry.putInt(TAG_MAGIC, state.getMagic());
            entry.putInt(TAG_WONDER, state.getWonder());
            entry.putInt(TAG_ROTATION, state.getRotationSteps());
            if (state.getCurrentTaskId() != null) {
                entry.putUUID(TAG_CURRENT_TASK, state.getCurrentTaskId());
            }
            // 委派法师（未委派不写键：旧档缺键即"无委派"，与旧行为一致）
            if (state.getDelegatedMage() != null) {
                entry.putUUID(TAG_DELEGATED_MAGE, state.getDelegatedMage());
            }

            // Task queue
            ListTag queueTag = new ListTag();
            for (WorkItem item : state.getTaskQueue()) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putString(TAG_QUEUE_ITEM_BLUEPRINT, item.blueprintId());
                itemTag.putInt(TAG_QUEUE_ITEM_PRIORITY, item.priority());
                writeParams(itemTag, item.params());
                queueTag.add(itemTag);
            }
            entry.put(TAG_QUEUE, queueTag);

            // 维护费已删除：旧存档中"maintenance" 字段会被忽略，建筑不再可能因维护费停摆。
            // 旧存档残留的 shutdown 位一律忽略（建筑照常运转）。

            // 占地格（pattern_pos）**刻意不落盘**：pattern 的真源是建筑 JSON，把整份世界坐标
            // 抄进存档既冗余（magic_academy 一栋 3.5MB 未压缩），又会在数据包更新该建筑之后与
            // 真实样式分家。读档时由 materializePatternPositions 现算。

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
        writeParams(itemTag, item.params());
        return itemTag;
    }

    /**
     * 落盘一条 WorkItem 的参数：JSON 超 {@link #MAX_PARAM_JSON_BYTES} 就 gzip 成 byteArray，
     * 否则明文。口径与 {@code TaskPoolSavedData} 完全一致（同一阈值、同一个 {@code params} /
     * {@code params_c} 双键形态）——别再起第三套写法。
     *
     * <p><b>为什么必须压</b>：Minecraft 的 {@code StringTag.write} 走 {@code DataOutput.writeUTF}，
     * UTF 字节超 65535 会抛 {@code UTFDataFormatException}，而 NeoForge 的
     * {@code NbtIo.StringFallbackDataOutput.writeUTF} 会**抓住它并把整个字符串换成空串**
     * （{@code super.writeUTF("")}）。也就是说超长参数不会崩、不会报错，只是**静默消失**：
     * 读档后这条任务变成没有参数的哑弹。本模组的建造参数正好是这个量级
     * （magic_academy 的 {@code offsets} 5.3 MB、{@code blocks} 17.6 MB），
     * 只要任务在存档时刻还躺在队列里（无法师 / 缺料 / 队列满，窗口可以任意长）就会中招。
     */
    private static void writeParams(CompoundTag target, Map<String, JsonElement> paramsMap) {
        if (paramsMap == null || paramsMap.isEmpty()) return;
        CompoundTag params = new CompoundTag();
        CompoundTag paramsCompressed = new CompoundTag();
        for (var entry : paramsMap.entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null) continue;
            String json = value.toString();
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_PARAM_JSON_BYTES) {
                // gzip 失败不能抛：退回明文，让这条任务至少完整（哪怕冒 64KB 风险也比丢光强）。
                paramsCompressed.putByteArray(entry.getKey(), gzip(json));
            } else {
                params.putString(entry.getKey(), json);
            }
        }
        if (!params.isEmpty()) target.put(TAG_QUEUE_ITEM_PARAMS, params);
        if (!paramsCompressed.isEmpty()) target.put(TAG_PARAMS_C, paramsCompressed);
    }

    /** {@link #writeParams} 的读侧：先解 {@code params_c}（gzip byteArray），再读明文 {@code params}（后者覆盖）。 */
    private static Map<String, JsonElement> readParams(CompoundTag tag) {
        Map<String, JsonElement> params = new HashMap<>();
        CompoundTag compressed = tag.getCompound(TAG_PARAMS_C);
        for (String key : compressed.getAllKeys()) {
            byte[] data = compressed.getByteArray(key);
            String json;
            try {
                json = ungzip(data);
            } catch (IOException e) {
                // 解压失败说明这段字节坏了；退回当明文试一次，与 TaskPoolSavedData 同口径。
                Log.warn(TAG, "params_c['{}'] is not a valid gzip stream — trying as plain text", key);
                json = new String(data, StandardCharsets.UTF_8);
            }
            putParam(params, key, json);
        }
        CompoundTag plain = tag.getCompound(TAG_QUEUE_ITEM_PARAMS);
        for (String key : plain.getAllKeys()) {
            putParam(params, key, plain.getString(key));
        }
        return params;
    }

    /** 解一条参数 JSON 并放进 map；坏 JSON 不能连累整座建筑，退回空表并留痕。 */
    private static void putParam(Map<String, JsonElement> out, String key, String json) {
        try {
            JsonElement parsed = PARAMS_GSON.fromJson(json, JsonElement.class);
            out.put(key, parsed != null ? parsed : com.google.gson.JsonNull.INSTANCE);
        } catch (RuntimeException e) {
            Log.warn(TAG, "task param '{}' is not readable JSON, dropped: {}", key, e.getMessage());
        }
    }

    /** 与 {@code TaskPoolSavedData.gzip} 同口径（gzip 失败不能抛，退回明文）。 */
    private static byte[] gzip(String json) {
        try {
            var sink = new java.io.ByteArrayOutputStream(Math.max(64, json.length() / 4));
            try (var gz = new java.util.zip.GZIPOutputStream(sink)) {
                gz.write(json.getBytes(StandardCharsets.UTF_8));
            }
            return sink.toByteArray();
        } catch (IOException e) {
            Log.warn(TAG, "Failed to gzip task params ({} bytes) — storing raw: {}",
                    json.length(), e.getMessage());
            return json.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** 与 {@code TaskPoolSavedData.ungzip} 同口径。 */
    private static String ungzip(byte[] data) throws IOException {
        try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(data))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Deserialize a WorkItem from a CompoundTag, or null on malformed data. */
    @Nullable
    private static WorkItem workItemFromTag(CompoundTag itemTag) {
        String blueprint = itemTag.getString(TAG_QUEUE_ITEM_BLUEPRINT);
        if (blueprint.isEmpty()) return null;
        int priority = itemTag.getInt(TAG_QUEUE_ITEM_PRIORITY);
        return new WorkItem(blueprint, readParams(itemTag), priority);
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
            // 旧档没有这一位 → 视为没自动补过料：缺料时还能自动补一次，与旧行为一致。
            state.setAutoSupplyDone(entry.getBoolean(TAG_AUTO_SUPPLY_DONE));
            // 旧档没有这本账 → 空账本：撤销时不退建材（宁可少退，不可凭空造物）。
            if (entry.contains(TAG_CHARGED_MATERIALS)) {
                CompoundTag chargedTag = entry.getCompound(TAG_CHARGED_MATERIALS);
                Map<String, Integer> charged = new LinkedHashMap<>();
                for (String itemId : chargedTag.getAllKeys()) {
                    charged.put(itemId, chargedTag.getInt(itemId));
                }
                state.recordChargedMaterials(charged);
            }
            if (entry.hasUUID(TAG_CURRENT_TASK)) {
                state.setCurrentTaskId(entry.getUUID(TAG_CURRENT_TASK));
            }
            // 旧档没有这个键 → 未委派（与旧行为一致：谁都能接这座建筑的活）
            if (entry.hasUUID(TAG_DELEGATED_MAGE)) {
                state.setDelegatedMage(entry.getUUID(TAG_DELEGATED_MAGE));
            }

            // Task queue
            ListTag queueTag = entry.getList(TAG_QUEUE, Tag.TAG_COMPOUND);
            for (int j = 0; j < queueTag.size(); j++) {
                CompoundTag itemTag = queueTag.getCompound(j);
                WorkItem item = workItemFromTag(itemTag);
                // 参数读不出来（旧档被 64KB 静默截断成空串的残留、或损坏）就丢掉这一条，
                // 不能把一条没有参数的任务放进队列——那会在执行期变成哑弹或误动作。
                if (item == null) {
                    Log.warn(TAG, "Dropping unreadable queued task on building {} (blueprint '{}')",
                            id.toString().substring(0, 8), itemTag.getString(TAG_QUEUE_ITEM_BLUEPRINT));
                    continue;
                }
                state.getTaskQueue().addLast(item);
            }

            // 维护费已删除：旧存档中"maintenance" 字段会被忽略，建筑不再可能因维护费停摆。
            // 旧存档残留的 shutdown 位一律忽略（建筑照常运转）。

            // 占地格不再从存档读（旧档里的 pattern_pos 直接忽略，用户已定断档不迁移）——
            // 由 rebuildIndexes → materializePatternPositions 按建筑 JSON 现算。

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

    /**
     * Rebuild posIndex / chunkIndex / delegation for a single building (used during load).
     *
     * <p>占地格不再从存档读，而是**按需由建筑 JSON 推导**（{@link #materializePatternPositions}）：
     * pattern 的定义真源是建筑 JSON，把它整份世界坐标抄进存档既冗余，又会在数据包更新该建筑之后
     * 与真实样式分家——那种漂移比省几十毫秒难查得多。
     */
    private void rebuildIndexes(BuildingState state) {
        // ── 占地格 → posIndex（精确的「这一格属于哪栋」查询）──
        for (BlockPos pos : materializePatternPositions(state)) {
            posIndex.put(pos, state.getBuildingId());
        }
        // ── 包围盒 → chunkIndex ──
        state.getBounds().intersectingChunks().forEach(cp -> {
            chunkIndex.computeIfAbsent(cp, k -> ConcurrentHashMap.newKeySet())
                    .add(state.getBuildingId());
        });
        // ── 委派反查索引（读档/新登记时重建）。两座建筑在存档里同时指向一名法师是损坏态：
        // 这里以先来后到认一个（索引即派发口径），并把落败那座的字段一并清掉，
        // 免得出现「面板显示已委派某人、调度器却当它没委派」的分歧（见 BuildingDelegation）。
        if (state.getDelegatedMage() != null) {
            UUID mage = state.getDelegatedMage();
            UUID prev = delegationByMage.put(mage, state.getBuildingId());
            if (prev != null && !prev.equals(state.getBuildingId())) {
                BuildingState loser = buildings.get(prev);
                if (loser != null && mage.equals(loser.getDelegatedMage())) {
                    loser.setDelegatedMage(null);
                }
                Log.warn(TAG, "mage {} was delegated to two buildings ({} kept, {} cleared on load)",
                        mage.toString().substring(0, 8), state.getBuildingId().toString().substring(0, 8),
                        prev.toString().substring(0, 8));
            }
        }
    }

    /**
     * 一栋建筑在世界里实际占用的格子，按需从建筑 JSON 推导并缓存在 {@link BuildingState} 上。
     *
     * <p>这是「建筑 JSON 是样式唯一真源」的落点：存档只留 type/anchor/rotation，占地格一律现算。
     * 缓存是为了给 {@code conflictsWith} 复用——那栋 44.3 万格的建筑每建一次邻栋都要算一遍
     * 整条 pattern 的话，代价是 O(pattern) 的装箱与哈希，比读一次存档贵得多。
     *
     * <p>config 查不到（数据包删掉了这个 type）时**静默留半残状态是不行的**：一条警告 + 一个
     * 空集，让该建筑从此「不占用任何世界格」——这正是「作废」在这套数据模型里的准确含义，
     * 而不是给它套一个会粘住的 demolishing 位。后果是明确的、可诊断的：它不再参与占地冲突，
     * 也不能再派发建造（没有 pattern 可展开），但仍在世界里有身份，玩家能看见并自行拆除。
     *
     * @return 该建筑占用的世界格；type 已不在数据包里时为**空集**（并已告警）
     */
    private Set<BlockPos> materializePatternPositions(BuildingState state) {
        Set<BlockPos> cached = state.getPatternPositions();
        if (cached != null) return cached;

        String type = state.getBuildingTypeId();
        BuildingConfig config = BuildingConfigLoader.getInstance().get(type);
        if (config == null || config.pattern().isEmpty()) {
            Log.warn(TAG, "building {} has type '{}' which is no longer in the datapack —"
                            + " treated as defunct: occupies no world voxel from now on",
                    state.getBuildingId().toString().substring(0, 8), type);
            Set<BlockPos> empty = Collections.emptySet();
            state.setPatternPositions(empty);
            state.setPatternExtent(null);
            return empty;
        }

        // 走 BuildingVoxels 的旋转缓存（键 config.id()+rot），别自己调 BuildingRotation 绕开它。
        List<BlockOffset> offsets = BuildingVoxels.rotatedOffsets(config, state.getRotationSteps());
        BlockPos anchor = state.getAnchor();
        Set<BlockPos> positions = new HashSet<>(offsets.size());
        for (BlockOffset off : offsets) {
            positions.add(anchor.offset(off.x(), off.y(), off.z()));
        }
        Set<BlockPos> unmodifiable = Collections.unmodifiableSet(positions);
        state.setPatternPositions(unmodifiable);
        state.setPatternExtent(BuildingVoxels.boundingBoxOf(unmodifiable));
        return unmodifiable;
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
}
