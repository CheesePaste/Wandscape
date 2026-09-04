package com.wsteam.wandscape.content.colony.ownership;

import com.wsteam.wandscape.api.ColonyApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 完全平行隔离的唯一归属判定入口。
 *
 * <p>铁律：一个玩家在服务器上的一切 Wandscape 上下文只能是他自己创建的小镇；
 * 没有小镇 = 「建镇引导态」，绝不显示/操作别人的小镇。本类收敛
 * 「目标是否属于操作者」这一判断与拒止反馈，取代逐包散落的手写校验。
 */
public final class ColonyOwnership {

    private static final String TAG = "ColonyOwnership";

    private ColonyOwnership() {}

    /**
     * 玩家自己的小镇（按 founder 绑定，无视距离），没有则返回 null。
     * 绝不回退到空间「最近小镇」——那是跨镇泄密的根因。
     */
    @Nullable
    public static UUID ownColony(ServerPlayer player) {
        ColonyApi api = WandscapeApis.getColonyApiSilently();
        return api != null ? api.getColonyByFounder(player.getUUID()) : null;
    }

    /**
     * 玩家是否拥有对 {@code colonyId} 的操作权。
     *
     * <ul>
     *   <li>{@code colonyId == null}：无归属目标（建镇/野建筑），视为允许。</li>
     *   <li>OP（权限 ≥ 2）旁路直接放行。</li>
     *   <li>否则须玩家自己的小镇与 {@code colonyId} 相等。</li>
     * </ul>
     */
    public static boolean isOwn(@Nullable UUID colonyId, @Nullable ServerPlayer player) {
        if (player == null) return false;
        if (colonyId == null) return true;                 // 无归属：建镇流程/未关联建筑
        if (player.hasPermissions(2)) return true;         // 管理员旁路
        UUID own = ownColony(player);
        return own != null && own.equals(colonyId);
    }

    /**
     * 拒止并反馈（快捷栏 Action Bar + 屏幕 Toast + 村民拒绝音效 + 日志）。
     *
     * @param what 简短操作描述（如「建筑」「法师」「仓库」），用于玩家反馈文案。
     */
    public static void deny(ServerPlayer player, String what) {
        Component msg = Component.literal("§c[小镇] 你没有权限操作别人的小镇（" + what + "）");
        player.displayClientMessage(msg, true);
        ScreenFeedbackPacket.send(player, msg, true);
        try {
            player.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.PLAYERS, 1.0f, 1.0f);
        } catch (Throwable ignored) {}
        Log.warn(TAG, "Player {} denied {} on colony",
                player.getGameProfile().getName(), what);
    }

    /** 目标小镇是否就是玩家自己的小镇（供无分支逻辑处使用）。 */
    public static boolean isOwnColonyOf(@Nullable UUID colonyId, @Nullable ServerPlayer player) {
        if (player == null) return false;
        if (colonyId == null) return false;
        if (player.hasPermissions(2)) return true;
        UUID own = ownColony(player);
        return own != null && own.equals(colonyId);
    }
}
