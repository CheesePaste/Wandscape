package com.wsteam.wandscape.content.tutorial.service;

import com.wsteam.wandscape.api.TutorialApi;
import com.wsteam.wandscape.content.building.data.BuildingData;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.content.tutorial.data.TutorialProgressSavedData;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;
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

    /**
     * 步骤总数 = {@link #computeStep} 的检查条数。步骤**内容**在
     * {@code TutorialRegistry.STEPS}，两处必须等长（那边启动时会校验并 warn）。这个数只用于
     * 一件事：判断「整段引导走完过没有」。
     */
    public static final int STEP_COUNT = 5;

    @Override
    public void sendToPlayer(ServerPlayer player, @Nullable UUID colonyId) {
        ServerLevel level = player.serverLevel();
        TutorialProgressSavedData sd = TutorialProgressSavedData.get(level);
        TutorialProgressSavedData.TutorialProgress saved = sd.get(player.getUUID());
        int step = saved.stepIndex();
        if (colonyId != null) {
            int current = computeStep(new ServerContext(level, colonyId));
            // 送出去的是**现值**（现在该做第几步），不是历史最高值。旧版逐条累加会把值写大，
            // 一旦沿用 Math.max 把它当下限，引导就永远停在一个已经做完的步骤上（工坊已建好，
            // 框里还在让你建工坊，再建多少座计数也不变）——玩家侧就是「建了完不成」。
            // 只有「整段走完过」才不回落：建筑被拆或切到另一座镇，不该把新手框重新叫回来。
            step = saved.stepIndex() >= STEP_COUNT ? saved.stepIndex() : current;
        }
        // 存档仍记历史最高值：它只在「无当前镇」时回放，并充当上面那个「走完过」的闩。
        sd.set(player.getUUID(), Math.max(saved.stepIndex(), step), saved.dismissed());
        Net.toPlayer(player, new TutorialProgressSyncPacket(step, saved.dismissed()));

        // 日志只在「引导真的动了」时写一行。本方法是每个仓库存取、每次开面板、每座建筑放置
        // 都会被调一次的，无条件写就会变成「每存一样东西刷一行」，而且引导走完后依然照刷
        // （推送点没有「已完成就不推」的开关），latest.log 只会一直长。
        if (step > saved.stepIndex()) {
            Log.info(TAG, "[Guide] {} 教程推进 {}→{} dismissed={}",
                    player.getGameProfile().getName(), saved.stepIndex(), step, saved.dismissed());
        } else if (step < saved.stepIndex()) {
            // 上屏值低于存档值：旧版逐条累加写大的值被拉回，或前置建筑已被拆。每玩家每目标值
            // 只写一次，否则这种玩家每次入仓都会重复同一条。
            Log.warnOnce(LogCategory.GENERAL, "tutorial.reconcile." + player.getUUID() + "." + step,
                    "[Guide] {} 上屏步骤 {} 低于存档 {}（旧值偏大或前置已拆），已按现值显示",
                    player.getGameProfile().getName(), step, saved.stepIndex());
        }
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
