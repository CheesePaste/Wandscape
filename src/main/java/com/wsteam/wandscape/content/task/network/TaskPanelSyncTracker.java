package com.wsteam.wandscape.content.task.network;
import com.wsteam.wandscape.content.building.data.BuildingData;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.content.building.internal.BuildingSavedData;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.content.task.component.ColonyMember;
import com.wsteam.wandscape.content.task.component.TaskExecutor;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.npc.types.AttributeType;
import com.wsteam.wandscape.content.task.types.ResourceStack;
import com.wsteam.wandscape.impl.WandscapeEngine;
import com.wsteam.wandscape.content.production.ProductionEligibility;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.api.ColonyApi;
import com.wsteam.wandscape.api.WarehouseApi;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.content.building.data.WorkItem;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.task.engine.pool.BuildingTaskPool;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.content.task.runtime.ExecutorState;
import com.wsteam.wandscape.content.task.runtime.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side tracking & throttled synchronization for players currently viewing
 * the Task & Mage Management Panel.
 *
 * <p>Pushes {@link TaskManagementSyncPacket} every 10 ticks (0.5s) to subscribed players.
 */
public final class TaskPanelSyncTracker {

    private static final String TAG = "TaskPanelSync";
    private static final Set<UUID> subscribedPlayers = ConcurrentHashMap.newKeySet();
    private static volatile boolean dirty = true;
    private static int tickCounter = 0;

    private TaskPanelSyncTracker() {}

    public static void subscribe(ServerPlayer player) {
        subscribedPlayers.add(player.getUUID());
        dirty = true;
        syncPlayer(player);
    }

    public static void unsubscribe(ServerPlayer player) {
        subscribedPlayers.remove(player.getUUID());
    }

