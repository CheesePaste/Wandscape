package com.wsteam.wandscape.tourist.internal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.building.internal.ShopStockManager;
import com.wsteam.wandscape.core.road.PathPoint;
import com.wsteam.wandscape.core.road.RoadNetwork;
import com.wsteam.wandscape.core.road.RoadRouter;
import com.wsteam.wandscape.core.road.RouteSegment;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.api.RoadApi;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.tourist.entity.TouristEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Tourist-specific movement AI. Navigates tourists along roads to a target
 * building's interaction position, triggers the interaction on arrival,
 * then picks the next building or leaves.
 *
 * <p>Supports multi-stop itineraries: shop → service → hotel (if night) → depart.
 * While checked into a hotel, the tourist stays in place and recovers energy.
 */
public class TouristMoveGoal extends Goal {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final TouristEntity tourist;
    private final double speed;

    private BlockPos[] waypoints;
    private int wpIndex;
    private boolean usingRoad;
    private int stuckTicks;
    private int idleTicks;
    /** Ticks spent idle after finishing all destinations, before cleanup removes the tourist. */
    private static final int POST_TOUR_IDLE_TICKS = 200;

    public TouristMoveGoal(TouristEntity tourist, double speed) {
        this.tourist = tourist;
        this.speed = speed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return tourist.isTouristMode() && tourist.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return tourist.isTouristMode() && tourist.isAlive();
    }

    @Override
    public void start() {
        // If checked into a hotel, stay put — heartbeat handles energy recovery
        if (tourist.getCheckedInBuildingId() != null) {
            return;
        }
        // If no commute target, try to plan one
        if (tourist.getCommuteTarget() == null) {
            planNextBuilding();
        }
        if (tourist.getCommuteTarget() == null) {
            idleTicks = 0;
            return;
        }
        beginNavigation(tourist.getCommuteTarget());
    }

    @Override
    public void stop() {
        tourist.getNavigation().stop();
        waypoints = null;
        wpIndex = 0;
    }

    @Override
    public void tick() {
        // While checked into hotel, stay still — HotelStayHandler heartbeat manages energy
        if (tourist.getCheckedInBuildingId() != null) {
            tourist.getNavigation().stop();
            return;
        }

        BlockPos target = tourist.getCommuteTarget();
        if (target == null) {
            idleTicks++;
            // After idling a while post-tour, try planning next destination
            if (idleTicks > POST_TOUR_IDLE_TICKS) {
                planNextBuilding();
                if (tourist.getCommuteTarget() != null) {
                    idleTicks = 0;
                    beginNavigation(tourist.getCommuteTarget());
                }
            }
            return;
        }

        var nav = tourist.getNavigation();
        BlockPos pos = tourist.blockPosition();

        // Check arrival: within interaction range of the target
        double distSqr = pos.distSqr(target);
        int interactionRange = getInteractionRange();
        if (distSqr < interactionRange * interactionRange) {
            onArrived();
            return;
        }

        // Waypoint advancement
        BlockPos wp = currentTarget(target);
        if (wp != null && pos.distSqr(wp) < 2.25) {
            wpIndex++;
            if (waypoints != null && wpIndex < waypoints.length) {
                moveToNext(speed, target);
                stuckTicks = 0;
                return;
            }
        }

        // Stuck recovery
        if (nav.isDone()) {
            if (++stuckTicks > 40) {
                stuckTicks = 0;
                usingRoad = planRoute(target);
                wpIndex = 1;
            }
            moveToNext(speed, target);
        } else {
            stuckTicks = Math.max(0, stuckTicks - 1);
        }
    }

    // ── Arrival handling ──

