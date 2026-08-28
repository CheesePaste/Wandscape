package com.wsteam.wandscape.engine.system;

import java.util.*;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import net.minecraft.core.BlockPos;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.data.BuildingConfig.NodeConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.core.ecs.System;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.engine.boundary.ProductionEligibility;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.shared.registry.WandscapeConstants;
import com.wsteam.wandscape.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.task.runtime.TaskState;
import com.wsteam.wandscape.warehouse.ColonyItemBank;

import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Periodically scans {@link TaskState#AWAITING_RESOURCES} tasks and
 * orchestrates resource supply to unblock them.
 *
 * <p>Two actions per scan cycle:
 * <ol>
 *   <li>Wake up tasks whose resources are now available.</li>
 *   <li>For tasks still blocked: try synthesize first, then fall back to
 *       node gathering for raw elements.</li>
 * </ol>
 *
 * <p>Runs on a 40-tick heartbeat to balance responsiveness with overhead.
 * The event-driven {@code onResourceAdded} path handles immediate wake-up;
 * this system is the retry loop for cases where the initial shortage handler
 * could not create supply tasks (e.g. no crafting station was free at the time).
 */
public class ResourceSupplySystem implements System {

    private static final String TAG = "ResourceSupplySystem";
    private static final int HEARTBEAT = 40;

    private int tickCounter;

    @Override
    public void update(World world, float delta) {
        tickCounter++;
        if (tickCounter % HEARTBEAT != 0) return;
        scanStuckTasks(world);
    }

    private void scanStuckTasks(World world) {
        scanAwaitingTasks(world);
        scanProductionQueues(world);
    }

    /** 扫描 AWAITING_RESOURCES 任务（建材运输等非生产路径）并补资源。 */
    private void scanAwaitingTasks(World world) {
        List<GlobalTask> waiting = world.taskPool.getByState(TaskState.AWAITING_RESOURCES);
        if (waiting.isEmpty()) return;

        for (GlobalTask task : waiting) {
            if (task.awaitingResource == null || task.awaitingResource.isEmpty()) continue;

            // Defense-in-depth: Never attempt to auto-supply materials for a decompose task
            if ("production:decompose".equals(task.blueprintId)) {
                world.taskPool.wakeupTask(task.id);
                continue;
            }

            boolean allAvailable = true;
            for (ResourceStack need : task.awaitingResource) {
                if (world.colonyResources.available(need.resource()) < need.amount()) {
                    allAvailable = false;
                    break;
                }
            }

            if (allAvailable) {
                world.taskPool.wakeupTask(task.id);
                continue;
            }

            for (ResourceStack need : task.awaitingResource) {
                int available = world.colonyResources.available(need.resource());
                if (available >= need.amount()) continue;
                trySupplyResource(need.resource(), need.amount() - available, world);
            }
        }
    }

    /**
     * 扫描工作站/合成站/魔法工坊队列里元素不足的生产配方：聚合每种元素的缺口，
     * 走 {@link #trySupplyResource}（先合成、回退节点采集）自动补齐。这些条目留在队列
     * 原位（面板可见「缺元素」），补齐后由 BuildingTaskSource 的发布扫描自然挑中。
     */
    private void scanProductionQueues(World world) {
        var api = getBuildingApi();
        var server = ServerLifecycleHooks.getCurrentServer();
        if (api == null || server == null) return;
        ColonyItemBank bank = ColonyItemBank.get(server.overworld());
        if (bank == null) return;

        Map<ElementType, Long> deficits = new LinkedHashMap<>();
        Set<String> seenGroups = new HashSet<>();
        for (String category : List.of("workstation", "crafting_station", "magic_station")) {
            for (UUID buildingId : api.getBuildingsByCategory(null, category)) {
                BuildingData bd = api.getBuilding(buildingId);
                if (bd == null) continue;
                UUID colonyId = bd.getColonyId();
                if (colonyId == null) continue;
                // 共享队列按 (colony, typeId) 分组，每组只扫一次。
                String groupKey = colonyId + "|" + bd.getBuildingTypeId();
                if (!seenGroups.add(groupKey)) continue;

                Map<ElementType, Long> available = bank.getElementSnapshot(colonyId);
                for (WorkItem item : api.getQueue(buildingId)) {
                    String bid = item.blueprintId();
                    if (!ProductionEligibility.isElementCosting(bid)) continue;
                    Map<ElementType, Long> required = ProductionEligibility.requiredElements(bid, item.params());
                    for (ElementType el : ProductionEligibility.missingElements(required, available)) {
                        long deficit = required.get(el) - available.getOrDefault(el, 0L);
                        deficits.merge(el, Math.max(1, deficit), Long::sum);
                    }
                }
            }
        }

        for (var e : deficits.entrySet()) {
            trySupplyResource(new ResourceId(e.getKey().getId()), e.getValue().intValue(), world);
        }
    }

    /**
     * Try to create a supply task for the given resource shortfall.
     * Prefers synthesize (elements → items) over raw node gathering.
     */
    private void trySupplyResource(ResourceId resource, int deficit, World world) {
        if (enqueueSynthesize(resource.id(), deficit, world)) return;
        tryGatherElement(resource, deficit, world);
    }

    /**
     * Enqueue {@code production:synthesize} work for {@code itemId} at a workstation,
     * but only for the shortfall beyond what is already queued or running. The
     * synthesized output lands in the colony warehouse; callers that initiated a
     * restock should retry once the item becomes available.
     *
     * @return true if the shortfall is being handled (covered by existing production
     *         or a new task was queued); false if it cannot be synthesized right now
     */
    public static boolean enqueueSynthesize(String itemId, int amount, @Nullable World world) {
        return enqueueSynthesize(itemId, amount, null, world, false);
    }

    /**
     * Colony-scoped variant of {@link #enqueueSynthesize(String, int, World)}.
     */
    public static boolean enqueueSynthesize(String itemId, int amount, @Nullable UUID colonyId, @Nullable World world) {
        return enqueueSynthesize(itemId, amount, colonyId, world, false);
    }

    /**
     * Colony-scoped variant with explicit urgency. {@code atFront} marks the task as shop
     * restock (补货段, priority 60); otherwise it is auto shortfall supply (自动段,
     * priority 40). The workstation queue orders by priority band, so a restock task is
     * served before any auto-craft task queued earlier.
     */
    public static boolean enqueueSynthesize(String itemId, int amount, @Nullable UUID colonyId,
                                            @Nullable World world, boolean atFront) {
        var recipes = Wandscape.PRODUCTION_RECIPE_LOADER;
        if (recipes == null) return false;
        if (recipes.getSynthesizeRecipe(itemId) == null) return false;

        int inFlight = countSynthesizeInFlight(itemId, colonyId, world);
        int toAdd = amount - inFlight;
        if (toAdd <= 0) return true; // already covered by queued/running production

        BuildingApi api = getBuildingApi();
        if (api == null) return false;
        List<UUID> stations = api.getBuildingsByCategory(colonyId, "workstation");
        if (stations.isEmpty()) return false;

        UUID stationId = null;
        BuildingData building = null;
        for (UUID id : stations) {
            BuildingData bd = api.getBuilding(id);
            if (bd != null && !bd.isDemolishing()) {
                stationId = id;
                building = bd;
                break;
            }
        }
        if (stationId == null || building == null) return false;

        BlockPos pos = building.getPosition();
        int count = Math.max(toAdd, 1);
        Map<String, JsonElement> params = new LinkedHashMap<>();
        params.put("anchor", posToJsonArray(pos));
        params.put("recipe_id", new JsonPrimitive(itemId));
        params.put("count", new JsonPrimitive(count));
        int channelTicks = com.wsteam.wandscape.Wandscape.PRODUCTION_RECIPE_LOADER != null
                ? com.wsteam.wandscape.Wandscape.PRODUCTION_RECIPE_LOADER.computeSynthesizeChannelTicks(itemId, count)
                : WandscapeConstants.WORKSTATION_CRAFT_TICKS_PER_UNIT * count;
        params.put("channel_ticks", new JsonPrimitive(channelTicks));

        // 商店补货（atFront）比自动补产（卡资源缺口的短供）更优先：前者进补货段，
        // 后者进自动段，队列按优先级分段排序。
        int priority = atFront
                ? WandscapeConstants.TASK_PRIORITY_RESTOCK
                : WandscapeConstants.TASK_PRIORITY_AUTO;
        api.enqueueWork(stationId, new WorkItem("production:synthesize", params, priority));
        Log.info(TAG, "shortfall {} x{} → synthesize:{} at workstation {} ({} already in flight, priority={})",
                itemId, amount, itemId, stationId.toString().substring(0, 8), inFlight, priority);
        return true;
    }

    /**
     * Sum the amount of {@code production:synthesize} work for {@code itemId} already
     * queued on any workstation or running in the task pool. Recipe ids are compared
     * prefix-insensitively so bare ("bread") and full ("minecraft:bread") ids aggregate.
     */
    private static int countSynthesizeInFlight(String itemId, @Nullable World world) {
        return countSynthesizeInFlight(itemId, null, world);
    }

    /**
     * Colony-scoped variant of {@link #countSynthesizeInFlight(String, World)}. When
     * {@code colonyId} is non-null, only that colony's workstations are considered for
     * the queued portion (running pool tasks are always global — tasks don't expose a
     * colony filter cheaply).
     */
    public static int countSynthesizeInFlight(String itemId, @Nullable UUID colonyId, @Nullable World world) {
        String key = stripMcPrefix(itemId);
        int total = 0;

        if (world != null) {
            for (GlobalTask t : world.taskPool.all()) {
                if (t.state == TaskState.COMPLETED) continue;
                if (!"production:synthesize".equals(t.blueprintId)) continue;
                if (!sameRecipe(key, t.taskParams.get("recipe_id"))) continue;
                total += intParam(t.taskParams.get("count"));
            }
        }

        BuildingApi api = getBuildingApi();
        if (api != null) {
            // Each shared queue is counted once per (colony, buildingTypeId) group — a
            // workstation's getQueue now returns its group's shared queue, so iterating
            // every station would count the same queue once per member.
            Set<String> seen = new HashSet<>();
            for (UUID stationId : api.getBuildingsByCategory(colonyId, "workstation")) {
                BuildingData bd = api.getBuilding(stationId);
                if (bd == null) continue;
                String dedupKey = String.valueOf(bd.getColonyId()) + "|" + bd.getBuildingTypeId();
                if (!seen.add(dedupKey)) continue;
                for (WorkItem item : api.getQueue(stationId)) {
                    if (!"production:synthesize".equals(item.blueprintId())) continue;
                    if (!sameRecipe(key, item.params().get("recipe_id"))) continue;
                    total += intParam(item.params().get("count"));
                }
            }
        }
        return total;
    }

    /**
     * Number of workstation buildings (optionally colony-scoped) currently working on a
     * {@code production:synthesize} task — either a running pool task anchored to them or
     * a synthesize item still sitting in their queue. This is the "工作中工作站数量"
     * divisor for the construction-site panel's start-time estimate.
     */
    public static int countSynthesizingWorkstations(@Nullable UUID colonyId, @Nullable World world) {
        BuildingApi api = getBuildingApi();
        if (api == null) return 0;

        // Anchors of workstations with a running synthesize task (head was dequeued,
        // so it no longer appears in the queue).
        var runningAnchors = new HashSet<BlockPos>();
        if (world != null) {
            for (GlobalTask t : world.taskPool.all()) {
                if (t.state == TaskState.COMPLETED) continue;
                if (!"production:synthesize".equals(t.blueprintId)) continue;
                JsonElement anchor = t.taskParams.get("anchor");
                if (anchor != null && anchor.isJsonArray() && anchor.getAsJsonArray().size() >= 3) {
                    JsonArray a = anchor.getAsJsonArray();
                    runningAnchors.add(new BlockPos(
                            a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()));
                }
            }
        }

        int count = 0;
        Set<String> countedQueueGroups = new HashSet<>();
        for (UUID stationId : api.getBuildingsByCategory(colonyId, "workstation")) {
            BuildingData bd = api.getBuilding(stationId);
            BlockPos pos = bd != null ? bd.getPosition() : null;
            boolean isRunning = pos != null && runningAnchors.contains(pos);
            if (isRunning) { count++; continue; }
            // A queued synthesize occupies one station per shared group — count each group once.
            String dedupKey = String.valueOf(bd != null ? bd.getColonyId() : null) + "|"
                    + (bd != null ? bd.getBuildingTypeId() : "");
            if (!countedQueueGroups.add(dedupKey)) continue;
            for (WorkItem item : api.getQueue(stationId)) {
                if ("production:synthesize".equals(item.blueprintId())) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private static boolean sameRecipe(String strippedKey, JsonElement recipeParam) {
        return recipeParam != null && recipeParam.isJsonPrimitive()
                && strippedKey.equals(stripMcPrefix(recipeParam.getAsString()));
    }

    private static int intParam(JsonElement el) {
        if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            return el.getAsInt();
        }
        return 0;
    }

    private static String stripMcPrefix(String id) {
        return id != null && id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private void tryGatherElement(ResourceId resource, int deficit, @Nullable World world) {
        ElementType element;
        try {
            element = ElementType.valueOf(resource.id().toUpperCase());
        } catch (IllegalArgumentException e) {
            return;
        }

        BuildingApi api = getBuildingApi();
        if (api == null) return;

        BuildingConfigLoader configLoader = BuildingConfigLoader.getInstance();
        List<UUID> nodeBuildings = api.getBuildingsByCategory(null, "node");

        // A representative node producing this element. All nodes of an element share one
        // queue, so the picked node only needs to be a valid member of that element's group.
        UUID representativeId = null;
        int perHarvest = 0;
        int channelTicks = 0;
        String blueprint = null;
        for (UUID buildingId : nodeBuildings) {
            BuildingData bd = api.getBuilding(buildingId);
            if (bd == null || !bd.isStructureIntact()) continue;
            BuildingConfig config = configLoader.get(bd.getBuildingTypeId());
            if (config == null) continue;
            NodeConfig nodeConfig = config.nodeConfig();
            if (nodeConfig == null) continue;
            ElementType produced;
            try {
                produced = ElementType.valueOf(nodeConfig.element().toUpperCase());
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (produced != element) continue;
            representativeId = buildingId;
            perHarvest = nodeConfig.amountPerHarvest();
            channelTicks = nodeConfig.channelTicks();
            blueprint = nodeConfig.blueprint();
            break;
        }
        if (representativeId == null || perHarvest <= 0) return;

        // How many harvests are still needed beyond what's already queued/running — so
        // repeated shortfall scans don't pile up redundant gathers.
        int inFlight = countGatherInFlight(element, world);
        int remaining = Math.max(0, deficit - inFlight);
        int harvests = (remaining + perHarvest - 1) / perHarvest; // ceil
        if (harvests <= 0) return;

        // Queue the shortfall as separate single-harvest tasks on the element's shared queue;
        // idle nodes of that element claim them concurrently.
        BuildingData rep = api.getBuilding(representativeId);
        BlockPos anchor = rep != null ? rep.getPosition() : null;
        for (int i = 0; i < harvests; i++) {
            Map<String, JsonElement> params = new LinkedHashMap<>();
            if (anchor != null) params.put("anchor", posToJsonArray(anchor));
            params.put("element", new JsonPrimitive(element.name().toLowerCase()));
            params.put("amount", new JsonPrimitive(perHarvest));
            params.put("channel_ticks", new JsonPrimitive(channelTicks));
            api.enqueueWork(representativeId, new WorkItem(blueprint, params,
                    WandscapeConstants.TASK_PRIORITY_AUTO));
        }
        Log.info(TAG, "shortfall {} x{} → gather on node {} ({} harvests, {} already in flight)",
                element, deficit, representativeId.toString().substring(0, 8), harvests, inFlight);
    }

    /**
     * Sum of {@code node:gather} output for {@code element} already queued in the element's
     * shared queue or running in the pool. Counts each shared queue once (deduped by
     * colony + element) so fan-out doesn't double-enqueue.
     */
    private static int countGatherInFlight(ElementType element, @Nullable World world) {
        int total = 0;
        if (world != null) {
            for (GlobalTask t : world.taskPool.all()) {
                if (t.state == TaskState.COMPLETED) continue;
                if (!"node:gather".equals(t.blueprintId)) continue;
                if (!sameElement(element, t.taskParams.get("element"))) continue;
                total += intParam(t.taskParams.get("amount"));
            }
        }

        BuildingApi api = getBuildingApi();
        if (api == null) return total;
        Set<String> seen = new HashSet<>();
        for (UUID nodeId : api.getBuildingsByCategory(null, "node")) {
            BuildingData bd = api.getBuilding(nodeId);
            if (bd == null) continue;
            String nodeElem = nodeElement(bd);
            if (nodeElem == null) continue;
            String dedupKey = String.valueOf(bd.getColonyId()) + "|" + nodeElem;
            if (!seen.add(dedupKey)) continue;
            for (WorkItem item : api.getQueue(nodeId)) {
                if (!"node:gather".equals(item.blueprintId())) continue;
                if (!sameElement(element, item.params().get("element"))) continue;
                total += intParam(item.params().get("amount"));
            }
        }
        return total;
    }

    /** The element a node building produces, or null if it's not a node / has no node_config. */
    @Nullable
    private static String nodeElement(BuildingData bd) {
        BuildingConfig cfg = BuildingConfigLoader.getInstance().get(bd.getBuildingTypeId());
        return cfg != null && cfg.nodeConfig() != null ? cfg.nodeConfig().element() : null;
    }

    private static boolean sameElement(ElementType element, JsonElement param) {
        return param != null && param.isJsonPrimitive() && param.getAsJsonPrimitive().isString()
                && element.name().equalsIgnoreCase(param.getAsString());
    }

    @Nullable
    private static BuildingApi getBuildingApi() {
        try {
            return WandscapeApis.getBuildingApi();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static JsonArray posToJsonArray(BlockPos pos) {
        JsonArray arr = new JsonArray();
        arr.add(pos.getX());
        arr.add(pos.getY());
        arr.add(pos.getZ());
        return arr;
    }
}
