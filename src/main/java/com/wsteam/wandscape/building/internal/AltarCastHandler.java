package com.wsteam.wandscape.building.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.magic.data.MagicDef;
import com.wsteam.wandscape.magic.internal.SpellbookLoader;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.internal.ColonyDeathRegistry;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.npc.internal.ReviveHandler;
import com.wsteam.wandscape.shared.data.AltarSpellInfo;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.shared.registry.WandscapeConstants;
import com.wsteam.wandscape.shared.network.ScreenFeedbackPacket;
import com.wsteam.wandscape.task.engine.pool.TaskRequest;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.ui.I18n;

/**
 * 祭坛施法服务端编排：玩家点选魔法 → 校验（altarOnly + 祭坛 CD + 小镇有魔力足够的法师）
 * → 经 {@code PlayerManualSource} 发布祭坛施法任务（NPC 接取后扣蓝执行，见 AltarCastExecutor）。
 *
 * <p>冷却按祭坛（building UUID）独立存 {@link AltarCastState}，不同祭坛之间不共享。
 * 每 server tick 由 {@link #tick} 推进冷却（接线在 Wandscape.onServerTick）。
 */
public final class AltarCastHandler {
    private static final String TAG = "AltarCastHandler";
    private static final String TASK_BLUEPRINT = "magic:altar_cast";

    private AltarCastHandler() {}

    /** 祭坛包围盒中心最上方方块顶端（复活点/法阵锚点）。 */
    public static BlockPos centerTop(BoundingBox box) {
        int cx = (box.minX() + box.maxX()) / 2;
        int cy = box.maxY();
        int cz = (box.minZ() + box.maxZ()) / 2;
        return new BlockPos(cx, cy + 1, cz);
    }

    /** 该祭坛可施放魔法列表（altarOnly）+ 各自当前祭坛 CD / 锁定状态。 */
    public static List<AltarSpellInfo> listSpells(ServerLevel level, UUID buildingId) {
        AltarCastState state = AltarCastState.get(level);
        List<AltarSpellInfo> out = new ArrayList<>();
        for (MagicDef def : SpellbookLoader.getAllSpecs().values()) {
            if (!def.altarOnly()) continue;
            out.add(new AltarSpellInfo(def.id(), def.manaCost(), def.altarCooldown(),
                    def.altarDuration(), state.getCooldown(buildingId, def.id()),
                    isAltarCastLocked(buildingId, def.id())));
        }
        return out;
    }

