package com.wsteam.wandscape.shared.network.tasks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.core.component.ColonyMember;
import com.wsteam.wandscape.core.component.Position;
import com.wsteam.wandscape.core.component.TaskExecutor;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.api.WarehouseApi;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.task.engine.pool.BuildingTaskPool;
import com.wsteam.wandscape.task.engine.pool.BuildingTaskQueue;
import com.wsteam.wandscape.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.task.runtime.ExecutorState;
import com.wsteam.wandscape.task.runtime.TaskState;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

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

        // 2. Collect queued building WorkItems (if any)
        BuildingTaskPool buildingTaskPool = null;
        try {
            var field = WandscapeEngine.class.getDeclaredField("buildingTaskPool");
            field.setAccessible(true);
            buildingTaskPool = (BuildingTaskPool) field.get(null);
        } catch (Exception ignored) {}

        if (buildingTaskPool != null) {
            long queuedVirtualId = -1000;
            for (Map.Entry<UUID, BuildingTaskQueue> entry : buildingTaskPool.getAll().entrySet()) {
                UUID bId = entry.getKey();
                BuildingState bs = buildingData.getBuilding(bId);
                if (bs == null || !colonyId.equals(bs.getColonyId())) continue;

                BuildingTaskQueue q = entry.getValue();
                String bName = formatBuildingName(bs);
                int index = 1;
                for (WorkItem item : q.getPending()) {
                    queuedVirtualId--;
                    String title = formatWorkItemTitle(item, bName, index++);
                    taskDtos.add(new TaskSummaryDto(
                            queuedVirtualId, "queued", title,
                            item.blueprintId(), bId, bName,
                            "QUEUED", item.priority(), 0, 1,
                            -1, 0,
                            -1, null, "",
                            List.of(), true,
                            bs.getAnchor().getX(), bs.getAnchor().getY(), bs.getAnchor().getZ(),
                            "QUEUED_STAGE"
                    ));
                }
            }
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

        return new TaskManagementSyncPacket(colonyId, taskDtos, mageDtos, activeTaskCount, idleMageCount, totalMageCount);
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

    private static String formatWorkItemTitle(WorkItem item, String buildingName, int stage) {
        return buildingName + " (待办 #" + stage + ")";
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
}
