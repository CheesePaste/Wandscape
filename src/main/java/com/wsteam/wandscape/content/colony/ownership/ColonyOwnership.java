package com.wsteam.wandscape.content.colony.ownership;

import com.wsteam.wandscape.api.ColonyApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.colony.ActiveColonyTracker;
import com.wsteam.wandscape.content.colony.roster.ColonyRole;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 殖民地上下文与权限判定的唯一入口。
 *
 * <p>多殖民地后语义分两层，**不要混用**：
 * <ul>
 *   <li>{@link #activeColony} —— 「我**当前操作**的小镇」。可以是自己拥有的，也可以是被邀请参与别人的镇；
 *       由 {@link ActiveColonyTracker} 解析、可显式切换（档位 ≥ MEMBER）、跨重连持久化。
 *       一切「包里不带镇 id」的上下文解析都走它。没有可切换的镇 = 建镇引导态，
 *       **绝不回退空间最近小镇**（那是跨镇泄密的根因）。</li>
 *   <li>{@link #hasRole} / {@link #role} —— 「我在**这座**镇能干什么」。权限判定一律用它；
 *       {@link #isOwn} 只回答「这是不是我当前操作的那座镇」，**它不是权限判定**。</li>
 * </ul>
 */
public final class ColonyOwnership {

    private static final String TAG = "ColonyOwnership";

    private ColonyOwnership() {}

    /**
     * 玩家**当前操作的小镇**；没有任何可切换的镇时返回 null（= 建镇引导态）。
     *
     * <p>由 {@link ActiveColonyTracker} 解析：已存且仍可切 → 自己拥有的镇 → 档位最高者 → null。
     * 全程**不回退空间最近小镇**。解析失败降级为 null 并记警告，绝不抛给调用方
     * （调用方遍布 UI 与事件路径）。这是全仓唯一的「当前镇」入口，各域不得自备副本。
     */
    @Nullable
    public static UUID activeColony(ServerPlayer player) {
        return ActiveColonyTracker.activeColony(player);
    }

    /**
     * [已弃用的名字] 等价于 {@link #activeColony(ServerPlayer)}。
     *
     * <p>多殖民地之前它是「我创始的那座」；现在玩家的操作上下文是**可切换的当前镇**，
     * 被邀请参与别人的镇也有自己的当前镇，所以那个等式不再成立。名字暂时保留以免一次性
     * 打红约 50 处调用点，整合期会统一改成 {@code activeColony} 并删除本方法。
     * **新代码请直接用 {@link #activeColony}。**
     */
    @Nullable
    public static UUID ownColony(ServerPlayer player) {
        return activeColony(player);
    }

    /**
     * 玩家**当前操作的镇**是否就是 {@code colonyId}。
     *
     * <p><b>[它不是权限判定]</b> 「我在这镇能干什么」一律走 {@link #hasRole} / {@link #role}。
     * 多殖民地后本方法只是「这是不是我当前那座镇」的是非题。
     *
     * <ul>
     *   <li>{@code colonyId == null}：无归属目标（建镇流程 / 未关联建筑），视为允许——
     *       建镇引导依赖这条，别收紧。</li>
     *   <li>否则须与玩家的当前镇（{@link #activeColony}）相等。</li>
     * </ul>
     *
     * <p><b>[无 OP 旁路]</b> 旧注释曾声称「OP（权限 ≥ 2）旁路直接放行」，但**代码里从来没有这个分支**，
     * 属于文档撒谎。现按四档模型裁定：**权限一律按档位判，不给 OP 静默的全局旁路**——
     * 管理员要干预走 {@code /wandscape colony ...}（op-2 门控的命令面），
     * 而不是悄悄绕过所有镇的权限。静默继承一条旧注释不是授权。
     */
    public static boolean isOwn(@Nullable UUID colonyId, @Nullable ServerPlayer player) {
        if (player == null) return false;
        if (colonyId == null) return true;                 // 无归属：建镇流程/未关联建筑
        UUID own = activeColony(player);
        return own != null && own.equals(colonyId);
    }

    /**
     * 拒止并反馈（快捷栏 Action Bar + 屏幕 Toast + 村民拒绝音效 + 日志）。
     *
     * <p>操作描述走两次查表而不是字面量：{@code whatKey} 定 lang 键，{@code whatFallback}
     * 是键缺失时的中文兜底。整个 Component 会被序列化给客户端解析，所以服务端只需要
     * 拼出可翻译结构即可。
     *
     * @param whatKey      操作描述的 lang 键后缀（如 {@code building}/{@code mage}/{@code warehouse}），
     *                     完整键为 {@code gui.wandscape.ownership.what.<whatKey>}
     * @param whatFallback 该键缺失时的兜底描述
     */
    public static void deny(ServerPlayer player, String whatKey, String whatFallback) {
        Component what = I18n.name("gui.wandscape.ownership.what." + whatKey, whatFallback);
        Component msg = I18n.name("message.wandscape.ownership.denied",
                "§c[魔法小镇] 你没有权限操作别人的小镇（%s）", what);
        player.displayClientMessage(msg, true);
        ScreenFeedbackPacket.send(player, msg, true);
        try {
            player.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 1.0f, 1.0f);
        } catch (Throwable ignored) {}
        Log.warn(TAG, "Player {} denied {} on colony",
                player.getGameProfile().getName(), whatKey);
    }

    /** 目标小镇是否就是玩家自己的小镇（供无分支逻辑处使用）。 */
    public static boolean isOwnColonyOf(@Nullable UUID colonyId, @Nullable ServerPlayer player) {
        if (player == null) return false;
        if (colonyId == null) return false;
        UUID own = ownColony(player);
        return own != null && own.equals(colonyId);
    }

    /**
     * 玩家在指定小镇的档位（花名册查询）。
     *
     * <p>{@link #isOwn} 只回答「这是不是我的镇」，档位才回答「我在这镇能干什么」——
     * 多殖民地模型下，权限判定要走这里而不是归属等值比较。
     *
     * <p>不在花名册、参数缺失、API 未就绪或存储异常一律返回 null（= 非成员，无任何权限），
     * 绝不把异常抛给调用方。
     */
    @Nullable
    public static ColonyRole role(@Nullable ServerPlayer player, @Nullable UUID colonyId) {
        if (player == null || colonyId == null) return null;
        try {
            ColonyApi api = WandscapeApis.getColonyApiSilently();
            return api != null ? api.getRole(colonyId, player.getUUID()) : null;
        } catch (RuntimeException e) {
            Log.warn(TAG, "Failed to resolve role of {} in colony {}: {}",
                    player.getUUID(), colonyId, e.toString());
            return null;
        }
    }

    /** 玩家在指定小镇的档位是否不低于 {@code min}（非成员恒 false）。 */
    public static boolean hasRole(@Nullable ServerPlayer player, @Nullable UUID colonyId, ColonyRole min) {
        ColonyRole current = role(player, colonyId);
        return current != null && current.atLeast(min);
    }
}
