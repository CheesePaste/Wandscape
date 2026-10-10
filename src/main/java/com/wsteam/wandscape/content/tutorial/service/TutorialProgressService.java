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
     * 返回**第一个还没满足**的步骤下标（= 连成串的步骤数，0..5）；顺序必须与
     * {@code TutorialRegistry.STEPS} 一致。客户端把它直接当作要显示的步骤下标
     * （{@code STEPS.get(step)}），所以这个返回值不是「进度分数」，而是「现在该做第几步」。
     *
     * <p><b>必须遇缺即停</b>：各步条件都是状态式（有没有某类建筑 / 存过东西 / 下过合成单），
     * 玩家完全可以乱序达成（例如还没存东西就先放下物品工坊）。若像早先那样逐条累加
     * （{@code if (satisfied) step++}），乱序时下标会滑到一个**已经做完**的步骤上，引导框就去
     * 指挥玩家做一件不会改变判定的事——「建造物品工坊」而工坊已存在，再多建几座也不会推进，
     * 这就是实测到的「建了完不成」；反过来也会滑到前置未满足的步骤（没有仓库却让玩家存东西）。
     * 遇缺即停则永远显示真正缺的那一步；乱序早做完的步骤会在轮到它时被一次吸收（直接跳过，
     * 不回退也不卡死）。
     */
    public static int computeStep(TutorialServerContext ctx) {
        if (!ctx.hasCategory("government")) return 0;     // 1 建造市政厅
        if (!ctx.hasCategory("storage")) return 1;        // 2 建造仓库
        if (!ctx.hasPlayerDeposited()) return 2;          // 3 存入一个物品
        if (!ctx.hasCategory("workstation")) return 3;    // 4 建造物品工坊
        if (!ctx.hasPlayerSynthesized()) return 4;        // 5 下发一个合成订单
        return 5;
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
