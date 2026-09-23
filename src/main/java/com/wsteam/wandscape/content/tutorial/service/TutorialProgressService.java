package com.wsteam.wandscape.content.tutorial.service;

import com.wsteam.wandscape.api.TutorialApi;
import com.wsteam.wandscape.content.building.data.BuildingData;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.content.tutorial.data.TutorialProgressSavedData;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.content.tutorial.network.TutorialProgressSyncPacket;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import com.wsteam.wandscape.foundation.networking.Net;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Server-authoritative onboarding progress. Computes the current step from
 * colony state (buildings the colony owns, what the player deposited and queued)
 * and pushes it to the client, which only renders.
 *
 * <p>{@link #computeStep} is pure and MC-free (over {@link TutorialServerContext})
 * so the ordering logic is unit-testable.
 */
public final class TutorialProgressService implements TutorialApi {

    private static final String TAG = "TutorialProgressService";

    @Override
    public void sendToPlayer(ServerPlayer player, @Nullable UUID colonyId) {
        ServerLevel level = player.serverLevel();
        TutorialProgressSavedData sd = TutorialProgressSavedData.get(level);
        TutorialProgressSavedData.TutorialProgress saved = sd.get(player.getUUID());
        int step = saved.stepIndex();
        if (colonyId != null) {
            step = Math.max(step, computeStep(new ServerContext(level, colonyId)));
        }
        sd.set(player.getUUID(), step, saved.dismissed());
        Net.toPlayer(player, new TutorialProgressSyncPacket(step, saved.dismissed()));
        Log.info(TAG, "[Guide] {} step={} dismissed={}",
                player.getGameProfile().getName(), step, saved.dismissed());
    }

    /**
     * Step completion checks, in order — MUST match {@code TutorialRegistry.STEPS}.
     * Returns the number of leading steps satisfied (0..5).
     */
    public static int computeStep(TutorialServerContext ctx) {
        int step = 0;
        if (ctx.hasCategory("government")) step++;        // 1 建造市政厅
        if (ctx.hasCategory("storage")) step++;           // 2 建造仓库
        if (ctx.hasPlayerDeposited()) step++;             // 3 存入一个物品
        if (ctx.hasCategory("workstation")) step++;       // 4 建造工作站
        if (ctx.hasPlayerSynthesized()) step++;           // 5 下发一个合成订单
        return step;
    }

    private static final class ServerContext implements TutorialServerContext {
        private final ServerLevel level;
        private final UUID colonyId;
        private final List<BuildingState> buildings;

        ServerContext(ServerLevel level, UUID colonyId) {
            this.level = level;
            this.colonyId = colonyId;
            var buildingApi = WandscapeApis.getBuildingApiSilently();
            this.buildings = buildingApi != null
                    ? buildingApi.getColonyBuildings(colonyId) : List.of();
        }

        @Override
        public boolean hasCategory(String category) {
            for (BuildingData b : buildings) {
                if (category.equals(b.getCategory())) return true;
            }
            return false;
        }

        @Override
        public boolean hasPlayerDeposited() {
            return ColonyItemBank.get(level).getPlayerDepositCount(colonyId) > 0;
        }

        @Override
        public boolean hasPlayerSynthesized() {
            return ColonyItemBank.get(level).getPlayerSynthesizeCount(colonyId) > 0;
        }
    }
}