    private void onArrived() {
        tourist.getNavigation().stop();
        waypoints = null;
        wpIndex = 0;
        idleTicks = 0;

        UUID buildingId = tourist.getTargetBuildingId();
        if (buildingId == null) {
            finishStop();
            return;
        }

        String category = tourist.getTargetBuildingCategory();
        boolean isHotel = isHotelBuilding(buildingId);

        if (isHotel) {
            // Check into hotel
            HotelStayHandler hotel = HotelStayHandler.getActive();
            UUID colonyId = tourist.getColonyId();
            if (hotel != null && colonyId != null && hotel.checkIn(tourist, buildingId, colonyId)) {
                tourist.addVisitedBuilding(buildingId);
                applyPreferenceDecay(buildingId);
                tourist.setCommuteTarget(null);
                tourist.setTargetBuildingId(null);
                tourist.setTargetBuildingCategory(null);
                LOGGER.debug("[TouristMove] {} checked into hotel {}", tourist.getTouristName(), shortId(buildingId));
                return;
            }
            // Hotel full or check-in failed — treat as regular service
        }

        // Interact based on category
        if ("shop".equals(category)) {
            interactWithShop(buildingId);
        } else if ("service".equals(category)) {
            interactWithService(buildingId);
        }

        tourist.addVisitedBuilding(buildingId);

        // Plan next stop
        finishStop();
    }

    /** Mark current stop complete and plan the next one. */
    private void finishStop() {
        tourist.setCommuteTarget(null);
        tourist.setTargetBuildingId(null);
        tourist.setTargetBuildingCategory(null);
        idleTicks = 0;
        planNextBuilding();
        if (tourist.getCommuteTarget() != null) {
            beginNavigation(tourist.getCommuteTarget());
        }
    }

    // ── Next-destination planning ──

    /**
     * Picks the tourist's next destination from available buildings in the colony.
     * Sets commuteTarget, targetBuildingId, and targetBuildingCategory on the tourist.
     * If no buildings are available, leaves them null (tourist will eventually despawn).
     */
    private void planNextBuilding() {
        UUID colonyId = tourist.getColonyId();
        if (colonyId == null) return;

        BuildingApi api = getBuildingApi();
        if (api == null) return;

        List<BuildingData> allBuildings = api.getColonyBuildings(colonyId);
        if (allBuildings.isEmpty()) return;

        ServerLevel level = getServerLevel();
        if (level == null) return;

        long dayTime = level.getDayTime() % 24000;
        boolean isNight = dayTime >= 13000;

        // Collect candidates: shop + service buildings that are intact, not shutdown, and not visited
        List<BuildingState> shopTargets = new ArrayList<>();
        List<BuildingState> serviceTargets = new ArrayList<>();
        List<BuildingState> hotelTargets = new ArrayList<>();

        BuildingSavedData savedData = BuildingSavedData.get(level);
        if (savedData == null) return;

        boolean inServiceCooldown = tourist.getServiceCooldownEndTick() > tourist.tickCount;

        for (BuildingData b : allBuildings) {
            String cat = b.getCategory();
            if (!"shop".equals(cat) && !"service".equals(cat)) continue;
            if (b.isShutdown() || !b.isStructureIntact()) continue;
            if (tourist.hasVisitedBuilding(b.getBuildingId())) continue;

            // Skip service buildings on per-building cooldown
            if ("service".equals(cat) && tourist.getServiceCooldown(b.getBuildingId()) > tourist.tickCount)
                continue;

            BuildingState state = savedData.getBuilding(b.getBuildingId());
            if (state == null) continue;

            if ("shop".equals(cat)) {
                // Only target shops that have stock
                ShopStockManager stock = ShopStockManager.getActive();
                if (stock != null && stock.hasStock(b.getBuildingId())) {
                    shopTargets.add(state);
                }
            } else {
                // Skip all service buildings during global cooldown
                if (inServiceCooldown) continue;

                if (isHotelBuilding(b.getBuildingId())) {
                    HotelStayHandler hotel = HotelStayHandler.getActive();
                    if (hotel != null && hotel.hasVacancy(b.getBuildingId())) {
                        hotelTargets.add(state);
                    }
                } else {
                    serviceTargets.add(state);
                }
            }
        }

        // Pick target with priority: hotel (if night + sat 70-99) > shop > service
        // Tourists with sat < 70 or >= 100 should not seek hotels
        int sat = tourist.getSatisfaction();
        boolean canUseHotel = sat >= 70 && sat < 100;

        BuildingState chosen = null;
        if (isNight && canUseHotel && !hotelTargets.isEmpty()) {
            chosen = weightedPick(hotelTargets);
        } else if (!shopTargets.isEmpty()) {
            chosen = weightedPick(shopTargets);
        } else if (!serviceTargets.isEmpty()) {
            chosen = weightedPick(serviceTargets);
        } else if (canUseHotel && !hotelTargets.isEmpty()) {
            chosen = weightedPick(hotelTargets);
        }

        if (chosen == null) {
            LOGGER.debug("[TouristMove] {} has no more buildings to visit", tourist.getTouristName());
            return;
        }

        BlockPos interactionTarget = api.getInteractionTarget(chosen.getBuildingId());
        if (interactionTarget == null) interactionTarget = chosen.getAnchor();

        tourist.setTargetBuildingId(chosen.getBuildingId());
        tourist.setTargetBuildingCategory(chosen.getCategory());
        tourist.setCommuteTarget(interactionTarget);
        LOGGER.debug("[TouristMove] {} next stop: {} '{}' at {}",
                tourist.getTouristName(), chosen.getCategory(),
                chosen.getBuildingTypeId(), interactionTarget.toShortString());
    }

