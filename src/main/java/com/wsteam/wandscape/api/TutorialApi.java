package com.wsteam.wandscape.api;

import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Server-side tutorial progress evaluation: computes the current onboarding
 * step from colony state and pushes it to the player's client. The client only
 * renders; this service is authoritative.
 */
public interface TutorialApi {

    /**
     * Recompute the player's tutorial step for the colony and send progress.
     * When {@code colonyId} is null (no colony yet), only the saved progress is
     * sent so a pre-colony dismissal still persists.
     */
    void sendToPlayer(ServerPlayer player, @Nullable UUID colonyId);

    // ── 未实现（重设计阶段声明，见 @Unimplemented）──

    /** 强制把某玩家的教程推进到指定步。 */
    @Unimplemented("重设计阶段——待接入 TutorialProgressSavedData.set")
    default void setProgress(ServerPlayer player, int step) {
        throw new UnsupportedOperationException("TutorialApi.setProgress not yet implemented");
    }

    /** 清除某玩家的教程进度（回到第 0 步）。 */
    @Unimplemented("重设计阶段——待接入 TutorialProgressSavedData 清空")
    default void clearProgress(ServerPlayer player) {
        throw new UnsupportedOperationException("TutorialApi.clearProgress not yet implemented");
    }
}
