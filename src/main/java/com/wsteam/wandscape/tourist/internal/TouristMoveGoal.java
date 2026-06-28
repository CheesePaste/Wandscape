package com.wsteam.wandscape.tourist.internal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Unified movement AI for {@link TouristEntity}.
 *
 * <p>Self-manages an internal {@link MoveMode} state machine independent of
 * {@link TouristState}:
 *
 * <ul>
 *   <li>{@code VISITING_BUILDING} — navigate to a shop/service/hotel,
 *       interact on arrival, then probabilistically pick next mode</li>
 *   <li>{@code EXPLORING_POI} — navigate to a POI, pause, then pick next mode</li>
 *   <li>{@code WANDERING} — random walk within an anchor radius, periodically
 *       re-evaluate mode</li>
 * </ul>
 *
 * <p>While checked into a hotel, all movement stops until checkout.
 */
public class TouristMoveGoal extends Goal {
    private static final String TAG = "TouristMoveGoal";

    // ── Internal movement mode ──

    enum MoveMode {
        /** Heading to a building (shop/service/hotel) for interaction. */
        VISITING_BUILDING,
        /** Heading to a POI for sightseeing. */
        EXPLORING_POI,
        /** Random walk near an anchor point. */
        WANDERING
    }

    private final TouristEntity tourist;
    private final double touristSpeed;
    private final double wanderSpeed;

    private MoveMode currentMode = MoveMode.WANDERING;

    // ── Shared navigation ──
    private BlockPos[] waypoints;
    private int wpIndex;
    private boolean usingRoad;
    private int stuckTicks;

    // ── Building-visit state ──
    private int idleTicks;
    private static final int POST_TOUR_IDLE_TICKS = 200;

    // ── POI state ──
    private int poiPauseTicks;

    // ── Wander state ──
    private int wanderCooldown;
    private int wanderEvaluateTick;

