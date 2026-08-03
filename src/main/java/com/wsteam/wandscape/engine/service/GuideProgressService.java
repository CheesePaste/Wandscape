package com.wsteam.wandscape.engine.service;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.shared.api.GuideProgressApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.data.GuideProgressSavedData;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.network.GuideProgressSyncPacket;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.warehouse.ColonyItemBank;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-authoritative onboarding progress. Computes the current step from
 * colony state (buildings, shop purchases, tourist stays, colony level) and
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
     * Returns the number of leading steps satisfied (0..9).
     */
    public static int computeStep(GuideServerContext ctx) {
        int step = 0;
        if (ctx.hasCategory("government")) step++;
        if (ctx.hasType("warehouse")) step++;
        if (ctx.hasCategory("node")) step++;
        if (ctx.hasCategory("workstation")) step++;
        if (ctx.hasCategory("crafting_station")) step++;
        if (ctx.hasShopPurchased()) step++;
        if (ctx.hasInnWithStay()) step++;
        if (ctx.hasTavernRecruited()) step++;
        if (ctx.colonyLevel() >= 2) step++;
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
        public boolean hasType(String buildingTypeId) {
            for (BuildingData b : buildings) {
                if (buildingTypeId.equals(b.getBuildingTypeId())) return true;
            }
            return false;
        }

        @Override
        public boolean hasShopPurchased() {
            return hasCategory("shop")
                    && ColonyItemBank.get(level).getPurchaseCount(colonyId) > 0;
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

        @Override
        public boolean hasTavernRecruited() {
            if (!hasCategory("tavern")) return false;
            var npcApi = WandscapeApis.getNpcApiSilently();
            return npcApi != null && npcApi.getNpcCount(colonyId) > 0;
        }

        @Override
        public int colonyLevel() {
            return WandscapeEngine.getColonyLevelManager().getLevel(colonyId);
        }
    }
}
