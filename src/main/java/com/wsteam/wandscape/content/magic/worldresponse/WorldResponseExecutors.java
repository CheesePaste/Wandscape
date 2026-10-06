package com.wsteam.wandscape.content.magic.worldresponse;

import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.server.level.ServerPlayer;

/**
 * 四个世界回应的实现接缝。
 *
 * <p>当前**四个回应都还没有行为**：统一走「尚未实现」反馈并返回 {@code false}——
 * 于是 {@link WorldResponseManager} 不会进冷却，界面可以反复打开调试。
 * 逐个实现时只替换对应 case 的分支即可（签名固定为「玩家 → 是否真的产生了效果」），
 * 不要在这里另开一套状态机：pending/冷却/校验都在 {@link WorldResponseManager}。
 */
public final class WorldResponseExecutors {

    private static final String TAG = "WorldResponse";

    private WorldResponseExecutors() {}

    /**
     * 执行选中的回应。
     *
     * @return 是否真的产生了世界效果——{@code false} 表示没做事（未实现 / 找不到落点 / 前方无阻碍），
     *         调用方据此**不消耗冷却**，并通过反馈把原因说清楚（禁静默失败）。
     */
    public static boolean execute(ServerPlayer player, WorldResponse response) {
        return switch (response) {
            case FORWARD, LIFT, OPEN, JUDGE -> notImplemented(player, response);
        };
    }

    private static boolean notImplemented(ServerPlayer player, WorldResponse response) {
        String label = I18n.name(response.labelKey(), response.id()).getString();
        Log.info(TAG, "[WorldResponse] '{}' selected by {} — not implemented yet",
                response.id(), player.getGameProfile().getName());
        ScreenFeedbackPacket.send(player, I18n.name("message.wandscape.world_response.not_implemented",
                "「%s」尚未实现", label), false);
        return false;
    }
}
