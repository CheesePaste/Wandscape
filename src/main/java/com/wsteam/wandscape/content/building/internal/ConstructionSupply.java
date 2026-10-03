package com.wsteam.wandscape.content.building.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.data.BuildingData;
import com.wsteam.wandscape.content.road.core.RoadEdge;
import com.wsteam.wandscape.content.road.engine.RoadSavedData;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.engine.pool.GlobalTask;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import com.wsteam.wandscape.content.warehouse.system.ResourceSupplySystem;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
import com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket;
import com.wsteam.wandscape.foundation.registry.WandscapeConstants;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.util.ItemKey;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工地建材补料：把未建成建筑 / 在建道路的建材缺口下成物品工坊合成任务。
 *
 * <p>三个入口，语义各不相同：
 * <ol>
 *   <li>放下建筑/道路时就地补一次（{@link #craftForBuilding}/{@link #craftForRoad}）：
 *       补上了就在建筑/道路边上记一笔，此后不再自动补（见 {@link #isAutoSupplyDone}）。</li>
 *   <li>缺料重试路径（{@link #handleAwaitingConstructionTask}）：只在还没记过账时顺延补一次——
 *       涵盖放下时殖民地还没有物品工坊、配方还没解锁这些当时补不了的情况。</li>
 *   <li>工地面板「一键制作」：玩家主动点，不记账、随时可再点，走玩家优先级档。</li>
 * </ol>
 *
 * <p>没有这一层时工地缺料走的是 {@code ResourceShortageHandler} 的通用自动补产：玩家把
 * 自动补出来的合成任务在物品工坊队列里删掉，等料任务每 40 tick 被重扫一次就再补一条，
 * 删了又回来、玩家没有任何办法喊停。这里的「只自动补一次」正是那条刹车的落点。
 */
public final class ConstructionSupply {

    private static final String TAG = "ConstructionSupply";

    private ConstructionSupply() {}

    /**
     * 一次补料的结果：{@code enqueued} 实际下发的合成任务条数、{@code covered} 已被在制任务
     * 覆盖（无需重复下发）的建材种数、{@code blocked} 有缺口却补不了的建材（没有合成配方 /
     * 配方未解锁 / 殖民地没有可用物品工坊）。
     */
    public record Result(int enqueued, int covered, List<String> blocked) {
        static final Result EMPTY = new Result(0, 0, List.of());

        /** 缺口是否已被安排（新下发或已在制）——自动补料据此决定要不要记账。 */
        public boolean arranged() {
            return enqueued > 0 || covered > 0;
        }
    }

    // ── 下发 ──

    /** 未建成建筑的建材缺口 → 该殖民地物品工坊的合成任务。 */
    public static Result craftForBuilding(BuildingState state, int priority) {
        BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        if (config == null) return Result.EMPTY;
        String sourceName = config.displayName() != null && !config.displayName().isEmpty()
                ? config.displayName()
                : state.getBuildingTypeId();
        return craft(state.getColonyId(), EnqueueHelper.computeMaterialCounts(config), priority,
                "building", state.getBuildingId().toString(), sourceName);
    }

    /** 在建道路路段的建材缺口 → 该殖民地物品工坊的合成任务。 */
    public static Result craftForRoad(RoadEdge edge, int priority) {
        return craft(edge.getColonyId(), edge.getMaterialCounts(), priority,
                "road", edge.getEdgeId().toString(), "道路施工");
    }

    /**
     * 按「需求 - 仓库库存」算每种建材的缺口，缺口里再扣掉已在制/已排队的部分，交给
     * {@link ResourceSupplySystem#enqueueSynthesize} 下单。库存已经够的建材不碰。
     */
    private static Result craft(@Nullable UUID colonyId, Map<String, Integer> demand, int priority,
                                @Nullable String sourceType, @Nullable String sourceId, @Nullable String sourceName) {
        World world = World.getActive();
        var server = ServerLifecycleHooks.getCurrentServer();
        if (world == null || server == null || colonyId == null || demand.isEmpty()) return Result.EMPTY;
        ColonyItemBank bank = ColonyItemBank.get(server.overworld());
        if (bank == null) return Result.EMPTY;

        int enqueued = 0;
        int covered = 0;
        List<String> blocked = new ArrayList<>();
        for (var entry : demand.entrySet()) {
            String itemId = entry.getKey();
            int required = entry.getValue();
            if (required <= 0) continue;

            long stock = bank.count(colonyId, ItemKey.of(itemId, null));
            if (stock >= required) continue;
            int deficit = (int) Math.min(Integer.MAX_VALUE, required - stock);

            // enqueueSynthesize 自己也会扣在制量，但它把「已覆盖」和「下发了」都返回 true；
            // 这里先算一次在制，才能把两种结果分开计数（面板回执要报真实下发条数）。
            // 按来源隔离在制量：避免同一建筑或同料建筑共享在制计数导致后续建筑漏发或误判覆盖。
            int inFlight = ResourceSupplySystem.countSynthesizeInFlight(itemId, colonyId, world, sourceType, sourceId);
            if (inFlight >= deficit) {
                covered++;
                continue;
            }
            if (ResourceSupplySystem.enqueueSynthesize(itemId, deficit, colonyId, world, priority, false,
                    sourceType, sourceId, sourceName)) {
                enqueued++;
            } else {
                blocked.add(itemId);
            }
        }
        return new Result(enqueued, covered, blocked);
    }

    // ── 自动补料只补一次 ──

    /**
     * 这条任务是不是「工地要建材」——未建成建筑的建造任务，或在建道路的路段任务。
     *
     * <p>复原任务（{@code build:place_structure} 但建筑已建成）不算工地：缺料一直自动补产，
     * 玩家点「复原」后不该逼他再点一次制作。
     */
    public static boolean isConstructionSiteTask(@Nullable String blueprintId, @Nullable UUID buildingId) {
        if ("road:build_segment".equals(blueprintId)) return true;
        if (!"build:place_structure".equals(blueprintId) && !"build:clear_and_build".equals(blueprintId)) {
            return false;
        }
        if (buildingId == null) return false; // 没挂建筑的建造任务（建镇脚手架等）不拦
        var api = WandscapeApis.getBuildingApiSilently();
        if (api == null) return false;
        BuildingData building = api.getBuilding(buildingId);
        return building != null && !building.hasEverCompleted();
    }

    /**
     * 等料任务重试路径的入口：是工地缺料就按「只自动补一次」处理并返回 true，调用方不要
     * 再走通用补产；不是工地返回 false，交回原逻辑。
     */
    public static boolean handleAwaitingConstructionTask(GlobalTask task) {
        if (!isConstructionSiteTask(task.blueprintId, task.buildingId)) return false;
        retryOnceIfPending(task);
        return true;
    }

    /** 还没自动补过料才顺延补一次；补过（或非工地）什么都不做。 */
    private static void retryOnceIfPending(GlobalTask task) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        if (task.buildingId != null) {
            BuildingSavedData sd = BuildingSavedData.get(server.overworld());
            BuildingState state = sd != null ? sd.getBuilding(task.buildingId) : null;
            if (state == null || state.hasEverCompleted() || state.isAutoSupplyDone()) return;
            Result result = craftForBuilding(state, WandscapeConstants.TASK_PRIORITY_AUTO);
            if (result.arranged()) markDone(sd, state);
            return;
        }

        UUID edgeId = parseUuid(task.taskParams.get("edge_id"));
        if (edgeId == null) return;
        RoadSavedData sd = RoadSavedData.getOrCreate(server.overworld());
        RoadEdge edge = sd.getNetwork().getEdge(edgeId);
        if (edge == null || edge.getStatus() == RoadEdge.EdgeStatus.COMPLETE || edge.isAutoSupplyDone()) return;
        Result result = craftForRoad(edge, WandscapeConstants.TASK_PRIORITY_AUTO);
        if (result.arranged()) {
            edge.setAutoSupplyDone(true);
            sd.setDirty();
        }
    }

    /**
     * 记下「这家工地已经自动补过料」。放下时就补上的走同一条记账；补不了（还没有物品工坊等）时
     * 不记账，等缺料重试路径再补一次——只要没补上，重试就一直有机会。
     */
    public static void markDone(@Nullable BuildingSavedData sd, BuildingState state) {
        state.setAutoSupplyDone(true);
        if (sd != null) sd.setDirty();
    }

    // ── 面板「一键制作」回执 ──

    /** 把一次补料的结果上屏（工地面板上的浮条，无 Screen 时落回动作栏）。 */
    public static void announce(ServerPlayer player, Result result) {
        if (result.enqueued() > 0) {
            ScreenFeedbackPacket.send(player, I18n.name(
                    "message.wandscape.constructionsite.craft_all_queued",
                    "§a[工地] 已下发 %s 项制作任务", result.enqueued()), false);
            if (!result.blocked().isEmpty()) {
                ScreenFeedbackPacket.send(player, I18n.name(
                        "message.wandscape.constructionsite.craft_all_blocked",
                        "§e[工地] 有 %s 项建材无法自动制作（没有合成配方、配方未解锁，或殖民地没有可用物品工坊）",
                        result.blocked().size()), true);
            }
        } else if (!result.blocked().isEmpty()) {
            ScreenFeedbackPacket.send(player, I18n.name(
                    "message.wandscape.constructionsite.craft_all_blocked",
                    "§e[工地] 有 %s 项建材无法自动制作（没有合成配方、配方未解锁，或殖民地没有可用物品工坊）",
                    result.blocked().size()), true);
        } else {
            ScreenFeedbackPacket.send(player, I18n.name(
                    "message.wandscape.constructionsite.craft_all_none",
                    "[工地] 没有需要制作的建材"), false);
        }
    }

    @Nullable
    private static UUID parseUuid(@Nullable JsonElement el) {
        if (el == null || !el.isJsonPrimitive()) return null;
        JsonPrimitive p = el.getAsJsonPrimitive();
        if (!p.isString()) return null;
        try {
            return UUID.fromString(p.getAsString());
        } catch (IllegalArgumentException e) {
            Log.warn(TAG, "road task carries a malformed edge_id: {}", p.getAsString());
            return null;
        }
    }
}
