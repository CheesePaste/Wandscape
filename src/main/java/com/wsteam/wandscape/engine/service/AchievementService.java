package com.wsteam.wandscape.engine.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.building.internal.ShopStockManager;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.colony.ColonyLevelManager;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.api.ColonyApi;
import com.wsteam.wandscape.shared.event.BuildingPlacedEvent;
import com.wsteam.wandscape.shared.event.ColonyLevelUpEvent;
import com.wsteam.wandscape.shared.event.ColonyRaidVictoryEvent;
import com.wsteam.wandscape.shared.event.ShopRestockedEvent;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.tourist.internal.HotelStayHandler;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Grants vanilla advancements when colony achievements are met.
 *
 * <p>Definitions live as data-driven JSON under
 * {@code data/wandscape/advancement/}; this service only detects conditions and
 * calls {@link PlayerAdvancements#award}. Granting is idempotent, so both the
 * event-driven fast path and the periodic re-scan safely re-grant to players
 * who join after a condition was already satisfied.
 */
public final class AchievementService {
    private static final String TAG = "AchievementService";

    // ---- Advancement ids (must match data/wandscape/advancement/<id>.json) ----
    private static final ResourceLocation START = loc("start");
    private static final ResourceLocation HAMLET = loc("hamlet");
    private static final ResourceLocation TOWN = loc("town");
    private static final ResourceLocation PROSPEROUS_TOWN = loc("prosperous_town");
    private static final ResourceLocation BIG_TOWN = loc("big_town");
    private static final ResourceLocation LEVEL_UP = loc("level_up");
    private static final ResourceLocation RENOWNED = loc("renowned");
    private static final ResourceLocation WELL_KNOWN = loc("well_known");
    private static final ResourceLocation FAMOUS = loc("famous");
    private static final ResourceLocation LEGENDARY = loc("legendary");
    private static final ResourceLocation SLAYER_OF_THE_END = loc("slayer_of_the_end");
    private static final ResourceLocation FULLY_STOCKED = loc("fully_stocked");
    private static final ResourceLocation FULL_HOUSE = loc("full_house");
    private static final ResourceLocation GRAND_WONDER = loc("grand_wonder");
    private static final ResourceLocation HERO_OF_WANDSCAPE = loc("hero_of_wandscape");

    /** Periodic full re-scan interval in ticks. Safety net for the hotel-full condition
     *  (no event) and for re-granting to players who log in later. */
    private static final int SCAN_INTERVAL = 100;
    private static int tickCounter;

    private AchievementService() {}

    public static void register() {
        var world = WandscapeEngine.getWorld();
        if (world == null || world.eventBus == null) {
            Log.warn(TAG, "Cannot register — engine not bootstrapped");
            return;
        }
        world.eventBus.subscribe(ColonyLevelUpEvent.class, AchievementService::onColonyLevelUp);

        NeoForge.EVENT_BUS.addListener(AchievementService::onBuildingPlaced);
        NeoForge.EVENT_BUS.addListener(AchievementService::onShopRestocked);
        NeoForge.EVENT_BUS.addListener(AchievementService::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(AchievementService::onRaidVictory);
        NeoForge.EVENT_BUS.addListener(AchievementService::onServerTick);
        Log.info(TAG, "registered on engine EventBus + NeoForge EVENT_BUS");
    }

    // ---- Event-driven fast path ----

    private static void onBuildingPlaced(BuildingPlacedEvent event) {
        UUID colonyId = event.getColonyId();
        if (colonyId == null) return;
        checkBuildingCount(colonyId);
        checkWonder(colonyId);
    }

    private static void onColonyLevelUp(ColonyLevelUpEvent event) {
        checkLevel(event.colonyId());
    }

    private static void onShopRestocked(ShopRestockedEvent event) {
        checkShopFull(event.getBuildingId());
    }

    private static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().getType() != EntityType.ENDERMAN) return;
        if (!(event.getSource().getEntity() instanceof WandscapeNpc)) return;
        grant(SLAYER_OF_THE_END);
    }

    private static void onRaidVictory(ColonyRaidVictoryEvent event) {
        grant(HERO_OF_WANDSCAPE);
    }

    // ---- Periodic safety net (hotel full + catch-up re-grant) ----

    private static void onServerTick(ServerTickEvent.Pre event) {
        if (++tickCounter < SCAN_INTERVAL) return;
        tickCounter = 0;
        ColonyApi api = WandscapeApis.getColonyApiSilently();
        if (api == null) return;
        try {
            for (UUID colonyId : api.getAllColonyIds()) {
                checkBuildingCount(colonyId);
                checkLevel(colonyId);
                checkWonder(colonyId);
                checkHotelFull(colonyId);
                checkAllShopsFull(colonyId);
            }
        } catch (Exception e) {
            Log.warn(TAG, "Full achievement scan failed: %s", e.getMessage());
        }
    }

    // ---- Condition checks (grant is idempotent, safe to call repeatedly) ----

    private static void checkBuildingCount(UUID colonyId) {
        BuildingApi api = buildingApi();
        if (api == null) return;
        int count;
        try {
            count = api.getColonyBuildings(colonyId).size();
        } catch (Exception e) {
            return;
        }
        if (count >= 1) grant(START);
        if (count >= 5) grant(HAMLET);
        if (count >= 10) grant(TOWN);
        if (count >= 20) grant(PROSPEROUS_TOWN);
        if (count >= 50) grant(BIG_TOWN);
    }

    private static void checkLevel(UUID colonyId) {
        ColonyLevelManager mgr = WandscapeEngine.getColonyLevelManager();
        if (mgr == null) return;
        int level = mgr.getLevel(colonyId);
        if (level >= 2) grant(LEVEL_UP);
        if (level >= 5) grant(RENOWNED);
        if (level >= 10) grant(WELL_KNOWN);
        if (level >= 20) grant(FAMOUS);
        if (level >= 30) grant(LEGENDARY);
    }

    /** Wonder-category building whose bounding box exceeds 50×50 blocks. */
    private static void checkWonder(UUID colonyId) {
        BuildingApi api = buildingApi();
        if (api == null) return;
        for (UUID wonderId : api.getBuildingsByCategory(colonyId, "wonder")) {
            BoundingBox bb = api.getBuildingBounds(wonderId);
            if (bb != null && bb.getXSpan() > 50 && bb.getZSpan() > 50) {
                grant(GRAND_WONDER);
                return;
            }
        }
    }

    /** Service building with maxOccupancy &gt; 0 that has reached full occupancy. */
    private static void checkHotelFull(UUID colonyId) {
        HotelStayHandler hotel = HotelStayHandler.getActive();
        if (hotel == null) return;
        BuildingApi api = buildingApi();
        if (api == null) return;
        for (UUID bId : api.getBuildingsByCategory(colonyId, "service")) {
            var building = api.getBuilding(bId);
            if (building == null) continue;
            BuildingConfig cfg;
            try {
                cfg = BuildingConfigLoader.getInstance().get(building.getBuildingTypeId());
            } catch (Exception e) {
                continue;
            }
            if (cfg == null || cfg.service() == null || cfg.service().maxOccupancy() <= 0) continue;
            if (hotel.getOccupancy(bId) >= cfg.service().maxOccupancy()) {
                grant(FULL_HOUSE);
                return;
            }
        }
    }

    private static void checkAllShopsFull(UUID colonyId) {
        BuildingApi api = buildingApi();
        if (api == null) return;
        for (UUID bId : api.getBuildingsByCategory(colonyId, "shop")) {
            checkShopFull(bId);
        }
    }

    /** Every good of the shop has been restocked up to its configured cap. */
    private static void checkShopFull(UUID buildingId) {
        ShopStockManager mgr = ShopStockManager.getActive();
        if (mgr == null) return;
        Map<String, Integer> maxs;
        try {
            maxs = mgr.getAllMaxStocks(buildingId);
        } catch (Exception e) {
            return;
        }
        if (maxs.isEmpty()) return;
        boolean hasGood = false;
        for (Map.Entry<String, Integer> e : maxs.entrySet()) {
            if (e.getValue() <= 0) continue;
            hasGood = true;
            if (mgr.getStockCount(buildingId, e.getKey()) < e.getValue()) return;
        }
        if (hasGood) grant(FULLY_STOCKED);
    }

    // ---- Granting ----

    private static void grant(ResourceLocation id) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        try {
            AdvancementHolder holder = server.getAdvancements().get(id);
            if (holder == null) return;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                PlayerAdvancements pa = player.getAdvancements();
                if (!pa.getOrStartProgress(holder).isDone()) {
                    for (String criterion : pa.getOrStartProgress(holder).getRemainingCriteria()) {
                        pa.award(holder, criterion);
                    }
                }
            }
        } catch (Exception e) {
            Log.warn(TAG, "Failed to grant advancement %s: %s", id, e.getMessage());
        }
    }

    @javax.annotation.Nullable
    private static BuildingApi buildingApi() {
        try {
            return WandscapeApis.getBuildingApi();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static ResourceLocation loc(String path) {
        return ResourceLocation.fromNamespaceAndPath("wandscape", path);
    }
}