    public TouristMoveGoal(TouristEntity tourist, double touristSpeed, double wanderSpeed) {
        this.tourist = tourist;
        this.touristSpeed = touristSpeed;
        this.wanderSpeed = wanderSpeed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    // ── Activation ──

    @Override
    public boolean canUse() {
        return tourist.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return tourist.isAlive();
    }

    // ── Entry / exit ──

    @Override
    public void start() {
        // Only bypass probability when spawner explicitly assigned a building target
        if (tourist.getCommuteTarget() != null && tourist.getTargetBuildingId() != null) {
            currentMode = MoveMode.VISITING_BUILDING;
        } else {
            currentMode = decideNextMode(null);
        }
        dispatchStart();
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

        switch (currentMode) {
            case VISITING_BUILDING -> tickBuildingVisit();
            case EXPLORING_POI -> tickPoiExplore();
            case WANDERING -> tickWander();
        }
    }

    // ── Mode dispatch (start) ──

    private void dispatchStart() {
        switch (currentMode) {
            case VISITING_BUILDING -> startBuildingVisit();
            case EXPLORING_POI -> startPoiExplore();
            case WANDERING -> startWander();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // VISITING_BUILDING
    // ════════════════════════════════════════════════════════════════

    private void startBuildingVisit() {
        // If no commute target, try to plan one
        if (tourist.getCommuteTarget() == null) {
            planNextBuilding();
        }
        if (tourist.getCommuteTarget() == null) {
            // No buildings available → fall back to wandering
            idleTicks = 0;
            switchMode(MoveMode.WANDERING);
            startWander();
            return;
        }
        beginNavigation(tourist.getCommuteTarget(), touristSpeed);
    }

    private void tickBuildingVisit() {
        BlockPos target = tourist.getCommuteTarget();
        if (target == null) {
            idleTicks++;
            if (idleTicks > POST_TOUR_IDLE_TICKS) {
                planNextBuilding();
                if (tourist.getCommuteTarget() != null) {
                    idleTicks = 0;
                    beginNavigation(tourist.getCommuteTarget(), touristSpeed);
                } else {
                    // Still nothing → wander
                    switchMode(MoveMode.WANDERING);
                    startWander();
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
            onBuildingArrived();
            return;
        }

        // Waypoint advancement
        BlockPos wp = currentTarget(target);
        if (wp != null && pos.distSqr(wp) < 2.25) {
            wpIndex++;
            if (waypoints != null && wpIndex < waypoints.length) {
                moveToNext(touristSpeed, target);
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
            moveToNext(touristSpeed, target);
        } else {
            stuckTicks = Math.max(0, stuckTicks - 1);
        }
    }

    private void onBuildingArrived() {
        tourist.getNavigation().stop();
        waypoints = null;
        wpIndex = 0;
        idleTicks = 0;

        UUID buildingId = tourist.getTargetBuildingId();
        if (buildingId == null) {
            finishBuildingStop();
            return;
        }

        String category = tourist.getTargetBuildingCategory();
        String bldType = getBuildingTypeId(buildingId);
        boolean isHotel = isHotelBuilding(buildingId);

        if (isHotel) {
            HotelStayHandler hotel = HotelStayHandler.getActive();
            UUID colonyId = tourist.getColonyId();
            if (hotel != null && colonyId != null && hotel.checkIn(tourist, buildingId, colonyId)) {
                tourist.addVisitedBuilding(buildingId);
                applyPreferenceDecay(buildingId);
                tourist.setCommuteTarget(null);
                tourist.setTargetBuildingId(null);
                tourist.setTargetBuildingCategory(null);
                showActionBar("✨ " + tourist.getTouristName() + " 入住了旅馆 " + (bldType != null ? bldType : "?") + "!");
                return;
            }
        }

        if ("shop".equals(category)) {
            interactWithShop(buildingId);
        } else if ("service".equals(category)) {
            interactWithService(buildingId);
        }

        tourist.addVisitedBuilding(buildingId);
        finishBuildingStop();
    }

    private void finishBuildingStop() {
        tourist.setCommuteTarget(null);
        tourist.setTargetBuildingId(null);
        tourist.setTargetBuildingCategory(null);
        idleTicks = 0;

        // Probability-based next mode
        switchMode(decideNextMode(MoveMode.VISITING_BUILDING));
        dispatchStart();
    }

    // ════════════════════════════════════════════════════════════════
    // EXPLORING_POI
    // ════════════════════════════════════════════════════════════════

    private void startPoiExplore() {
        waypoints = null;
        wpIndex = 0;
        stuckTicks = 0;
        poiPauseTicks = 0;
        pickNextPoiAndGo();
    }

    private void tickPoiExplore() {
        var nav = tourist.getNavigation();
        BlockPos pos = tourist.blockPosition();

        if (poiPauseTicks > 0) {
            poiPauseTicks--;
            nav.stop();
            if (poiPauseTicks <= 0) {
                // POI pause ended — re-evaluate mode (may switch to building or wander)
                MoveMode next = decideNextMode(MoveMode.EXPLORING_POI);
                if (next != currentMode) {
                    switchMode(next);
                    dispatchStart();
                    return;
                }
                // Stay in EXPLORING_POI — fall through to pick next POI below
            } else {
                return;
            }
        }

        BlockPos wp = currentTarget();
        if (wp == null) {
            // No waypoints — pick another POI, or wander fallback
            if (!pickNextPoiAndGo()) {
                switchMode(MoveMode.WANDERING);
                startWander();
            }
            return;
        }

        // Waypoint advancement
        if (pos.distSqr(wp) < 2.25) {
            if (waypoints != null && wpIndex < waypoints.length) {
                wpIndex++;
                if (wpIndex < waypoints.length) {
                    moveToNext(wanderSpeed, wp);
                    stuckTicks = 0;
                    return;
                }
            }
            // End of waypoint chain → arrived at POI
            nav.stop();
            waypoints = null;
            wpIndex = 0;
            poiPauseTicks = 100 + tourist.getRandom().nextInt(200);
            return;
        }

        // Stuck recovery
        if (nav.isDone()) {
            if (++stuckTicks > 60) {
                stuckTicks = 0;
                if (!pickNextPoiAndGo()) {
                    switchMode(MoveMode.WANDERING);
                    startWander();
                }
                return;
            }
            moveToNext(wanderSpeed, wp);
        } else {
            stuckTicks = Math.max(0, stuckTicks - 1);
        }
    }

    /**
     * Pick a random far POI (or wander-anchor offset) and start navigating.
     * @return true if a target was set
     */
    private boolean pickNextPoiAndGo() {
        List<BlockPos> pois = tourist.getPoiList();
        BlockPos rawTarget = null;

        if (!pois.isEmpty()) {
            BlockPos here = tourist.blockPosition();
            List<BlockPos> far = new ArrayList<>();
            for (BlockPos p : pois) {
                if (p.distSqr(here) > 25) far.add(p);
            }
            rawTarget = !far.isEmpty()
                    ? far.get(tourist.getRandom().nextInt(far.size()))
                    : pois.get(tourist.getRandom().nextInt(pois.size()));
        }
        if (rawTarget == null) {
            BlockPos anchor = tourist.getWanderAnchor();
            if (anchor != null) {
                int r = tourist.getWanderRadius();
                rawTarget = anchor.offset(
                        tourist.getRandom().nextInt(r * 2 + 1) - r, 0,
                        tourist.getRandom().nextInt(r * 2 + 1) - r);
            }
        }
        if (rawTarget == null) return false;

        BlockPos target = findGround(rawTarget.getX(), rawTarget.getY(), rawTarget.getZ());
        if (target == null) target = rawTarget;

        usingRoad = planRoute(target);
        logNav("POI", target);
        wpIndex = 1; // skip wp[0] (= start pos)
        moveToNext(wanderSpeed, target);
        return true;
    }

    // ════════════════════════════════════════════════════════════════
    // WANDERING
    // ════════════════════════════════════════════════════════════════

    private void startWander() {
        Log.info(TAG, "[Citizen] %s startWander".formatted(tourist.getTouristName()));
        wanderCooldown = 0;
        wanderEvaluateTick = 300 + tourist.getRandom().nextInt(200);
    }

    private void tickWander() {
        BlockPos anchor = tourist.getWanderAnchor();
        // If no anchor, use current position
        if (anchor == null) {
            anchor = tourist.blockPosition();
            tourist.setWanderAnchor(anchor);
            tourist.setWanderRadius(8);
        }
        int radius = tourist.getWanderRadius();
        if (radius <= 0) radius = 8;
        BlockPos pos = tourist.blockPosition();
        int manDist = Math.abs(pos.getX() - anchor.getX()) + Math.abs(pos.getZ() - anchor.getZ());
        var nav = tourist.getNavigation();

        // Too far from anchor → head back
        if (manDist > radius + 3) {
            if (nav.isDone())
                nav.moveTo(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5, wanderSpeed);
            tickWanderEvaluate();
            return;
        }

        // Periodic mode re-evaluation
        if (tickWanderEvaluate()) return;

        // Random step within radius
        if (--wanderCooldown <= 0) {
            int tx = anchor.getX() + tourist.getRandom().nextInt(radius * 2 + 1) - radius;
            int tz = anchor.getZ() + tourist.getRandom().nextInt(radius * 2 + 1) - radius;
            BlockPos g = findGround(tx, anchor.getY(), tz);
            if (g != null)
                nav.moveTo(g.getX() + 0.5, g.getY(), g.getZ() + 0.5, wanderSpeed);
            wanderCooldown = 60 + tourist.getRandom().nextInt(120);
        }
    }

    /** @return true if mode was switched (caller should return immediately) */
    private boolean tickWanderEvaluate() {
        if (--wanderEvaluateTick > 0) return false;

        MoveMode next = decideNextMode(MoveMode.WANDERING);
        if (next != currentMode) {
            switchMode(next);
            dispatchStart();
            return true;
        }
        // Stay in WANDERING, reset timer
        wanderEvaluateTick = 300 + tourist.getRandom().nextInt(200);
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    // Mode transitions
    // ════════════════════════════════════════════════════════════════

    /**
     * Probability-based next mode selection.
     *
     * <table>
     *   <tr><th>From</th><th>BUILDING</th><th>POI</th><th>WANDER</th></tr>
     *   <tr><td>VISITING_BUILDING</td><td>60%</td><td>25%</td><td>15%</td></tr>
     *   <tr><td>EXPLORING_POI</td><td>50%</td><td>30%</td><td>20%</td></tr>
     *   <tr><td>WANDERING</td><td>40%</td><td>30%</td><td>30%</td></tr>
     *   <tr><td>(initial)</td><td>50%</td><td>25%</td><td>25%</td></tr>
     * </table>
     *
     * <p>If BUILDING is selected but no colony buildings are available,
     * falls back to POI (or WANDER if no POIs).
     */
    private MoveMode decideNextMode(@Nullable MoveMode from) {
        double roll = tourist.getRandom().nextDouble();
        double bProb, pProb; // building, poi probabilities

        if (from == null) {
            bProb = 0.50; pProb = 0.25;
        } else {
            switch (from) {
                case VISITING_BUILDING -> { bProb = 0.60; pProb = 0.25; }
                case EXPLORING_POI ->     { bProb = 0.50; pProb = 0.30; }
                default ->                { bProb = 0.40; pProb = 0.30; }
            }
        }

        if (roll < bProb) {
            // Check if there are actually buildings to visit
            if (hasBuildingsAvailable()) return MoveMode.VISITING_BUILDING;
            // Fall through to POI check
            roll = bProb + pProb + 0.01; // force skip to POI check below
        }
        if (roll < bProb + pProb) {
            if (!tourist.getPoiList().isEmpty()) return MoveMode.EXPLORING_POI;
            // No POIs → wander
        }
        return MoveMode.WANDERING;
    }

    /** Quick check whether any valid building targets exist. */
    private boolean hasBuildingsAvailable() {
        UUID colonyId = tourist.getColonyId();
        if (colonyId == null) return false;
        BuildingApi api = getBuildingApi();
        if (api == null) return false;
        List<BuildingData> all = api.getColonyBuildings(colonyId);
        if (all.isEmpty()) return false;
        for (BuildingData b : all) {
            String cat = b.getCategory();
            if (!"shop".equals(cat) && !"service".equals(cat)) continue;
            if (b.isShutdown() || !b.isStructureIntact()) continue;
            if (tourist.hasVisitedBuilding(b.getBuildingId())) continue;
            return true;
        }
        return false;
    }

    private void switchMode(MoveMode next) {
        if (currentMode != next) {
            Log.debug(TAG, "[Tourist] {} mode {} → {}",
                    tourist.getTouristName(), currentMode, next);
        }
        currentMode = next;
        waypoints = null;
        wpIndex = 0;
        tourist.getNavigation().stop();
        // Sync display state with actual movement
        tourist.applyState(mapModeToState(next));
    }

    private static TouristState mapModeToState(MoveMode mode) {
        return switch (mode) {
            case VISITING_BUILDING -> TouristState.VISITING;
            case EXPLORING_POI -> TouristState.EXPLORING;
            case WANDERING -> TouristState.WANDERING;
        };
    }

    // ════════════════════════════════════════════════════════════════
    // Building-visit: planning & interaction
    // (unchanged from original tourist logic)
    // ════════════════════════════════════════════════════════════════

    private void showActionBar(String msg) {
        if (tourist.level().isClientSide) return;
        Component comp = Component.literal(msg);
        for (ServerPlayer p : tourist.level().getEntitiesOfClass(
                ServerPlayer.class,
                tourist.getBoundingBox().inflate(32))) {
            p.sendSystemMessage(comp, true);
        }
    }

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

            if ("service".equals(cat) && tourist.getServiceCooldown(b.getBuildingId()) > tourist.tickCount)
                continue;

            BuildingState state = savedData.getBuilding(b.getBuildingId());
            if (state == null) continue;

            if ("shop".equals(cat)) {
                ShopStockManager stock = ShopStockManager.getActive();
                if (stock != null && stock.hasStock(b.getBuildingId())) {
                    shopTargets.add(state);
                }
            } else {
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
            StringBuilder report = new StringBuilder();
            report.append(String.format(
                    "[Tourist] %s | NO BUILDING | colony=%s | phase=%s | sat=%d | night=%s | visited=%d | cooldown=%s",
                    tourist.getTouristName(), tourist.getColonyId(),
                    isNight ? "night" : "day", sat, isNight,
                    tourist.getVisitedBuildings().size(),
                    inServiceCooldown ? "YES" : "no"));

            int total = allBuildings.size();
            int noShopService = 0, shutdown = 0, notIntact = 0, alreadyVisited = 0;
            int svcCooldown = 0, noStock = 0, hotelFull = 0, noState = 0;

            for (BuildingData b : allBuildings) {
                String cat = b.getCategory();
                if (!"shop".equals(cat) && !"service".equals(cat)) { noShopService++; continue; }
                if (b.isShutdown()) { shutdown++; continue; }
                if (!b.isStructureIntact()) { notIntact++; continue; }
                if (tourist.hasVisitedBuilding(b.getBuildingId())) { alreadyVisited++; continue; }
                if ("service".equals(cat) && tourist.getServiceCooldown(b.getBuildingId()) > tourist.tickCount) { svcCooldown++; continue; }

                BuildingState state = savedData.getBuilding(b.getBuildingId());
                if (state == null) { noState++; continue; }

                if ("shop".equals(cat)) {
                    ShopStockManager stockMgr = ShopStockManager.getActive();
                    if (stockMgr != null && !stockMgr.hasStock(b.getBuildingId())) { noStock++; }
                } else {
                    if (inServiceCooldown) { svcCooldown++; continue; }
                    if (isHotelBuilding(b.getBuildingId())) {
                        HotelStayHandler hotel = HotelStayHandler.getActive();
                        if (hotel != null && !hotel.hasVacancy(b.getBuildingId())) { hotelFull++; }
                    }
                }
            }

            report.append(String.format(
                    "\n  ALL=%d | shop+service=%d | shutdown=%d | not_intact=%d | visited=%d | svc_cooldown=%d | no_stock=%d | hotel_full=%d | no_state=%d",
                    total, total - noShopService,
                    shutdown, notIntact, alreadyVisited, svcCooldown, noStock, hotelFull, noState));
            report.append(String.format(
                    "\n  hotel_targets=%d | shop_targets=%d | service_targets=%d | canUseHotel=%s",
                    hotelTargets.size(), shopTargets.size(), serviceTargets.size(), canUseHotel));

            Log.info(TAG, report.toString());
            return;
        }

        BlockPos interactionTarget = api.getInteractionTarget(chosen.getBuildingId());
        if (interactionTarget == null) interactionTarget = chosen.getAnchor();

        tourist.setTargetBuildingId(chosen.getBuildingId());
        tourist.setTargetBuildingCategory(chosen.getCategory());
        tourist.setCommuteTarget(interactionTarget);
        Log.debug(TAG, "[Tourist] {} next stop: {} '{}' at {}",
                tourist.getTouristName(), chosen.getCategory(),
                chosen.getBuildingTypeId(), interactionTarget.toShortString());
    }

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
            String bldType = getBuildingTypeId(buildingId);
            showActionBar("🛒 " + tourist.getTouristName() + " 从 "
                    + (bldType != null ? bldType : "商店") + " 购买了 " + purchased
                    + " | 满意+" + gain + " 精力-20");
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

        int cooldownTicks = Config.SERVICE_COOLDOWN_TICKS.get();
        if (cooldownTicks > 0) {
            int endTick = tourist.tickCount + cooldownTicks;
            tourist.setServiceCooldown(buildingId, endTick);
            tourist.setServiceCooldownEndTick(endTick);
        }

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
                            Log.warn(TAG, "[Tourist] Unknown element type '{}' in service {} elementOutput",
                                    entry.getKey(), shortId(buildingId));
                        }
                    }
                }
            }
        }

        String bldType = getBuildingTypeId(buildingId);
        showActionBar("🔧 " + tourist.getTouristName() + " 使用了 "
                + (bldType != null ? bldType : "服务建筑")
                + " | 满意+" + gain + " 精力-" + svc.energyPerUse());
    }

