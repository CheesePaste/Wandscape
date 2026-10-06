package com.wsteam.wandscape.content.magic.worldresponse;

import com.wsteam.wandscape.content.magic.network.WorldResponseOpenPacket;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.Net;
import com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 《世界应答》的两阶段施法状态机（服务端权威）。
 *
 * <p>阶段一：玩家施放（卷轴 / 命令 → {@code MagicSpellExecutors.castForPlayer}）只**打开选择**，
 * 不产生任何世界效果；服务端记一条 pending（默认 5 秒）并把可选回应下发给客户端。
 * 阶段二：客户端轮盘选中后回包，这里校验 pending 未过期、id 合法，再交给
 * {@link WorldResponseExecutors} 执行；**执行成功才进冷却**（失败/尚未实现不进，便于反复调试）。
 *
 * <p>pending 是防伪造的闸门：没有 pending 的选择包一律不认——客户端本地开屏的方案做不到这点。
 * 状态是内存态（测试期不落盘）：pending 过期即失效、冷却按世界游戏刻计时，两者都在
 * {@link #begin} 与 {@link #choose} 里顺手修剪过期项，表长受玩家数上界约束。
 */
public final class WorldResponseManager {

    private static final String TAG = "WorldResponse";

    /** 选择窗口：5 秒不选自动作废（与客户端轮盘的超时提示一致）。 */
    private static final int PENDING_TICKS = 100;

    private record Pending(long deadline, int cooldownTicks) {}

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> COOLDOWN_UNTIL = new ConcurrentHashMap<>();

    private WorldResponseManager() {}

    /**
     * 阶段一：请求打开选择轮盘。
     *
     * @param cooldownTicks 选择并执行成功后的冷却（取 {@code MagicDef.baseCooldown}）
     * @return 是否真的打开了（冷却中/已在选择中返回 false，调用方据此给玩家反馈）
     */
    public static boolean begin(ServerPlayer player, int cooldownTicks) {
        if (player == null) return false;
        long now = player.serverLevel().getGameTime();
        UUID id = player.getUUID();
        prune(now);

        Long cd = COOLDOWN_UNTIL.get(id);
        if (cd != null && cd > now) {
            // 兜底串里的占位符一律 %s（%.1f/%d 不会被 Language 归一，玩家会看到字面量）
            feedback(player, "message.wandscape.world_response.cooldown",
                    "世界仍在平静 —— %s 秒后才会再次回应", String.format("%.1f", (cd - now) / 20.0));
            return false;
        }
        Pending existing = PENDING.get(id);
        if (existing != null && existing.deadline() > now) {
            feedback(player, "message.wandscape.world_response.pending",
                    "世界正在聆听你的意志 —— 先选一个回应");
            return false;
        }

        PENDING.put(id, new Pending(now + PENDING_TICKS, Math.max(0, cooldownTicks)));
        Net.toPlayer(player, new WorldResponseOpenPacket(WorldResponse.ids()));
        Log.info(TAG, "[WorldResponse] Picker opened for {} ({} responses)",
                player.getGameProfile().getName(), WorldResponse.ids().size());
        return true;
    }

    /**
     * 阶段二：玩家在轮盘上选定回应。空 id 表示取消（关闭轮盘），只清 pending、不进冷却。
     */
    public static void choose(ServerPlayer player, String responseId) {
        if (player == null) return;
        long now = player.serverLevel().getGameTime();
        UUID id = player.getUUID();

        Pending pending = PENDING.remove(id);
        if (pending == null || pending.deadline() < now) {
            // 过期/无 pending：可能是网络乱序或伪造，明确拒绝并记一条
            Log.info(TAG, "[WorldResponse] Choice '{}' from {} without a live pending cast — ignored",
                    responseId, player.getGameProfile().getName());
            feedback(player, "message.wandscape.world_response.expired",
                    "世界已经平静下来 —— 重新施放【世界应答】");
            return;
        }

        if (responseId == null || responseId.isEmpty()) {
            Log.info(TAG, "[WorldResponse] {} cancelled the picker", player.getGameProfile().getName());
            return;
        }

        WorldResponse response = WorldResponse.byId(responseId);
        if (response == null) {
            Log.warn(TAG, "[WorldResponse] Unknown response id '{}' from {}",
                    responseId, player.getGameProfile().getName());
            feedback(player, "message.wandscape.world_response.unknown",
                    "世界听不懂这个意志：%s", responseId);
            return;
        }

        if (WorldResponseExecutors.execute(player, response) && pending.cooldownTicks() > 0) {
            COOLDOWN_UNTIL.put(id, now + pending.cooldownTicks());
        }
    }

    /** 断开/换世界时清干净，避免 UUID 复用后带着旧 pending。 */
    public static void clear(UUID playerId) {
        if (playerId == null) return;
        PENDING.remove(playerId);
        COOLDOWN_UNTIL.remove(playerId);
    }

    private static void prune(long now) {
        PENDING.entrySet().removeIf(e -> e.getValue().deadline() < now);
        COOLDOWN_UNTIL.entrySet().removeIf(e -> e.getValue() < now);
    }

    private static void feedback(ServerPlayer player, String key, String fallback, Object... args) {
        ScreenFeedbackPacket.send(player, I18n.name(key, fallback, args), true);
    }
}
