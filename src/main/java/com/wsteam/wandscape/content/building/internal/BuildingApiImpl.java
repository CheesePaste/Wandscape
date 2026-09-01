package com.wsteam.wandscape.content.building.internal;
import com.wsteam.wandscape.impl.WandscapeEngine;
import com.wsteam.wandscape.content.colony.service.ChunkLoadManager;
import com.wsteam.wandscape.content.colony.ColonyApiImpl;
import com.wsteam.wandscape.content.warehouse.system.ResourceSupplySystem;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.ecs.World;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.content.building.data.BlockOffset;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.source.BuildingTaskSource;
import com.wsteam.wandscape.content.building.projection.BuildingRotation;
import com.wsteam.wandscape.api.BuildingApi;
import com.wsteam.wandscape.content.building.data.BuildingData;
import com.wsteam.wandscape.foundation.util.ItemKey;
import com.wsteam.wandscape.content.building.data.WorkItem;
import com.wsteam.wandscape.content.building.event.BuildingPlacedEvent;
import com.wsteam.wandscape.content.building.event.BuildingRemovedEvent;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.registry.WandscapeConstants;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Implementation of {@link BuildingApi} backed by {@link BuildingSavedData}.
 */
public class BuildingApiImpl implements BuildingApi {
    private static final String TAG = "BuildingApiImpl";

    // Task tracking (engine taskId → buildingId)
    private final Map<UUID, UUID> currentTasks = new ConcurrentHashMap<>(); // buildingId → taskId

    // Three-value: per colony, which building types have ever been built
    private final Map<UUID, Set<String>> colonyUnlockedTypes = new ConcurrentHashMap<>();

    /** 拆除保护计数的建筑最小投影（纯数据，可脱离 MC 运行时单测）。 */
    record CategoryPresence(String category, boolean demolishing) {}

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

    @Override
    @Nullable
    public BoundingBox getBuildingBounds(UUID buildingId) {
        BuildingSavedData sd = getSavedData();
        BuildingState state = sd != null ? sd.getBuilding(buildingId) : null;
        return state != null ? state.getBounds() : null;
    }

    // ---- Lifecycle ----