    // ── Preference / satisfaction ──

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

    private int threeValueSum(@Nullable UUID buildingId) {
        int[] v = getEffectiveValues(buildingId);
        return v[0] + v[1] + v[2];
    }

    private int computeMatchScore(@Nullable UUID buildingId) {
        String typeId = getBuildingTypeId(buildingId);
        if (typeId == null) return 0;
        int typePref = tourist.getTypePreference(typeId);
        int sum = threeValueSum(buildingId);
        return typePref * sum;
    }

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

    private void applyPreferenceDecay(UUID buildingId) {
        int decay = Config.TOURIST_PREFERENCE_DECAY.get();
        if (decay <= 0) return;
        String typeId = getBuildingTypeId(buildingId);
        if (typeId == null) return;
        tourist.adjustTypePreference(typeId, -decay);
        Log.debug(TAG, "[Tourist] {} decay preference for {} → {}",
                tourist.getTouristName(), typeId, tourist.getTypePreference(typeId));
    }

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

    // ════════════════════════════════════════════════════════════════
    // Shared navigation
    // ════════════════════════════════════════════════════════════════

    private void beginNavigation(BlockPos target, double speed) {
        waypoints = null;
        wpIndex = 0;
        stuckTicks = 0;
        idleTicks = 0;
        usingRoad = planRoute(target);
        wpIndex = 1;
        moveToNext(speed, target);
        Log.debug(TAG, "[Tourist] {} heading to {} via {}",
                tourist.getTouristName(), target.toShortString(),
                usingRoad ? "road" : "direct");
    }

