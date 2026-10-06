package com.wsteam.wandscape.content.magic.worldresponse;

import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 持续型世界回应的生命周期管理（每个玩家一份「正在生效的效果」表）。
 *
 * <p>它同时是**所有回滚的唯一入口**：正常停止、玩家断线、换维度、关服，四条路都汇到
 * {@link #stopAll} —— 因为效果改的是真实地形，「效果没了地形没还」是不能接受的孤儿状态。
 * 这也是企划案里「持续时间 infinity 但必须有主动关闭」的落地方式：{@link WorldResponseExecutors}
 * 负责开，配套魔法《平息》（{@code world_response_calm}）与这里负责收。
 */
public final class WorldResponseEffects {

    private static final String TAG = "WorldResponse";

    private static final Map<UUID, Map<String, WorldResponseEffect>> ACTIVE = new ConcurrentHashMap<>();
    private static boolean registered;

    private WorldResponseEffects() {}

    /** 注册全局钩子；重复调用无效（单机反复进出世界会重复走这里）。 */
    public static void register() {
        if (registered) return;
        registered = true;
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(ServerTickEvent.Post.class, WorldResponseEffects::onServerTick);
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent.class, WorldResponseEffects::onLoggedOut);
        bus.addListener(PlayerEvent.PlayerChangedDimensionEvent.class, WorldResponseEffects::onChangedDimension);
        bus.addListener(ServerStoppingEvent.class, e -> stopAll(e.getServer()));
        Log.info(TAG, "[WorldResponse] Persistent effect manager registered");
    }

    /** 激活一个持续效果；同 id 已生效时返回 false（不重复叠加）。 */
    public static boolean activate(ServerPlayer player, WorldResponseEffect effect) {
        if (player == null || effect == null) return false;
        Map<String, WorldResponseEffect> mine =
                ACTIVE.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>());
        if (mine.containsKey(effect.id())) return false;
        mine.put(effect.id(), effect);
        Log.info(TAG, "[WorldResponse] '{}' activated for {}", effect.id(), player.getGameProfile().getName());
        return true;
    }

    public static boolean isActive(ServerPlayer player, String effectId) {
        Map<String, WorldResponseEffect> mine = ACTIVE.get(player.getUUID());
        return mine != null && mine.containsKey(effectId);
    }

    public static boolean hasAny(ServerPlayer player) {
        Map<String, WorldResponseEffect> mine = ACTIVE.get(player.getUUID());
        return mine != null && !mine.isEmpty();
    }

    /** 停止该玩家全部持续效果并回滚；返回停掉的数量。 */
    public static int stopAll(ServerPlayer player) {
        if (player == null) return 0;
        Map<String, WorldResponseEffect> mine = ACTIVE.remove(player.getUUID());
        if (mine == null || mine.isEmpty()) return 0;
        for (WorldResponseEffect effect : mine.values()) {
            try {
                effect.stop(player);
            } catch (RuntimeException ex) {
                // 回滚失败必须看得见：宁可留下日志也不要静默把地形留在半途
                Log.warn(TAG, "[WorldResponse] Failed to stop '{}' for {}: {}",
                        effect.id(), player.getGameProfile().getName(), ex.toString());
            }
        }
        Log.info(TAG, "[WorldResponse] Stopped {} effect(s) for {}",
                mine.size(), player.getGameProfile().getName());
        return mine.size();
    }

    private static void stopAll(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
            stopAll(player);
        }
        ACTIVE.clear();
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (ACTIVE.isEmpty()) return;
        MinecraftServer server = event.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Map<String, WorldResponseEffect> mine = ACTIVE.get(player.getUUID());
            if (mine == null || mine.isEmpty()) continue;
            for (WorldResponseEffect effect : mine.values()) {
                try {
                    effect.tick(player);
                } catch (RuntimeException ex) {
                    Log.warn(TAG, "[WorldResponse] '{}' tick failed for {}: {} — stopping it",
                            effect.id(), player.getGameProfile().getName(), ex.toString());
                    stopEffect(player, effect.id());
                }
            }
        }
    }

    private static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            stopAll(player);   // 带着效果断线会让地形留坑：登出即回滚
        }
    }

    private static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            stopAll(player);   // 快照记的是原维度的坐标，跨维度必须先在原维度还回去
        }
    }

    private static void stopEffect(ServerPlayer player, String effectId) {
        Map<String, WorldResponseEffect> mine = ACTIVE.get(player.getUUID());
        if (mine == null) return;
        WorldResponseEffect effect = mine.remove(effectId);
        if (effect == null) return;
        try {
            effect.stop(player);
        } catch (RuntimeException ex) {
            Log.warn(TAG, "[WorldResponse] Rollback failed for '{}': {}", effectId, ex.toString());
        }
        if (mine.isEmpty()) ACTIVE.remove(player.getUUID());
    }

    /** 只读快照，供调试/测试命令查询（不要拿它改状态）。 */
    public static List<String> activeIds(ServerPlayer player) {
        Map<String, WorldResponseEffect> mine = ACTIVE.get(player.getUUID());
        if (mine == null) return List.of();
        return Collections.unmodifiableList(new ArrayList<>(mine.keySet()));
    }
}