    // ── Interactions ──

    private void interactWithShop(UUID buildingId) {
        ShopStockManager stockManager = ShopStockManager.getActive();
        if (stockManager == null) return;

        UUID colonyId = tourist.getColonyId();
        if (colonyId == null) return;

        String purchased = com.wsteam.wandscape.building.internal.ShopInteractionHandler.interact(
                stockManager, tourist.getUUID(), buildingId, colonyId);
        if (purchased != null) {
            int gain = computeSatisfactionGain(buildingId);
            tourist.setSatisfaction(tourist.getSatisfaction() + gain);
            tourist.setEnergy(tourist.getEnergy() - 20);
            applyPreferenceDecay(buildingId);
            LOGGER.debug("[TouristMove] {} purchased {} from shop {} — satisfaction +{}",
                    tourist.getTouristName(), purchased, shortId(buildingId), gain);
        }
    }

    private void interactWithService(UUID buildingId) {
        var config = BuildingConfigLoader.getInstance().get(getBuildingTypeId(buildingId));
        if (config == null || config.service() == null) return;

        var svc = config.service();
        tourist.setEnergy(tourist.getEnergy() - svc.energyPerUse());
        int gain = computeSatisfactionGain(buildingId);
        tourist.setSatisfaction(tourist.getSatisfaction() + gain);
        applyPreferenceDecay(buildingId);

        // Apply service cooldown — tourist wanders streets before next facility visit
        int cooldownTicks = Config.SERVICE_COOLDOWN_TICKS.get();
        if (cooldownTicks > 0) {
            int endTick = tourist.tickCount + cooldownTicks;
            tourist.setServiceCooldown(buildingId, endTick);
            tourist.setServiceCooldownEndTick(endTick);
        }

        // Deposit elementOutput to colony bank
        UUID colonyId = tourist.getColonyId();
        if (colonyId != null && !svc.elementOutput().isEmpty()) {
            ServerLevel level = getServerLevel();
            if (level != null) {
                var bank = com.wsteam.wandscape.warehouse.ColonyItemBank.get(level);
                if (bank != null) {
                    for (var entry : svc.elementOutput().entrySet()) {
                        try {
                            var elementType = com.wsteam.wandscape.shared.data.ElementType.fromId(entry.getKey());
                            bank.addElement(colonyId, elementType, entry.getValue());
                        } catch (IllegalArgumentException e) {
                            LOGGER.warn("[TouristMove] Unknown element type '{}' in service {} elementOutput",
                                    entry.getKey(), shortId(buildingId));
                        }
                    }
                }
            }
        }

        LOGGER.debug("[TouristMove] {} used service {} — energy={} satisfaction={} cooldown={}",
                tourist.getTouristName(), shortId(buildingId),
                tourist.getEnergy(), tourist.getSatisfaction(), cooldownTicks);
    }