    @Override
    public void registerBuilding(BuildingData data) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) {
            Log.warn(TAG, "Cannot register building — no server level available");
            return;
        }

        BuildingState state = (BuildingState) data;

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
        }

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

        unregisterState(state);
    }

    /** Remove a building and all its residual data from every registry. */
    private void unregisterState(BuildingState state) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return;

        UUID colonyId = state.getColonyId();
        if (colonyId != null) {
            // Remove from the contribution registry so evaluation values drop
            // if this was the last intact building of its type.
            sd.removeBuildingContribution(colonyId, state.getBuildingTypeId());
        }
        currentTasks.remove(state.getBuildingId());
        // Clear shop stock too — otherwise a demolished shop still sells goods.
        sd.removeShopData(state.getBuildingId());
        sd.unregister(state.getBuildingId());
        // Notify engine services (e.g. ChunkLoadManager) to release the footprint lease.
        NeoForge.EVENT_BUS.post(new BuildingRemovedEvent(state.getBuildingId(), state.getColonyId()));
    }

    // ---- Colony stats (three-value system) ----

    @Override
    @Nullable
    public ColonySnapshot getColonySnapshot(UUID colonyId) {
        BuildingSavedData sd = getSavedData();
        if (sd == null || colonyId == null) return null;
        var registry = sd.getContributionRegistry();
        if (registry == null) return null;
        var inner = registry.getSnapshot(colonyId);
        return new ColonySnapshot(inner.comfort(), inner.magic(), inner.wonder());
    }

    @Override
    public int getColonyComfort(UUID colonyId) {
        ColonySnapshot snap = getColonySnapshot(colonyId);
        return snap != null ? snap.comfort() : 0;
    }

    @Override
    public int getColonyMagic(UUID colonyId) {
        ColonySnapshot snap = getColonySnapshot(colonyId);
        return snap != null ? snap.magic() : 0;
    }

    @Override
    public int getColonyWonder(UUID colonyId) {
        ColonySnapshot snap = getColonySnapshot(colonyId);
        return snap != null ? snap.wonder() : 0;
    }

    // ---- Demolish ----

    private static final int DEMOLISH_PRIORITY = 49;

    /**
     * 是否应阻止拆除：目标建筑所属类别在受保护类别内，且它是该类唯一未被拆除中的一座。
     * 纯函数，输入为全部注册建筑的最小投影，便于脱离 MC 运行时单元测试。
     *
     * @param buildings          当前全部注册建筑（含目标）的类别/拆除中投影
     * @param protectedCategories 受保护类别集合
     * @param targetCategory     被拆除建筑所属类别
     */
    static boolean isLastProtected(List<CategoryPresence> buildings,
                                   Set<String> protectedCategories,
                                   String targetCategory) {
        if (!protectedCategories.contains(targetCategory)) return false;
        int remaining = 0;
        for (CategoryPresence b : buildings) {
            if (targetCategory.equals(b.category()) && !b.demolishing()) remaining++;
        }
        return remaining <= 1;
    }

    /** 目标建筑是否是其受保护类别的最后一座（拆除/撤销入口的防御性双保险）。 */
    private boolean isProtectedLast(BuildingState state) {
        if (!WandscapeConstants.PROTECTED_LAST_CATEGORIES.contains(state.getCategory())) return false;
        BuildingSavedData sd = getSavedData();
        if (sd == null) return false;
        List<CategoryPresence> projection = new ArrayList<>();
        for (BuildingState b : sd.getAllBuildings()) {
            projection.add(new CategoryPresence(b.getCategory(), b.isDemolishing()));
        }
        return isLastProtected(projection, WandscapeConstants.PROTECTED_LAST_CATEGORIES,
                state.getCategory());
    }

    @Override
    @Nullable
    public Component demolishBlockReason(UUID buildingId) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return null;
        BuildingState state = sd.getBuilding(buildingId);
        if (state == null || state.isDemolishing() || !isProtectedLast(state)) return null;
        return Component.literal("这是最后一座同类建筑，必须保留至少一座以维持殖民地运转");
    }

    @Override
    public void demolishBuilding(UUID buildingId) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return;

        BuildingState state = sd.getBuilding(buildingId);
        if (state == null) return;

        if (state.isDemolishing()) {
            return;
        }

        if (isProtectedLast(state)) {
            Log.warn(TAG, "[Demolish] BLOCKED {} ({}) at {} — last {} building, demolition refused",
                    state.getBuildingTypeId(), buildingId, state.getAnchor(), state.getCategory());
            return;
        }

        // Immediately stop any in-progress construction/production task so an NPC
        // doesn't keep working on a structure that's being torn down (undo/destroy).
        BuildingTaskSource.cancelBuildingTasks(buildingId);

        // Cancel any auto-synthesis tasks if the building was still under construction
        if (!state.hasEverCompleted()) {
            BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
            if (config != null) {
                Map<String, Integer> materialCounts = EnqueueHelper.computeMaterialCounts(config);
                if (!materialCounts.isEmpty()) {
                    com.wsteam.wandscape.content.warehouse.system.ResourceSupplySystem.cancelAutoSynthesize(
                            state.getColonyId(), materialCounts, com.wsteam.wandscape.impl.WandscapeEngine.getWorld());
                }
            }
        }

        // Mark building for demolition and clear any pending work. Also flip
        // structureIntact so tourist filters (which check intact) drop the
        // building immediately, before the NPC dispatch poll picks it up.
        state.setDemolishing(true);
        state.setStructureIntact(false);
        state.getTaskQueue().clear();

        // Build the demolish WorkItem — iterate all pattern offsets, place air
        BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        if (config == null) {
            Log.error(TAG, "demolishBuilding: config not found for {} ({})", state.getBuildingTypeId(), buildingId);
            return;
        }

        Map<String, JsonElement> params = new HashMap<>();
        params.put("anchor", posToJsonArray(state.getAnchor()));
        params.put("building_id", new JsonPrimitive(buildingId.toString()));

        int rotationSteps = state.getRotationSteps();
        java.util.List<BlockOffset> pattern =
                BuildingRotation.rotateOffsets(
                        config.pattern(), rotationSteps);

        JsonArray offsets = new JsonArray();
        for (var offset : pattern) {
            JsonArray arr = new JsonArray();
            arr.add(offset.x());
            arr.add(offset.y());
            arr.add(offset.z());
            offsets.add(arr);
        }
        params.put("offsets", offsets);

        WorkItem demolishWork = new WorkItem("build:demolish_structure", params, DEMOLISH_PRIORITY);
        state.getTaskQueue().addLast(demolishWork);
        sd.setDirty();

        Log.info(TAG, "[Demolish] Building {} ({}) at {} — demolition enqueued",
                state.getBuildingTypeId(), buildingId, state.getAnchor());
    }

    @Override
    public boolean isDemolishing(UUID buildingId) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return false;
        BuildingState state = sd.getBuilding(buildingId);
        return state != null && state.isDemolishing();
    }

    @Override
    public boolean cancelBuilding(UUID buildingId) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return false;

        BuildingState state = sd.getBuilding(buildingId);
        if (state == null) return false;

        if (isProtectedLast(state)) {
            Log.warn(TAG, "[Cancel] BLOCKED {} ({}) — last {} building, undo refused",
                    state.getBuildingTypeId(), buildingId, state.getCategory());
            return false;
        }

        // Only buildings that have not yet completed construction can be undone.
        // Completed buildings are removed through the normal demolition path instead.
        if (state.hasEverCompleted() || state.isDemolishing()) return false;

        UUID colonyId = state.getColonyId();
        BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        var world = com.wsteam.wandscape.impl.WandscapeEngine.getWorld();

        // 1. Immediately cancel all building tasks in engine/global/building task pool and clear state queue
        BuildingTaskSource.cancelBuildingTasks(buildingId);
        state.getTaskQueue().clear();

        // 2. Cancel auto-synthesized workstation tasks spawned for this building
        if (config != null) {
            Map<String, Integer> materialCounts = EnqueueHelper.computeMaterialCounts(config);
            if (!materialCounts.isEmpty()) {
                com.wsteam.wandscape.content.warehouse.system.ResourceSupplySystem.cancelAutoSynthesize(
                        colonyId, materialCounts, world);
            }
        }

        if (state.isConstructionStarted()) {
            // Construction started → materials were charged to the warehouse in one
            // bulk commit at construction start, so refund the full material cost,
            // then demolish whatever has been built.
            refundUnplacedMaterials(state);
            demolishBuilding(buildingId);
        } else {
            // Not started → nothing was consumed; just drop the pending building.
            unregisterState(state);
        }
        sd.setDirty();
        return true;
    }

    /**
     * Return the material cost of the blocks that were NOT yet placed to the colony
     * warehouse. Blocks that WERE placed are refunded physically by the demolition's
     * salvage flow (each broken block drops back as items), so refunding the full
     * blueprint cost here would double-count and mint items. The offsets that are
     * still missing / mismatched are detected via
     * {@link BuildCompleteListener#findDamagedBlocks} — the same data the
     * {@code build:place_structure} request_resource step consumed, minus the
     * already-placed offsets.
     */
    private void refundUnplacedMaterials(BuildingState state) {
        UUID colonyId = state.getColonyId();
        if (colonyId == null) return;
        BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        if (config == null) return;
        Level level = getServerLevel();
        if (level == null) return;
        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) return;

        var missingOffsets = BuildCompleteListener.findDamagedBlocks(
                level, state.getAnchor(), config, state.getRotationSteps());
        Map<String, Integer> counts = materialCountsForMissingOffsets(
                config, state.getRotationSteps(), missingOffsets,
                id -> com.wsteam.wandscape.api.WandscapeApis.getElementApi().hasElementMapping(id));
        if (counts.isEmpty()) return;

        int total = 0;
        for (var entry : counts.entrySet()) {
            bank.add(colonyId, ItemKey.of(entry.getKey(), null), entry.getValue());
            total += entry.getValue();
        }
        Log.info(TAG, "[Cancel] Refunded {} unplaced material items ({} types) to colony {} for {} ({})",
                total, counts.size(), colonyId.toString().substring(0, 8),
                state.getBuildingTypeId(), state.getBuildingId().toString().substring(0, 8));
    }

    /**
     * Material counts for the offsets that are missing / not yet placed, using the
     * same口径 as {@link EnqueueHelper#computeMaterialCounts} (bare block id, element
     * mapping filter, air skip, 1 per offset). The missing offsets are passed in so
     * this stays a pure function, testable without a live {@code Level} or element
     * registry.
     *
     * <p>Already-placed offsets are excluded so the demolition's salvage handles
     * them — the refund only covers what the warehouse was charged but never turned
     * into a block.
     */
    static Map<String, Integer> materialCountsForMissingOffsets(BuildingConfig config, int rotationSteps,
            java.util.Collection<BlockOffset> missingOffsets,
            java.util.function.Predicate<String> hasElementMapping) {
        var missingKeys = new java.util.HashSet<String>();
        for (BlockOffset off : missingOffsets) missingKeys.add(off.toKey());

        java.util.List<BlockOffset> rotatedPattern = BuildingRotation.rotateOffsets(config.pattern(), rotationSteps);
        var counts = new java.util.LinkedHashMap<String, Integer>();
        for (int i = 0; i < rotatedPattern.size(); i++) {
            if (!missingKeys.contains(rotatedPattern.get(i).toKey())) continue;
            String blockId = config.blockIdAt(i);
            String pureId = blockId.replaceAll("\\[.*?\\]", "").trim();
            if ("minecraft:air".equals(pureId)) continue;
            if (!hasElementMapping.test(pureId)) continue;
            counts.merge(pureId, 1, Integer::sum);
        }
        return counts;
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
                continue;
            }
            if (currentTasks.containsKey(state.getBuildingId())) {
                continue;
            }
            if (!hasClaimableWork(sd, state)) {
                continue;
            }
            // No longer skip unloaded anchors: BuildingTaskSource force-loads the
            // building's footprint before dispatching, so the colony keeps building
            // even while its chunks are unloaded.
            result.add(state.getBuildingId());
        }
        return result;
    }

    /**
     * Whether a building has work it can claim: its own queue (construction / repair)
     * OR, for a built shared building, its group queue.
     */
    private boolean hasClaimableWork(BuildingSavedData sd, BuildingState state) {
        if (state.hasWork()) return true;
        UUID cid = state.getColonyId();
        if (cid == null || !state.hasEverCompleted()) return false;
        String groupKey = BuildingSavedData.groupKeyFor(state);
        return groupKey != null && sd.hasSharedWork(cid, groupKey);
    }

    @Override
    @Nullable
    public WorkItem dequeueWork(UUID buildingId) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return null;

        BuildingState state = sd.getBuilding(buildingId);
        if (state == null) return null;

        // Shared queue claim: a built, operational shared building (workstation / node)
        // pulls the front unclaimed task from its group queue. The anchor is rebound to
        // this building so the channel progress is tracked per-station and tasks at
        // different stations never collide on one anchor.
        if (state.hasEverCompleted()) {
            Deque<WorkItem> shared = sharedQueueFor(sd, state);
            if (shared != null && !shared.isEmpty()) {
                WorkItem item = shared.pollFirst();
                sd.setDirty();
                return rebindAnchor(item, state.getAnchor());
            }
        }

        WorkItem item = state.getTaskQueue().pollFirst();
        if (item != null) {
            sd.setDirty();
            // Sticky "under construction" marker: once an NPC claims a not-yet-
            // completed building's work, it is being built and never reverts to
            // waiting-for-materials. Completed buildings already started long ago.
            if (!state.hasEverCompleted() && !state.isConstructionStarted()) {
                state.setConstructionStarted(true);
            }
            // Demolition task has been claimed by an NPC — remove the building
            // data NOW instead of waiting for the fragile blueprint tail
            // (for_each → emit_event). Block destruction uses the snapshot params
            // in the WorkItem, so it is decoupled from data cleanup. The
            // demolish_complete event listener stays as an idempotent fallback.
            if ("build:demolish_structure".equals(item.blueprintId())) {
                unregisterState(state);
            }
        }
        return item;
    }

    @Override
    @Nullable
    public WorkItem dequeueWorkEligible(UUID buildingId, Predicate<WorkItem> eligible) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return null;

        BuildingState state = sd.getBuilding(buildingId);
        if (state == null) return null;

        // Shared queue claim: a built, operational shared building pulls the first
        // task its owner accepts (e.g. the first element-affordable craft), leaving
        // rejected ones (element-short crafts) in place for later. Mirrors
        // dequeueWork's shared-queue routing.
        if (state.hasEverCompleted()) {
            Deque<WorkItem> shared = sharedQueueFor(sd, state);
            if (shared != null && !shared.isEmpty()) {
                WorkItem item = pollFirstEligible(shared, eligible);
                if (item != null) {
                    sd.setDirty();
                    return rebindAnchor(item, state.getAnchor());
                }
            }
        }

        WorkItem item = pollFirstEligible(state.getTaskQueue(), eligible);
        if (item != null) {
            sd.setDirty();
            if (!state.hasEverCompleted() && !state.isConstructionStarted()) {
                state.setConstructionStarted(true);
            }
            if ("build:demolish_structure".equals(item.blueprintId())) {
                unregisterState(state);
            }
        }
        return item;
    }

    /** Remove and return the first queue item accepted by {@code eligible}; rejected items stay put. */
    @Nullable
    static WorkItem pollFirstEligible(Deque<WorkItem> queue, Predicate<WorkItem> eligible) {
        java.util.List<WorkItem> list = new ArrayList<>(queue);
        for (int i = 0; i < list.size(); i++) {
            WorkItem item = list.get(i);
            if (eligible.test(item)) {
                list.remove(i);
                queue.clear();
                queue.addAll(list);
                return item;
            }
        }
        return null;
    }

    @Override
    public void enqueueWork(UUID buildingId, WorkItem work) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return;

        BuildingState state = sd.getBuilding(buildingId);
        if (state == null || state.isDemolishing()) return;

        // Production/gather tasks on shared buildings (workstation family / nodes) go into
        // the group queue so any idle member can claim them; construction/repair stays on
        // the building's own queue.
        Deque<WorkItem> queue = null;
        if (isRoleWork(state, work)) {
            UUID cid = state.getColonyId();
            String groupKey = cid != null ? BuildingSavedData.groupKeyFor(state) : null;
            if (groupKey != null) queue = sd.sharedQueue(cid, groupKey);
        }
        if (queue == null) queue = state.getTaskQueue();

        // Merge a production task into an adjacent same-recipe task at its priority band's
        // tail so restock x1/x2 requests don't flood the queue with consecutive *7/*9
        // entries. A merge consumes no queue slot, so it must run before the capacity check.
        // Band-tail placement means a player task always merges at the top of its band, never
        // behind lower-priority restock/auto-craft tasks.
        if (mergeBandTail(queue, work)) {
            sd.setDirty();
            return;
        }

        insertByPriority(queue, work);
        sd.setDirty();
    }

    // ---- Production task merging ----

    /** Params that scale with quantity; excluded from the merge signature, summed on merge. */
    private static final Set<String> PRODUCTION_SCALED_PARAMS = Set.of("count", "channel_ticks");

    /**
     * Identity of a production task for merging: blueprint + every param except
     * count/channel_ticks (anchor, recipe/item id, ...). Returns null for non-production
     * blueprints so they are never merged.
     */
    static String productionSignature(WorkItem w) {
        if (w == null || !w.blueprintId().startsWith("production:")) return null;
        StringBuilder sb = new StringBuilder(w.blueprintId()).append('|');
        w.params().entrySet().stream()
                .filter(e -> !PRODUCTION_SCALED_PARAMS.contains(e.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append('=').append(e.getValue()).append(';'));
        return sb.toString();
    }

    /**
     * Merge {@code incoming} into the tail of its priority band when that band's last task
     * is an adjacent same-recipe production task. Counts and channel ticks sum — executing
     * both requests sequentially is equivalent to the single merged task. Returns true when
     * merged; the incoming task then consumes no queue slot.
     */
    static boolean mergeBandTail(Deque<WorkItem> queue, WorkItem incoming) {
        List<WorkItem> list = new ArrayList<>(queue);
        for (int i = list.size() - 1; i >= 0; i--) {
            WorkItem item = list.get(i);
            if (item.priority() < incoming.priority()) continue;  // below the band, keep scanning
            if (item.priority() > incoming.priority()) break;     // reached a higher band — no band below
            if (mergeable(incoming, item)) {
                WorkItem merged = mergeWork(item, incoming);
                list.set(i, merged);
                queue.clear();
                queue.addAll(list);
                Log.info(TAG, "enqueueWork: merged {} tasks at building {} — count={}",
                        merged.blueprintId(), merged.params().get("anchor"), merged.params().get("count"));
                return true;
            }
            break; // band tail is a different task — no merge, insertion lands after it
        }
        return false;
    }

    /**
     * Insert {@code work} at the tail of its own priority band — after all tasks with
     * priority {@code >= work.priority()}, before the first lower-priority task — keeping
     * the queue ordered high-to-low so {@code dequeueWork}'s {@code pollFirst} always
     * serves the highest priority first.
     */
    static void insertByPriority(Deque<WorkItem> queue, WorkItem work) {
        List<WorkItem> list = new ArrayList<>(queue);
        int index = 0;
        while (index < list.size() && list.get(index).priority() >= work.priority()) {
            index++;
        }
        list.add(index, work);
        queue.clear();
        queue.addAll(list);
    }

    /** Whether {@code incoming} and {@code base} are mergeable adjacent production tasks. */
    private static boolean mergeable(WorkItem incoming, WorkItem base) {
        String sig = productionSignature(incoming);
        return sig != null && sig.equals(productionSignature(base));
    }

    /** Combine two same-signature production tasks: counts and channel ticks sum. */
    private static WorkItem mergeWork(WorkItem base, WorkItem incoming) {
        Map<String, JsonElement> params = new LinkedHashMap<>(base.params());
        params.put("count", new JsonPrimitive(
                mergeParamInt(base.params(), "count") + mergeParamInt(incoming.params(), "count")));
        params.put("channel_ticks", new JsonPrimitive(
                mergeParamInt(base.params(), "channel_ticks")
                        + mergeParamInt(incoming.params(), "channel_ticks")));
        return new WorkItem(base.blueprintId(), params, base.priority());
    }

    private static int mergeParamInt(Map<String, JsonElement> params, String key) {
        JsonElement el = params.get(key);
        return (el instanceof JsonPrimitive p && p.isNumber()) ? p.getAsInt() : 0;
    }

    // ---- Shared production queue routing ----

    /** Whether a WorkItem is a production task (synthesize/decompose/craft/brew). */
    static boolean isProductionWork(WorkItem w) {
        return w != null && w.blueprintId().startsWith("production:");
    }

    /** Whether a WorkItem is a node gather task. */
    static boolean isGatherWork(WorkItem w) {
        return w != null && "node:gather".equals(w.blueprintId());
    }

    /** Whether {@code work} belongs to {@code state}'s shared queue role (station↔production, node↔gather). */
    private static boolean isRoleWork(BuildingState state, WorkItem work) {
        return "node".equals(state.getCategory()) ? isGatherWork(work) : isProductionWork(work);
    }

    /**
     * The shared group queue a building participates in, or null when it shares none
     * (non-shared category, or no colony assigned). Shared buildings are workstation
     * family (by typeId) and element nodes (by element).
     */
    @Nullable
    private Deque<WorkItem> sharedQueueFor(BuildingSavedData sd, BuildingState state) {
        UUID cid = state.getColonyId();
        if (cid == null) return null;
        String groupKey = BuildingSavedData.groupKeyFor(state);
        return groupKey != null ? sd.peekSharedQueue(cid, groupKey) : null;
    }

    /**
     * Rebind a claimed WorkItem's {@code anchor} to the claiming building so the
     * async channel progress is tracked per-station (two concurrent tasks at
     * different stations must not collide on one anchor). Returns a new WorkItem;
     * the queued element is left untouched.
     */
    static WorkItem rebindAnchor(WorkItem item, BlockPos anchor) {
        Map<String, JsonElement> params = new LinkedHashMap<>(item.params());
        JsonArray arr = new JsonArray();
        arr.add(anchor.getX());
        arr.add(anchor.getY());
        arr.add(anchor.getZ());
        params.put("anchor", arr);
        return new WorkItem(item.blueprintId(), params, item.priority());
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

        Deque<WorkItem> queue = sharedQueueFor(sd, state);
        if (queue == null) queue = state.getTaskQueue();
        return new ArrayList<>(queue);
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

        Deque<WorkItem> queue = sharedQueueFor(sd, state);
        if (queue == null) queue = state.getTaskQueue();
        if (index < 0 || index >= queue.size()) {
            Log.warn(TAG, "removeFromQueue: index {} out of range (size={}) for {}", index, queue.size(), buildingId);
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

        Deque<WorkItem> queue = sharedQueueFor(sd, state);
        if (queue == null) queue = state.getTaskQueue();
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

        Deque<WorkItem> queue = sharedQueueFor(sd, state);
        if (queue == null) queue = state.getTaskQueue();
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

    // ---- Placement (unified entry point) ----

    @Override
    public PlacementResult placeBuilding(BlockPos anchor, String buildingTypeId, int rotationSteps) {
        BuildingConfig config = BuildingConfigLoader.getInstance().get(buildingTypeId);
        if (config == null) {
            return PlacementResult.fail(Component.literal("Unknown building type: " + buildingTypeId));
        }

        UUID tempColonyId = com.wsteam.wandscape.content.colony.ColonyApiImpl.get().getColonyId(anchor);
        if (!BuildingUnlockChecker.isUnlocked(tempColonyId, config)) {
            Component reason = BuildingUnlockChecker.getLockReason(tempColonyId, config);
            return PlacementResult.fail(reason != null ? reason : Component.literal("Building is locked"));
        }

        // A disabled block must not be placed as a free material — refuse the whole build.
        String disabledBlock = EnqueueHelper.findDisabledBlock(config);
        if (disabledBlock != null) {
            return PlacementResult.fail(Component.literal("Building uses a disabled block: " + disabledBlock));
        }

        // Register (overlap check happens inside → BuildingSavedData.register).
        // The anchor is a reference point only (may sit outside the building's own
        // boundary, e.g. scanner placed in front), so use the returned state
        // directly instead of re-locating the building by position.
        BuildingState state = EnqueueHelper.registerIfAbsent(anchor, config, buildingTypeId, rotationSteps);
        if (state == null) {
            return PlacementResult.fail(Component.literal("Cannot place here — overlaps with an existing building"));
        }

        UUID colonyId = state.getColonyId();
        UUID buildingId = state.getBuildingId();
        BuildingSavedData sd = getSavedData();

        boolean isGov = "government".equals(config.category());
        boolean firstFree;
        if (colonyId == null) {
            firstFree = isGov && config.firstFree();
        } else {
            firstFree = !isGov && config.firstFree()
                    && sd != null
                    && !sd.isFirstFreeClaimed(colonyId, buildingTypeId);
        }

        WorkItem workItem = EnqueueHelper.buildWorkItem(
                config, anchor, buildingTypeId, 0,
                sd, buildingId, rotationSteps,
                firstFree);

        if (firstFree && sd != null && colonyId != null) {
            sd.claimFirstFree(colonyId, buildingTypeId);
        }

        enqueueWork(buildingId, workItem);

        Log.info(TAG, "[Placement] '{}' at {} firstFree={}",
                config.displayName(), anchor, firstFree);
        return PlacementResult.ok(buildingId, firstFree);
    }

    @Override
    public boolean isFirstFreeClaimed(UUID colonyId, String buildingTypeId) {
        BuildingSavedData sd = getSavedData();
        return sd != null && colonyId != null && sd.isFirstFreeClaimed(colonyId, buildingTypeId);
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
        return result;
    }

    @Override
    @Nullable
    public BlockPos getTouristInteractionTarget(UUID buildingId) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return null;

        Level level = this.serverLevel;
        if (level == null) level = getServerLevel();
        if (level == null) return null;

        return sd.getTouristInteractionTarget(buildingId, level);
    }

    @Override
    @Nullable
    public BlockPos getEntryPoint(UUID buildingId) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return null;

        Level level = this.serverLevel;
        if (level == null) level = getServerLevel();
        if (level == null) return null;

        return sd.getEntryPoint(buildingId, level);
    }

    @Override
    @Nullable
    public BlockPos getTouristInteractPoint(UUID buildingId) {
        BuildingSavedData sd = getSavedData();
        if (sd == null) return null;

        Level level = this.serverLevel;
        if (level == null) level = getServerLevel();
        if (level == null) return null;

        return sd.getTouristInteractPoint(buildingId, level);
    }

    @Nullable
    private static Level getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return server.overworld();
    }

    private static JsonArray posToJsonArray(BlockPos pos) {
        JsonArray arr = new JsonArray();
        arr.add(pos.getX());
        arr.add(pos.getY());
        arr.add(pos.getZ());
        return arr;
    }
}