    /** 玩家在 AltarScreen 点选魔法 → 校验 + 发布祭坛施法任务。 */
    public static void onCastRequest(ServerPlayer player, UUID buildingId, String magicId) {
        ServerLevel level = player.serverLevel();
        MagicDef def = SpellbookLoader.getSpec(magicId);
        if (def == null || !def.altarOnly()) {
            ScreenFeedbackPacket.send(player, I18n.name("message.wandscape.altar.not_altar_only",
                    "[Wandscape] 该魔法不可在祭坛施放"), true);
            return;
        }
        var buildingApi = WandscapeApis.getBuildingApiSilently();
        if (buildingApi == null) {
            ScreenFeedbackPacket.send(player, I18n.name("message.wandscape.altar.build_system_not_ready",
                    "[Wandscape] 建筑系统未就绪"), true);
            return;
        }
        BuildingData building = buildingApi.getBuilding(buildingId);
        BoundingBox bounds = buildingApi.getBuildingBounds(buildingId);
        if (building == null || bounds == null) {
            ScreenFeedbackPacket.send(player, I18n.name("message.wandscape.altar.not_found",
                    "[Wandscape] 祭坛不存在或未完工"), true);
            return;
        }

        AltarCastState state = AltarCastState.get(level);
        int cd = state.getCooldown(buildingId, magicId);
        if (cd > 0) {
            ScreenFeedbackPacket.send(player, I18n.name("message.wandscape.altar.on_cooldown",
                    "[Wandscape] 祭坛冷却中（剩余 %.1f 秒）", cd / 20.0), true);
            return;
        }
        if (isAltarCastLocked(buildingId, magicId)) {
            // 已发布未施放 / 正在施法 —— 发布即锁定，直到施放结束
            ScreenFeedbackPacket.send(player, I18n.name("message.wandscape.altar.already_casting",
                    "[Wandscape] 该祭坛正在施法中"), true);
            return;
        }
        UUID colonyId = building.getColonyId();
        if (ReviveHandler.REVIVE_MAGIC_ID.equals(magicId)
                && ColonyDeathRegistry.get(level).latestInColony(colonyId) == null) {
            ScreenFeedbackPacket.send(player, I18n.name("message.wandscape.altar.no_revive_record",
                    "[Wandscape] 该小镇没有可复活的死亡记录"), true);
            return;
        }
        if (!hasAdequateMage(level, colonyId, def.manaCost())) {
            ScreenFeedbackPacket.send(player, I18n.name("message.wandscape.altar.no_adequate_mage",
                    "[Wandscape] 没有魔力足够（≥%d）的法师 NPC", def.manaCost()), true);
            return;
        }

        BlockPos center = centerTop(bounds);
        Map<String, JsonElement> params = new HashMap<>();
        params.put("anchor", posToJson(center));
        params.put("magic_id", new JsonPrimitive(magicId));
        params.put("altar", new JsonPrimitive(buildingId.toString()));
        params.put("mana_cost", new JsonPrimitive(def.manaCost()));
        params.put("duration", new JsonPrimitive(def.altarDuration()));
        if (colonyId != null) {
            params.put("colony_id", new JsonPrimitive(colonyId.toString()));
        }

        var source = WandscapeEngine.getPlayerManualSource();
        if (source == null) {
            ScreenFeedbackPacket.send(player, I18n.name("message.wandscape.altar.task_system_not_ready",
                    "[Wandscape] 任务系统未就绪"), true);
            return;
        }
        source.publish(new TaskRequest(TASK_BLUEPRINT, params, WandscapeConstants.QUEUE_RITUAL_ALTAR));
        Log.info(TAG, "player={} requested altar cast: altar={} magic={} manaCost={}",
                player.getName().getString(), buildingId.toString().substring(0, 8),
                magicId, def.manaCost());
        ScreenFeedbackPacket.send(player, I18n.name("message.wandscape.altar.cast_scheduled",
                "[Wandscape] 已安排祭坛施法：%s", magicId), false);
    }

    /** 每 server tick：推进所有祭坛冷却。 */
    public static void tick(ServerLevel level) {
        AltarCastState.get(level).tick();
    }

    /** 小镇内是否存在当前魔力足以支付蓝耗的 NPC（调度器分派时的最终门槛，这里做尽早反馈）。 */
    private static boolean hasAdequateMage(ServerLevel level, @Nullable UUID colonyId, int manaCost) {
        for (WandscapeNpc npc : EntityComponentBridge.INSTANCE.allNpcs().values()) {
            if (npc.isRemoved() || npc.level() != level) continue;
            if (colonyId != null && (npc.colonyId == null || !npc.colonyId.equals(colonyId))) continue;
            if (npc.getCurrentMana() >= manaCost) return true;
        }
        return false;
    }

    /** 该祭坛该魔法是否已有活跃的 altar_cast 任务（已发布未施放 / 正在施法）——发布即锁定。 */
    private static boolean isAltarCastLocked(UUID buildingId, String magicId) {
        World world = WandscapeEngine.getWorld();
        if (world == null || world.taskPool == null) return false;
        return world.taskPool.hasActiveTask(TASK_BLUEPRINT, Map.of(
                "altar", new JsonPrimitive(buildingId.toString()),
                "magic_id", new JsonPrimitive(magicId)));
    }

    private static JsonArray posToJson(BlockPos pos) {
        JsonArray arr = new JsonArray();
        arr.add(pos.getX());
        arr.add(pos.getY());
        arr.add(pos.getZ());
        return arr;
    }
}