    // ── Preference-based satisfaction ──

    /**
     * Returns the effective three-values for a building, including in-stock goods
     * bonus for shops.
     */
    private int[] getEffectiveValues(@Nullable UUID buildingId) {
        var config = BuildingConfigLoader.getInstance().get(getBuildingTypeId(buildingId));
        if (config == null) return new int[]{0, 0, 0};
        int c = config.comfort();
        int m = config.magic();
        int w = config.wonder();
        if ("shop".equals(config.category())) {
            ShopStockManager stockMgr = ShopStockManager.getActive();
            if (stockMgr != null) {
                c += stockMgr.getGoodsBonusComfort(buildingId);
                m += stockMgr.getGoodsBonusMagic(buildingId);
                w += stockMgr.getGoodsBonusWonder(buildingId);
            }
        }
        return new int[]{c, m, w};
    }

    /** Three-value sum for a building (including goods bonus). */
    private int threeValueSum(@Nullable UUID buildingId) {
        int[] v = getEffectiveValues(buildingId);
        return v[0] + v[1] + v[2];
    }

    /**
     * Match score = tourist's type preference × building's three-value sum.
     * Drives both building selection (weightedPick) and satisfaction gain.
     */
    private int computeMatchScore(@Nullable UUID buildingId) {
        String typeId = getBuildingTypeId(buildingId);
        if (typeId == null) return 0;
        int typePref = tourist.getTypePreference(typeId);
        int sum = threeValueSum(buildingId);
        return typePref * sum;
    }

    /**
     * Compute satisfaction gain with level-scaled cutoff and diminishing returns.
     *
     * <p>If the building's three-value sum is below {@code level × thresholdPerLevel},
     * the building is too basic for this tourist — zero gain.
     * Above the threshold, gain = min(sqrt(typePref × (threeSum - threshold + 1)), maxPerVisit).
     */
    private int computeSatisfactionGain(@Nullable UUID buildingId) {
        int threeSum = threeValueSum(buildingId);
        int threshold = tourist.getLevel() * Config.TOURIST_LEVEL_SATISFACTION_THRESHOLD.get();
        if (threeSum < threshold) return 0;

        String typeId = getBuildingTypeId(buildingId);
        int typePref = typeId != null ? tourist.getTypePreference(typeId) : 50;
        int baseScore = typePref * (threeSum - threshold + 1);
        int gain = (int) Math.sqrt(baseScore);
        return Math.min(gain, Config.TOURIST_MAX_SATISFACTION_PER_VISIT.get());
    }

    /**
     * Reduce the tourist's preference for the specific building type just visited.
     * This prevents tourists from farming a single building type.
     */
    private void applyPreferenceDecay(UUID buildingId) {
        int decay = Config.TOURIST_PREFERENCE_DECAY.get();
        if (decay <= 0) return;
        String typeId = getBuildingTypeId(buildingId);
        if (typeId == null) return;
        tourist.adjustTypePreference(typeId, -decay);
        LOGGER.debug("[TouristMove] {} decay preference for {} → {}",
                tourist.getTouristName(), typeId, tourist.getTypePreference(typeId));
    }

    // ── Weighted random building selection ──

    /** Pick a building weighted by preference-match score. Minimum weight is 1. */
    @Nullable
    private BuildingState weightedPick(List<BuildingState> candidates) {
        if (candidates.isEmpty()) return null;

        int[] weights = new int[candidates.size()];
        int totalWeight = 0;
        for (int i = 0; i < candidates.size(); i++) {
            int score = computeMatchScore(candidates.get(i).getBuildingId());
            weights[i] = Math.max(1, score);
            totalWeight += weights[i];
        }

        int roll = tourist.getRandom().nextInt(totalWeight);
        int cumulative = 0;
        for (int i = 0; i < candidates.size(); i++) {
            cumulative += weights[i];
            if (roll < cumulative) return candidates.get(i);
        }
        return candidates.get(candidates.size() - 1);
    }

