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
            case LIFT -> activateLift(player);
            case TERRAFORM -> activateTerraform(player);
            case FORWARD, JUDGE -> notImplemented(player, response);
        };
    }

    /**
     * 扶摇：同样是**持续效果**（世界持续替你铺台阶往上托），关闭入口也是《平息》。
     * 重复选择不叠加、也不进冷却——没做事就不该罚冷却。
     */
    private static boolean activateLift(ServerPlayer player) {
        if (WorldResponseEffects.isActive(player, LiftEffect.ID)) {
            ScreenFeedbackPacket.send(player, I18n.name("message.wandscape.world_response.lift_active",
                    "世界已经在托着你上升 —— 用【平息】让它停下"), false);
            return false;
        }
        if (!WorldResponseEffects.activate(player, new LiftEffect(player.serverLevel()))) {
            return false;
        }
        Log.info(TAG, "[WorldResponse] Lift started for {}", player.getGameProfile().getName());
        ScreenFeedbackPacket.send(player, I18n.name("message.wandscape.world_response.lift_start",
                "世界开始为你铺阶"), true);
        return true;
    }

    /**
     * 移山填海：开的是**持续效果**（世界持续为你让路），关闭入口是配套魔法《平息》
     * （{@code world_response_calm} → {@link WorldResponseEffects#stopAll}）。
     * 重复选择不叠加、也不进冷却——没做事就不该罚冷却。
     */
    private static boolean activateTerraform(ServerPlayer player) {
        if (WorldResponseEffects.isActive(player, TerraformEffect.ID)) {
            ScreenFeedbackPacket.send(player, I18n.name("message.wandscape.world_response.terraform_active",
                    "世界已经在为你让路 —— 用【平息】让它停下"), false);
            return false;
        }
        if (!WorldResponseEffects.activate(player, new TerraformEffect(player.serverLevel()))) {
            return false;
        }
        Log.info(TAG, "[WorldResponse] Terraform started for {}", player.getGameProfile().getName());
        ScreenFeedbackPacket.send(player, I18n.name("message.wandscape.world_response.terraform_start",
                "世界开始为你让路"), true);
        return true;
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