    public static void markDirty() {
        dirty = true;
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            subscribedPlayers.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (subscribedPlayers.isEmpty()) return;

        tickCounter++;
        if (tickCounter % 10 != 0 && !dirty) return;
        dirty = false;

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        for (UUID playerId : subscribedPlayers) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null && !player.isRemoved()) {
                syncPlayer(player);
            }
        }
    }

    private static void syncPlayer(ServerPlayer player) {
        ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi == null) return;

        UUID colonyId = colonyApi.getColonyByFounder(player.getUUID());
        if (colonyId == null) {
            colonyId = colonyApi.getColonyId(player.blockPosition());
        }
        if (colonyId == null) return;

        World world = WandscapeEngine.getWorld();
        if (world == null || world.taskPool == null) return;

        TaskManagementSyncPacket packet = buildSnapshot(world, colonyId, player);
        PacketDistributor.sendToPlayer(player, packet);
    }

    public static TaskManagementSyncPacket buildSnapshot(World world, UUID colonyId, ServerPlayer player) {
        var serverLevel = player.serverLevel();
        BuildingSavedData buildingData = BuildingSavedData.get(serverLevel);
        WarehouseApi warehouseApi = WandscapeApis.getWarehouseApiSilently();

        // 1. Collect all global tasks for this colony
        List<TaskSummaryDto> taskDtos = new ArrayList<>();
        int activeTaskCount = 0;

        for (GlobalTask task : world.taskPool.all()) {
            if (task.state == TaskState.COMPLETED) continue;

            // Check colony ownership: if buildingId present, match building's colony; otherwise match player's colony
            if (task.buildingId != null) {
                BuildingState bs = buildingData.getBuilding(task.buildingId);
                if (bs != null && !colonyId.equals(bs.getColonyId())) {
                    continue;
                }
            }

            activeTaskCount++;

            String category = extractCategory(task);
            String title = extractTitle(task, buildingData);
            String buildingName = extractBuildingName(task, buildingData);

            long assignedNpcId = task.assignedNpcId != null ? task.assignedNpcId : -1;
            UUID assignedNpcUuid = null;
            String assignedNpcName = "";
            if (assignedNpcId >= 0) {
                WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(assignedNpcId);
                if (npc != null) {
                    assignedNpcUuid = npc.getUUID();
                    assignedNpcName = npc.getName().getString();
                }
            }

            List<ResourceShortageDto> shortages = new ArrayList<>();
            if (task.awaitingResource != null) {
                for (ResourceStack stack : task.awaitingResource) {
                    String resId = stack.resource().id();
                    int required = stack.amount();
                    int available = 0;
                    if (warehouseApi != null) {
                        try {
                            ElementType elemType = ElementType.fromId(resId);
                            available = (int) warehouseApi.getElement(colonyId, elemType);
                        } catch (Exception ignored) {
                            available = 0;
                        }
                    }
                    String displayName = formatResourceName(resId);
                    shortages.add(new ResourceShortageDto("element", resId, displayName, required, available));
                }
            }

            boolean hasTarget = false;
            int tx = 0, ty = 0, tz = 0;
            if (task.buildingId != null) {
                BuildingState bs = buildingData.getBuilding(task.buildingId);
                if (bs != null && bs.getAnchor() != null) {
                    hasTarget = true;
                    tx = bs.getAnchor().getX();
                    ty = bs.getAnchor().getY();
                    tz = bs.getAnchor().getZ();
                }
            }

            String stateStr = task.state.name();
            String blockerReason = determineBlocker(task, world, colonyId);

            int totalSteps = task.sequence != null ? task.sequence.size() : 1;

            taskDtos.add(new TaskSummaryDto(
                    task.id, category, title,
                    task.blueprintId != null ? task.blueprintId : "",
                    task.buildingId, buildingName,
                    stateStr, task.priority, task.stepIndex, totalSteps,
                    task.channelRemainingTicks, 0,
                    assignedNpcId, assignedNpcUuid, assignedNpcName,
                    shortages, hasTarget, tx, ty, tz, blockerReason
            ));
        }

        // 3. Collect Mages
        List<MageSummaryDto> mageDtos = new ArrayList<>();
        int idleMageCount = 0;
        int totalMageCount = 0;

        for (Map.Entry<Long, WandscapeNpc> entry : EntityComponentBridge.INSTANCE.allNpcs().entrySet()) {
            long ecsId = entry.getKey();
            WandscapeNpc npc = entry.getValue();
            if (npc == null || npc.isRemoved()) continue;

            ColonyMember member = world.get(ecsId, ColonyMember.class);
            if (member == null || !colonyId.equals(member.colonyId())) continue;

            totalMageCount++;

            TaskExecutor exec = world.get(ecsId, TaskExecutor.class);
            boolean isIdle = exec == null || (exec.state == ExecutorState.IDLE && exec.npcQueue.isIdle() && exec.globalTaskId == null);
            if (isIdle && !npc.isFollowMode() && !npc.isResting()) {
                idleMageCount++;
            }

            String state = "IDLE";
            if (npc.isFollowMode()) {
                state = "FOLLOWING";
            } else if (npc.isResting()) {
                state = "RESTING";
            } else if (exec != null && exec.state != ExecutorState.IDLE) {
                state = exec.pendingFutureIsNav ? "MOVING" : "CASTING";
            }

            float hp = npc.getHealth();
            float maxHp = npc.getMaxHealth();
            float mana = npc.getCurrentMana();
            float maxMana = npc.getMaxMana();
            float sp = npc.getEffectiveAttribute(AttributeType.SPELL_POWER);
            float ws = npc.getEffectiveAttribute(AttributeType.WORK_SPEED);
            float ss = npc.getEffectiveAttribute(AttributeType.SPELL_SPEED);
            // 护甲显示总护甲 = vanilla ARMOR 有效值（槽内盔甲由原版结算，包含天生+法杖+槽内盔甲）
            float ar = npc.getEffectiveArmorValue();

            String currentTaskTitle = "";
            long currentTaskId = exec != null && exec.globalTaskId != null ? exec.globalTaskId : -1;
            if (currentTaskId >= 0) {
                GlobalTask t = world.taskPool.get(currentTaskId);
                if (t != null) {
                    currentTaskTitle = extractTitle(t, buildingData);
                }
            }

            String equippedWand = "";
            var mainHand = npc.getMainHandItem();
            if (!mainHand.isEmpty()) {
                equippedWand = mainHand.getHoverName().getString();
            }

            mageDtos.add(new MageSummaryDto(
                    ecsId, npc.getUUID(), npc.getId(),
                    npc.getName().getString(), state,
                    hp, maxHp, mana, maxMana,
                    sp, ws, ss, ar,
                    currentTaskTitle, currentTaskId, equippedWand,
                    npc.getX(), npc.getY(), npc.getZ(),
                    npc.isFollowMode(), npc.isPeaceMode()
            ));
        }

        // 4. Collect Production Groups (Workstations, Alchemy, Magic Workshops, Nodes)
        List<ProductionGroupDto> productionGroups = buildProductionGroups(world, buildingData, colonyId, warehouseApi);

        return new TaskManagementSyncPacket(colonyId, taskDtos, productionGroups, mageDtos, activeTaskCount, idleMageCount, totalMageCount);
    }

    private static String extractCategory(GlobalTask task) {
        if (task.blueprintId != null) {
            if (task.blueprintId.contains("build") || task.blueprintId.contains("construction")) return "build";
            if (task.blueprintId.contains("gather") || task.blueprintId.contains("mine")) return "gather";
            if (task.blueprintId.contains("craft") || task.blueprintId.contains("synthesize")) return "craft";
            if (task.blueprintId.contains("decompose")) return "decompose";
            if (task.blueprintId.contains("guard") || task.blueprintId.contains("attack")) return "guard";
            if (task.blueprintId.contains("altar")) return "altar";
            if (task.blueprintId.contains("repair")) return "repair";
        }
        return "task";
    }

    private static String extractTitle(GlobalTask task, BuildingSavedData buildingData) {
        if (task.sequence != null && task.sequence.label() != null && !task.sequence.label().isEmpty()) {
            return task.sequence.label();
        }
        if (task.buildingId != null) {
            BuildingState bs = buildingData.getBuilding(task.buildingId);
            if (bs != null) {
                return formatBuildingName(bs) + " 任务";
            }
        }
        return task.blueprintId != null ? task.blueprintId : "未知任务 #" + task.id;
    }

    private static String extractBuildingName(GlobalTask task, BuildingSavedData buildingData) {
        if (task.buildingId != null) {
            BuildingState bs = buildingData.getBuilding(task.buildingId);
            if (bs != null) {
                return formatBuildingName(bs);
            }
        }
        return "";
    }

    private static String formatBuildingName(BuildingState bs) {
        var cfg = BuildingConfigLoader.getInstance().get(bs.getBuildingTypeId());
        if (cfg != null && cfg.displayName() != null) {
            return cfg.displayName();
        }
        return bs.getBuildingTypeId();
    }

    private static String formatResourceName(String elementId) {
        return switch (elementId) {
            case "earth" -> "地元素";
            case "wood" -> "木元素";
            case "water" -> "水元素";
            case "fire" -> "火元素";
            case "wind" -> "风元素";
            case "metal" -> "金元素";
            case "dark" -> "暗元素";
            default -> elementId;
        };
    }

    private static String determineBlocker(GlobalTask task, World world, UUID colonyId) {
        if (task.state == TaskState.AWAITING_RESOURCES) {
            return "MISSING_RESOURCES";
        }
        if (task.state == TaskState.PENDING_ASSIGN) {
            int idleMages = 0;
            for (Map.Entry<Long, WandscapeNpc> entry : EntityComponentBridge.INSTANCE.allNpcs().entrySet()) {
                ColonyMember m = world.get(entry.getKey(), ColonyMember.class);
                if (m != null && colonyId.equals(m.colonyId())) {
                    TaskExecutor exec = world.get(entry.getKey(), TaskExecutor.class);
                    if (exec != null && exec.state == ExecutorState.IDLE && exec.npcQueue.isIdle() && exec.globalTaskId == null
                            && !entry.getValue().isFollowMode() && !entry.getValue().isResting()) {
                        idleMages++;
                    }
                }
            }
            if (idleMages == 0) {
                return "WAITING_NPC";
            }
            return "SCHEDULING";
        }
        if (task.state == TaskState.IN_PROGRESS) {
            return "IN_PROGRESS";
        }
        return "NONE";
    }

    private static List<ProductionGroupDto> buildProductionGroups(World world, BuildingSavedData buildingData, UUID colonyId, WarehouseApi warehouseApi) {
        List<ProductionGroupDto> groups = new ArrayList<>();
        BuildingTaskPool buildingTaskPool = world.buildingTaskPool;

        // Check active node:gather tasks and which elements are actively being harvested
        Set<String> activeGatherElements = new HashSet<>();
        for (GlobalTask gt : world.taskPool.all()) {
            if (gt.state != TaskState.COMPLETED && "node:gather".equals(gt.blueprintId)) {
                if (gt.buildingId != null) {
                    BuildingState nbs = buildingData.getBuilding(gt.buildingId);
                    if (nbs != null && nbs.getBuildingTypeId() != null) {
                        String btype = nbs.getBuildingTypeId();
                        if (btype.startsWith("node")) {
                            activeGatherElements.add(btype.substring(4)); // e.g. "nodefire" -> "fire"
                        }
                    }
                }
            }
        }

        // Snapshot of available elements
        Map<ElementType, Long> elementSnapshot = new LinkedHashMap<>();
        if (warehouseApi != null) {
            for (ElementType et : ElementType.values()) {
                try {
                    elementSnapshot.put(et, warehouseApi.getElement(colonyId, et));
                } catch (Exception ignored) {}
            }
        }

        for (BuildingState bs : buildingData.getAllBuildings()) {
            if (bs == null || !colonyId.equals(bs.getColonyId())) continue;

            String category = bs.getCategory();
            boolean isProdCategory = "workstation".equals(category) || "magic_workshop".equals(category)
                    || "alchemy".equals(category) || "node".equals(category);
            Deque<WorkItem> localQueue = bs.getTaskQueue();
            boolean hasQueue = localQueue != null && !localQueue.isEmpty();
            boolean hasHead = buildingTaskPool != null && buildingTaskPool.hasHead(bs.getBuildingId());

            if (!isProdCategory && !hasQueue && !hasHead) continue;

            String bName = formatBuildingName(bs);
            int activeWorkers = 0;
            List<ProductionItemDto> items = new ArrayList<>();
            long virtualId = -5000;

            // 1. Running Head Task
            if (buildingTaskPool != null && buildingTaskPool.hasHead(bs.getBuildingId())) {
                Long headTaskId = buildingTaskPool.getHeadTaskId(bs.getBuildingId());
                if (headTaskId != null) {
                    GlobalTask head = world.taskPool.get(headTaskId);
                    if (head != null && head.state != TaskState.COMPLETED) {
                        activeWorkers++;
                        long assignedNpcId = head.assignedNpcId != null ? head.assignedNpcId : -1;
                        String assignedNpcName = "";
                        if (assignedNpcId >= 0) {
                            WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(assignedNpcId);
                            if (npc != null) assignedNpcName = npc.getName().getString();
                        }
                        int totalSteps = head.sequence != null ? head.sequence.size() : 1;
                        float progress = totalSteps > 0 ? (float) (head.stepIndex + 1) / totalSteps : 0.5f;
                        String bid = head.blueprintId != null ? head.blueprintId : "";
                        String itemOrRecipeId = extractItemOrRecipeIdJson(bid, head.taskParams);
                        String displayName = formatItemDisplayName(bid, itemOrRecipeId);
                        int count = paramIntJson(head.taskParams, "count", 1);
                        String cat = extractCategory(head);

                        items.add(new ProductionItemDto(
                                head.id, 0, cat, bid, itemOrRecipeId, displayName, count,
                                "RUNNING", assignedNpcId, assignedNpcName, progress,
                                List.of(), List.of(), "正在执行中", false
                        ));
                    }
                }
            }

            // 2. Queued WorkItems
            if (localQueue != null) {
                int qIndex = 1;
                for (WorkItem item : localQueue) {
                    virtualId--;
                    String bid = item.blueprintId();
                    String cat = categorizeWorkItem(bid);
                    String itemOrRecipeId = extractItemOrRecipeIdJson(bid, item.params());
                    String displayName = formatItemDisplayName(bid, itemOrRecipeId);
                    int count = paramIntJson(item.params(), "count", 1);

                    // Required elements & shortages
                    Map<ElementType, Long> req = ProductionEligibility.requiredElements(bid, item.params());
                    List<ResourceShortageDto> elementCosts = new ArrayList<>();
                    List<String> missingElems = new ArrayList<>();
                    boolean isShort = false;

                    for (var entry : req.entrySet()) {
                        ElementType et = entry.getKey();
                        long needAmt = entry.getValue();
                        long availAmt = elementSnapshot.getOrDefault(et, 0L);
                        elementCosts.add(new ResourceShortageDto("element", et.getId(), formatResourceName(et.getId()), (int) needAmt, (int) availAmt));
                        if (availAmt < needAmt) {
                            isShort = true;
                            missingElems.add(et.getId());
                        }
                    }

                    boolean isGathering = false;
                    for (String me : missingElems) {
                        if (activeGatherElements.contains(me)) {
                            isGathering = true;
                            break;
                        }
                    }

                    String status = isShort ? "MISSING_ELEMENTS" : "QUEUED";
                    String reason = item.params() != null && item.params().containsKey("reason")
                            ? item.params().get("reason").getAsString()
                            : (bid.startsWith("production:synthesize") ? "自动化供应链补料" : "工坊手动排队");

                    items.add(new ProductionItemDto(
                            virtualId, qIndex++, cat, bid, itemOrRecipeId, displayName, count,
                            status, -1, "", 0f,
                            elementCosts, missingElems, reason, isGathering
                    ));
                }
            }

            if (!items.isEmpty()) {
                BlockPos anchor = bs.getAnchor() != null ? bs.getAnchor() : BlockPos.ZERO;
                groups.add(new ProductionGroupDto(
                        bs.getBuildingId(), bName, category != null ? category : "workstation",
                        anchor.getX(), anchor.getY(), anchor.getZ(),
                        activeWorkers, items
                ));
            }
        }
        return groups;
    }

    private static String categorizeWorkItem(String blueprintId) {
        if (blueprintId.equals("production:decompose")) return "decompose";
        if (blueprintId.equals("production:synthesize")) return "synthesize";
        if (blueprintId.equals("production:craft")) return "craft";
        if (blueprintId.equals("production:craft_spell")) return "transcribe";
        if (blueprintId.startsWith("build:")) return "build";
        if (blueprintId.equals("node:gather")) return "gather";
        return "other";
    }

    private static String extractItemOrRecipeIdJson(String blueprintId, Map<String, JsonElement> params) {
        if (params == null) return "";
        String out = paramStrJson(params, "output_item");
        if (out != null) return out;
        String item = paramStrJson(params, "item_id");
        if (item != null) return item;
        String recipe = paramStrJson(params, "recipe_id");
        if (recipe != null) return recipe;
        String el = paramStrJson(params, "element");
        if (el != null) return el;
        return "";
    }

    private static String formatItemDisplayName(String blueprintId, String itemOrRecipeId) {
        if (itemOrRecipeId == null || itemOrRecipeId.isEmpty()) {
            return blueprintId != null ? blueprintId : "未知生产项";
        }
        if (itemOrRecipeId.startsWith("minecraft:") || itemOrRecipeId.startsWith("wandscape:")) {
            var rl = ResourceLocation.tryParse(itemOrRecipeId);
            if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) {
                return BuiltInRegistries.ITEM.get(rl).getDescription().getString();
            }
        }
        return formatResourceName(itemOrRecipeId);
    }

    private static String paramStrJson(Map<String, JsonElement> params, String key) {
        if (params == null) return null;
        JsonElement el = params.get(key);
        return (el instanceof JsonPrimitive p && p.isString()) ? p.getAsString() : null;
    }

    private static int paramIntJson(Map<String, JsonElement> params, String key, int fallback) {
        if (params == null) return fallback;
        JsonElement el = params.get(key);
        return (el instanceof JsonPrimitive p && p.isNumber()) ? p.getAsInt() : fallback;
    }
}