    // ── Road routing ──

    private void beginNavigation(BlockPos target) {
        waypoints = null;
        wpIndex = 0;
        stuckTicks = 0;
        idleTicks = 0;
        usingRoad = planRoute(target);
        wpIndex = 1;
        moveToNext(speed, target);
        LOGGER.debug("[TouristMove] {} heading to {} via {}",
                tourist.getTouristName(), target.toShortString(),
                usingRoad ? "road" : "direct");
    }

    private boolean planRoute(BlockPos target) {
        RoadApi roadApi = getRoadApiSilently();
        if (roadApi == null) return false;
        RoadNetwork network = roadApi.getNetwork(null);
        if (network == null || network.isEmpty()) return false;

        BlockPos from = tourist.blockPosition();
        List<RouteSegment> segments = RoadRouter.plan(
                network,
                new PathPoint(from.getX(), from.getY(), from.getZ()),
                new PathPoint(target.getX(), target.getY(), target.getZ()));
        if (segments.isEmpty()) return false;

        List<BlockPos> wps = new ArrayList<>();
        RouteSegment first = segments.get(0);
        wps.add(new BlockPos((int) first.fromX(), (int) first.fromY(), (int) first.fromZ()));
        for (RouteSegment seg : segments) {
            wps.add(new BlockPos((int) seg.toX(), (int) seg.toY(), (int) seg.toZ()));
        }
        waypoints = wps.toArray(new BlockPos[0]);
        return true;
    }

    private void moveToNext(double spd, BlockPos fallback) {
        BlockPos wp = currentTarget(fallback);
        BlockPos ground = findGround(wp.getX(), wp.getY(), wp.getZ());
        if (ground != null) wp = ground;
        tourist.getNavigation().moveTo(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5, spd);
    }

    @Nullable
    private BlockPos currentTarget(BlockPos fallback) {
        if (waypoints != null && wpIndex < waypoints.length) return waypoints[wpIndex];
        return fallback;
    }

    private int getInteractionRange() {
        UUID buildingId = tourist.getTargetBuildingId();
        if (buildingId == null) return 3;
        BuildingApi api = getBuildingApi();
        if (api == null) return 3;
        var data = api.getBuilding(buildingId);
        if (data == null) return 3;
        var config = BuildingConfigLoader.getInstance().get(data.getBuildingTypeId());
        if (config == null) return 3;
        int r = config.interactionRadius();
        return r > 0 ? r : 3;
    }

    // ── Hotel detection ──

    private boolean isHotelBuilding(UUID buildingId) {
        var config = BuildingConfigLoader.getInstance().get(getBuildingTypeId(buildingId));
        return config != null && config.service() != null && config.service().maxOccupancy() > 0;
    }

    @Nullable
    private String getBuildingTypeId(UUID buildingId) {
        BuildingApi api = getBuildingApi();
        if (api == null) return null;
        var data = api.getBuilding(buildingId);
        return data != null ? data.getBuildingTypeId() : null;
    }

    // ── Helpers ──

    @Nullable
    private BlockPos findGround(int x, int baseY, int z) {
        var lvl = tourist.level();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos(
                x, Math.min(lvl.getMaxBuildHeight() - 1, baseY + 5), z);
        while (mp.getY() > lvl.getMinBuildHeight()) {
            if (!lvl.getBlockState(mp).isAir()
                    && lvl.getBlockState(mp.above()).isAir())
                return mp.above().immutable();
            mp.move(0, -1, 0);
        }
        return null;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    @Nullable
    private static BuildingApi getBuildingApi() {
        try { return WandscapeApis.getBuildingApi(); }
        catch (IllegalStateException e) { return null; }
    }

    @Nullable
    private static RoadApi getRoadApiSilently() {
        try { return WandscapeApis.getRoadApi(); }
        catch (IllegalStateException e) { return null; }
    }

    @Nullable
    private static ServerLevel getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }
}
