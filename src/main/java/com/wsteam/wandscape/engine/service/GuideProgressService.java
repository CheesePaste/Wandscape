package com.wsteam.wandscape.engine.service;

import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.content.building.internal.BuildingSavedData;
import com.wsteam.wandscape.shared.api.GuideProgressApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.GuideProgressSavedData;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.network.GuideProgressSyncPacket;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Server-authoritative onboarding progress. Computes the current step from
 * colony state (buildings, player actions, shop stock, tourist stays) and
 * pushes it to the client, which only renders.
 *
 * <p>{@link #computeStep} is pure and MC-free (over {@link GuideServerContext})
 * so the ordering logic is unit-testable.
 */
public final class GuideProgressService implements GuideProgressApi {

    private static final String TAG = "GuideProgressService";

    @Override
    public void sendToPlayer(ServerPlayer player, @Nullable UUID colonyId) {
        ServerLevel level = player.serverLevel();
        GuideProgressSavedData sd = GuideProgressSavedData.get(level);
        GuideProgressSavedData.GuideProgress saved = sd.get(player.getUUID());
        int step = saved.stepIndex();
        if (colonyId != null) {
            step = Math.max(step, computeStep(new ServerContext(level, colonyId)));
        }
        sd.set(player.getUUID(), step, saved.dismissed());
        PacketDistributor.sendToPlayer(player, new GuideProgressSyncPacket(step, saved.dismissed()));
        Log.info(TAG, "[Guide] {} step={} dismissed={}",
                player.getGameProfile().getName(), step, saved.dismissed());
    }

    /**
     * Step completion checks, in order — MUST match {@code GuideRegistry.STEPS}.
     * Returns the number of leading steps satisfied (0..10).
     */
    public static int computeStep(GuideServerContext ctx) {
        int step = 0;
        if (ctx.hasCategory("government")) step++;        // 1 建造市政厅
        if (ctx.hasCategory("storage")) step++;           // 2 建造仓库
        if (ctx.hasPlayerDeposited()) step++;             // 3 存入一个物品
        if (ctx.hasCategory("workstation")) step++;       // 4 建造工作站
        if (ctx.hasPlayerSynthesized()) step++;           // 5 合成一样物品
        if (ctx.hasPlayerPlacedRoad()) step++;            // 6 铺设一条道路
        if (ctx.hasBakeryStocked()) step++;             // 7 面包店补充货物
        if (ctx.hasNodeGatherPublished()) step++;         // 8 节点发布采集任务
        if (ctx.hasCategory("altar")) step++;             // 9 建造祭坛
        if (ctx.hasInnWithStay()) step++;                 // 10 青年旅舍游客入住
        return step;
    }

    private static final class ServerContext implements GuideServerContext {
        private final ServerLevel level;
        private final UUID colonyId;
        private final List<BuildingData> buildings;

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

        @Override
        public boolean hasPlayerPlacedRoad() {
            return ColonyItemBank.get(level).getPlayerRoadPlaceCount(colonyId) > 0;
        }

        @Override
        public boolean hasBakeryStocked() {
            BuildingSavedData savedData = BuildingSavedData.get(level);
            if (savedData == null) return false;
            for (BuildingData b : buildings) {
                if ("bakery".equals(b.getBuildingTypeId())
                        && savedData.hasShopStock(b.getBuildingId())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean hasNodeGatherPublished() {
            return hasCategory("node")
                    && ColonyItemBank.get(level).getGatherPublishedCount(colonyId) > 0;
        }

        @Override
        public boolean hasInnWithStay() {
            if (!hasServiceInn()) return false;
            var touristApi = WandscapeApis.getTouristApiSilently();
            return touristApi != null && touristApi.getOvernightStayerCount(colonyId) > 0;
        }

        private boolean hasServiceInn() {
            for (BuildingData b : buildings) {
                if (!"service".equals(b.getCategory())) continue;
                var config = BuildingConfigLoader.getInstance().get(b.getBuildingTypeId());
                if (config != null && config.service() != null
                        && config.service().maxOccupancy() > 0) {
                    return true;
                }
            }
            return false;
        }
    }
}