    private boolean planRoute(BlockPos target) {
        RoadApi roadApi = getRoadApiSilently();
        if (roadApi == null) return false;
        RoadNetwork network = roadApi.getNetwork(null);
        if (network == null || network.isEmpty()) return false;

        BlockPos from = tourist.blockPosition();
        List<RouteSegment> segments = RoadRouter.planNpc(
                network,
                new PathPoint(from.getX(), from.getY(), from.getZ()),
                new PathPoint(target.getX(), target.getY(), target.getZ()));
        if (segments.isEmpty()) return false;

        List<BlockPos> wps = new ArrayList<>();
        RouteSegment first = segments.get(0);
        wps.add(jitter(new BlockPos((int) first.fromX(), (int) first.fromY(), (int) first.fromZ())));
        for (RouteSegment seg : segments) {
            wps.add(jitter(new BlockPos((int) seg.toX(), (int) seg.toY(), (int) seg.toZ())));
        }
        waypoints = wps.toArray(new BlockPos[0]);
        return true;
    }

    /** Deterministic ±0–1 XZ offset so entities don't walk on exactly the same path. */
    private BlockPos jitter(BlockPos raw) {
        long seed = tourist.getUUID().hashCode() + raw.hashCode();
        int dx = (int) ((seed & 3) - 1);         // -1, 0, or +1
        int dz = (int) (((seed >> 16) & 3) - 1); // -1, 0, or +1
        return raw.offset(dx, 0, dz);
    }

    private void moveToNext(double spd, BlockPos fallback) {
        BlockPos wp = currentTarget(fallback);
        BlockPos ground = findGround(wp.getX(), wp.getY(), wp.getZ());
        if (ground != null) wp = ground;
        tourist.getNavigation().moveTo(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5, spd);
    }

    @Nullable
    private BlockPos currentTarget() {
        if (waypoints != null && wpIndex < waypoints.length) return waypoints[wpIndex];
        return null;
    }

    private BlockPos currentTarget(BlockPos fallback) {
        BlockPos wp = currentTarget();
        return wp != null ? wp : fallback;
    }

    // ── Logging ──

    private void logNav(String label, BlockPos target) {
        String name = tourist.getTouristName();
        BlockPos from = tourist.blockPosition();
        if (usingRoad && waypoints != null) {
            Log.info(TAG, "[Tourist] {} {} ROAD → {} ({} wps, {}→{})",
                    name, label, target.toShortString(), waypoints.length,
                    from.toShortString(), target.toShortString());
        } else {
            Log.info(TAG, "[Tourist] {} {} VANILLA → {} ({}→{})",
                    name, label, target.toShortString(),
                    from.toShortString(), target.toShortString());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════

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
