package com.wsteam.wandscape.content.magic.worldresponse;

import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.util.BalanceValues;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 持续型世界回应的生命周期管理（每个玩家一份「正在生效的效果」表）。
 *
 * <p>它同时是**所有回滚的唯一入口**：正常停止、玩家断线、**传送 / 换维度**、关服，五条路都汇到
 * {@link #stopAll} —— 因为效果改的是真实地形，「效果没了地形没还」是不能接受的孤儿状态。
 * 这也是企划案里「持续时间 infinity 但必须有主动关闭」的落地方式：{@link WorldResponseExecutors}
 * 负责开，配套魔法《平息》（{@code world_response_calm}）与这里负责收。
 *
 * <p><b>传送要在传送发生之前停</b>：快照记的是**原地**的方块坐标，人被传到很远之后那些区块会卸载，
 * 回滚就得回头去碰已经不在内存里的区块。所以监听的是 `EntityTravelToDimensionEvent`（换维度之前）与
 * `EntityTeleportEvent`（/tp、末影珍珠、紫颂果、spreadplayers 等），而不是事后事件。
 * 整合包里有些传送（传送石碑之类）不走这两个事件，于是再加一条兜底：**每 tick 位移超过
 * {@code worldResponseTeleportJumpDistance} 格**（正常跑跳/鞘翅/激流都到不了）就按传送处理。
 * 停下之后玩家重新施放即可——比起"顺手把某台机器的核心方块留在地上一个大洞"，宁可少维持一会儿。
 *
 * <p><b>还不了的方块不许丢</b>：回滚遇到未加载的区块时效果会返回 false，这里把它挂进
 * {@link #PENDING_ROLLBACK} 每 {@value #ROLLBACK_RETRY_TICKS} tick 重试一次（区块回来了就还上）；
 * 关服那一次是最后机会，允许为此把区块读回来——`ServerStoppingEvent` 在存档之前触发
 * （`MinecraftServer.runServer` 的 finally 里先 handleServerStopping 再 stopServer），所以这一次
 * 写回去的方块会落盘。非正常关服（崩溃/断电）仍可能留下坑，这是已知取舍。
 */
public final class WorldResponseEffects {

    private static final String TAG = "WorldResponse";
    /** 回滚重试间隔（tick）：给未加载区块留出"回来"的时间，也避免每 tick 遍历。 */
    private static final int ROLLBACK_RETRY_TICKS = 20;

    private static final Map<UUID, Map<String, WorldResponseEffect>> ACTIVE = new ConcurrentHashMap<>();
    /** 还剩下未加载区块没还干净的效果：绝不丢掉，等区块回来或关服收尾。 */
    private static final List<WorldResponseEffect> PENDING_ROLLBACK = new CopyOnWriteArrayList<>();
    /** 上一 tick 的位置，用来识别"没有事件的那种传送"。 */
    private static final Map<UUID, Vec3> LAST_POS = new ConcurrentHashMap<>();
    private static int retryCooldown;
    private static boolean registered;

    private WorldResponseEffects() {}

    /** 注册全局钩子；重复调用无效（单机反复进出世界会重复走这里）。 */
    public static void register() {
        if (registered) return;
        registered = true;
        var bus = NeoForge.EVENT_BUS;
        bus.addListener(ServerTickEvent.Post.class, WorldResponseEffects::onServerTick);
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent.class, WorldResponseEffects::onLoggedOut);
        // 传送/换维度一律用「之前」的事件：这两条在传送发生前触发，回滚还来得及在原地区块里做完
        bus.addListener(EntityTravelToDimensionEvent.class, WorldResponseEffects::onTravelToDimension);
        bus.addListener(EntityTeleportEvent.class, WorldResponseEffects::onTeleport);
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
        LAST_POS.remove(player.getUUID());
        if (mine == null || mine.isEmpty()) return 0;
        for (WorldResponseEffect effect : mine.values()) {
            rollback(player, effect);
        }
        Log.info(TAG, "[WorldResponse] Stopped {} effect(s) for {}",
                mine.size(), player.getGameProfile().getName());
        return mine.size();
    }

    /** 回滚一个效果；没还干净（方块所在区块没加载）就挂进重试表，**不丢快照**。 */
    private static void rollback(@Nullable ServerPlayer player, WorldResponseEffect effect) {
        try {
            if (!effect.stop(player, false)) {
                PENDING_ROLLBACK.add(effect);
                Log.info(TAG, "[WorldResponse] '{}' has block(s) in unloaded chunks — queued for retry", effect.id());
            }
        } catch (RuntimeException ex) {
            // 回滚失败必须看得见：宁可留下日志也不要静默把地形留在半途
            Log.warn(TAG, "[WorldResponse] Failed to roll back '{}': {}", effect.id(), ex.toString());
        }
    }

    private static void stopAll(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
            stopAll(player);
        }
        ACTIVE.clear();
        LAST_POS.clear();
        forceFinalRollback();
    }

    /** 关服收尾：这是最后一次机会，允许为回滚把未加载的区块读回来。 */
    private static void forceFinalRollback() {
        if (PENDING_ROLLBACK.isEmpty()) return;
        retryPending(true);
        if (!PENDING_ROLLBACK.isEmpty()) {
            Log.warn(TAG, "[WorldResponse] {} effect(s) still hold un-restored block(s) — the world may keep a hole",
                    PENDING_ROLLBACK.size());
            PENDING_ROLLBACK.clear();
        }
    }

    private static void retryPending(boolean loadChunks) {
        for (WorldResponseEffect effect : PENDING_ROLLBACK) {
            boolean done;
            try {
                done = effect.stop(null, loadChunks);
            } catch (RuntimeException ex) {
                Log.warn(TAG, "[WorldResponse] Retried rollback of '{}' failed: {}", effect.id(), ex.toString());
                done = true;   // 一直抛异常的效果不该每 20 tick 刷一次屏
            }
            if (done) PENDING_ROLLBACK.remove(effect);
        }
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (--retryCooldown <= 0) {
            retryCooldown = ROLLBACK_RETRY_TICKS;
            if (!PENDING_ROLLBACK.isEmpty()) retryPending(false);
        }
        if (ACTIVE.isEmpty()) {
            if (!LAST_POS.isEmpty()) LAST_POS.clear();
            return;
        }
        MinecraftServer server = event.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Map<String, WorldResponseEffect> mine = ACTIVE.get(player.getUUID());
            if (mine == null || mine.isEmpty()) {
                LAST_POS.remove(player.getUUID());
                continue;
            }
            if (jumpedTooFar(player)) {   // 没有事件的那种传送（整合包传送石碑等）的兜底
                Log.info(TAG, "[WorldResponse] {} moved too far in one tick — treating it as a teleport",
                        player.getGameProfile().getName());
                stopForTeleport(player);
                continue;
            }
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

    /**
     * 单 tick 位移突变检测。阈值默认 16 格：玩家自由落体终速约 4 格/tick、鞘翅火箭与激流远低于它，
     * 所以正常的"跑得快"不会误伤；而整合包传送石碑这类没有事件的传送通常是几十到上千格。
     */
    private static boolean jumpedTooFar(ServerPlayer player) {
        Vec3 now = player.position();
        Vec3 last = LAST_POS.put(player.getUUID(), now);
        if (last == null) return false;
        double limit = Math.max(4, BalanceValues.worldResponseTeleportJumpDistance());
        return now.distanceToSqr(last) > limit * limit;
    }

    private static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            stopAll(player);   // 带着效果断线会让地形留坑：登出即回滚
            WorldResponseManager.cancelPending(player.getUUID());   // 人都走了，留着选择窗口没意义
        }
    }

    private static void onTravelToDimension(EntityTravelToDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            stopForTeleport(player);   // 换维度之前：这时人还在原维度，快照所在区块必然加载着
        }
    }

    private static void onTeleport(EntityTeleportEvent event) {
        // 这个监听器挂在父类上，子类（/tp、spreadplayers、末影珍珠、紫颂果、末影人瞬移）都会进来；
        // 非玩家实体的瞬移会被下面的 instanceof 挡掉
        if (event.getEntity() instanceof ServerPlayer player) {
            stopForTeleport(player);
        }
    }

    private static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            stopForTeleport(player);   // 上面那条 pre 事件没兜住时的补网（已经停过就不会重复提示）
        }
    }

    private static void stopForTeleport(ServerPlayer player) {
        // 还没选完的轮盘窗口也一并作废：开着轮盘被传走再确认，效果会落在新位置
        WorldResponseManager.cancelPending(player.getUUID());
        if (stopAll(player) <= 0) return;
        // 传送把它打断这件事得说一声：不然玩家只会看到地形还回去了、魔法无声消失
        player.displayClientMessage(I18n.name("message.wandscape.world_response.teleport_stop",
                "传送打断了世界应答，需要时再施放一次"), true);
    }

    private static void stopEffect(ServerPlayer player, String effectId) {
        Map<String, WorldResponseEffect> mine = ACTIVE.get(player.getUUID());
        if (mine == null) return;
        WorldResponseEffect effect = mine.remove(effectId);
        if (effect == null) return;
        if (mine.isEmpty()) ACTIVE.remove(player.getUUID());
        rollback(player, effect);
    }

    /** 只读快照，供调试/测试命令查询（不要拿它改状态）。 */
    public static List<String> activeIds(ServerPlayer player) {
        Map<String, WorldResponseEffect> mine = ACTIVE.get(player.getUUID());
        if (mine == null) return List.of();
        return Collections.unmodifiableList(new ArrayList<>(mine.keySet()));
    }
}
