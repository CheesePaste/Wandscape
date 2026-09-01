package com.wsteam.wandscape.api;

import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Server-side tutorial progress evaluation: computes the current onboarding
 * step from colony state and pushes it to the player's client. The client only
 * renders; this service is authoritative.
 */
public interface GuideProgressApi {

    /**
     * Recompute the player's tutorial step for the colony and send progress.
     * When {@code colonyId} is null (no colony yet), only the saved progress is
     * sent so a pre-colony dismissal still persists.
     */
    void sendToPlayer(ServerPlayer player, @Nullable UUID colonyId);
}
