package com.wsteam.wandscape.tourist.internal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.building.internal.ShopStockManager;
import com.wsteam.wandscape.core.event.NarrativeEventTriggered;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.nav.RoadWalkPlanner;
import com.wsteam.wandscape.engine.service.ParticleService;
import com.wsteam.wandscape.projection.BuildingRotation;
import com.wsteam.wandscape.road.core.RoadNetwork;
import com.wsteam.wandscape.shared.data.NarrativeEvent;
import com.wsteam.wandscape.shared.data.VisitMemory;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.api.RoadApi;
import com.wsteam.wandscape.shared.client.bubble.TransientBubbleStore;
import com.wsteam.wandscape.shared.data.BuildingData;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.tourist.entity.TouristEntity;
import com.wsteam.wandscape.tourist.network.TouristBubblePacket;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
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

    // ── Real stuck detection ──
    private BlockPos lastPos;
    private int noMoveTicks;
    private int totalNavTicks;

    // ── Building-visit state ──
    private int idleTicks;
    private static final int POST_TOUR_IDLE_TICKS = 200;

    // ── POI state ──
    private int poiPauseTicks;

    // ── Wander state ──
    private int wanderCooldown;
    private int wanderEvaluateTick;

    // ── Roof-rescue insurance ──
    /** Last position used to detect a stuck-on-roof situation while roaming. */
    @Nullable
    private BlockPos rescueLastPos;
    /** Consecutive ticks with no meaningful movement while roaming. */
    private int roofStuckTicks;
    /** Zero-movement ticks on a floating surface before teleporting down. */
    private static final int ROOF_STUCK_TICKS = 80;
    /** Zero-movement ticks during active wander nav before teleporting to anchor. */
    private static final int WANDER_STUCK_TICKS = 120;
    /** Last path-node index seen while wandering — the primary stuck-detection signal. */
    private int lastNodeIndex = -1;
    /** Min ticks before re-picking a wander target after the current path finishes. */
    private static final int WANDER_RECHOOSE_TICKS = 20;
    /** Default wander radius (blocks) around the (drifting) anchor. */
    private static final int WANDER_RADIUS = 12;

    // ── Indoor / outdoor navigation phases ──
    /** True when the tourist is inside a building (micro-navigation phase). */
    private boolean indoorPhase;
    /** True when the tourist has finished interacting and is exiting the building. */
    private boolean exitingPhase;
    /** The building entry point — macro navigation destination, fallback for micro exit. */
    @Nullable
    private BlockPos entryPoint;
    /** The precise interaction point inside the building. */
    @Nullable
    private BlockPos interactPoint;
    /** World-space tourist interact AABB zones for indoor arrival detection (Y-expanded for entity height). */
    private List<BoundingBox> touristInteractZones = List.of();

    /** Push current debug state to entity synched data for client-side renderer. */
    private void syncDebugData() {
        tourist.setDebugEntryPoint(entryPoint);
        tourist.setDebugInteractPoint(interactPoint);
        tourist.setDebugIndoorPhase(indoorPhase);
    }

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

        // ── Roof-rescue insurance: stuck on a floating surface → teleport down ──
        if (tickRoofRescue()) {
            return;
        }

        // ── Forced move mode (command override) ──
        TouristState forced = tourist.getForcedMoveMode();
        if (forced != null) {
            MoveMode mapped = mapStateToMoveMode(forced);
            if (mapped != null && mapped != currentMode) {
                Log.info(TAG, "[Tourist] {} forced mode {} (command override)",
                        tourist.getTouristName(), mapped);
                switchMode(mapped);
                dispatchStart(); // plan target + begin navigation
            }
            tourist.forceMoveMode(null); // consume the override
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

        // Show arrival narrative on first building visit (journey start)
        if (tourist.getRecentVisits().isEmpty()) {
            long dayTime = tourist.level().getDayTime() % 24000;
            String dayPhase = dayTime < 6000 ? "morning"
                    : dayTime < 13000 ? "afternoon" : "night";
            NarrativeEvent arrival = NarrativeGenerator.generateArrival(
                    tourist.getTouristName(), dayPhase, tourist.level().getGameTime());
            showActionBar(arrival.text());
            emitNarrativeEvent(arrival);
        }

        beginNavigation(tourist.getCommuteTarget(), touristSpeed);
    }

    private void tickBuildingVisit() {
        if (indoorPhase) {
            tickIndoorNav();
        } else {
            tickOutdoorNav();
        }
    }

    /** Macro-navigation phase: approach building entry point via road network. */
    private void tickOutdoorNav() {
        BlockPos target = tourist.getCommuteTarget();
        if (target == null) {
            idleTicks++;
            if (idleTicks > POST_TOUR_IDLE_TICKS) {
                planNextBuilding();
                if (tourist.getCommuteTarget() != null) {
                    idleTicks = 0;
                    beginNavigation(tourist.getCommuteTarget(), touristSpeed);
                } else {
                    switchMode(MoveMode.WANDERING);
                    startWander();
                }
            }
            return;
        }

        // Check if we're close enough to the building to switch to indoor micro-nav
        UUID buildingId = tourist.getTargetBuildingId();
        if (buildingId != null && isWithinDistanceOfBbox(buildingId, Config.MICRO_NAV_SWITCH_DISTANCE.get())) {
            switchToIndoorNav();
            return;
        }

        var nav = tourist.getNavigation();
        BlockPos pos = tourist.blockPosition();

        // ── Real stuck detection & hard fallback ──
        totalNavTicks++;
        if (lastPos != null && pos.distSqr(lastPos) < 1.0) {
            noMoveTicks++;
        } else {
            noMoveTicks = 0;
            lastPos = pos;
        }

        if (noMoveTicks > 100 || totalNavTicks > 600) {
            Log.info(TAG, "[Tourist] {} outdoor nav hard fallback. Teleporting near {}", tourist.getTouristName(), target.toShortString());
            BlockPos tpTarget = currentTarget(target);
            if (tpTarget != null) {
                BlockPos ground = findGround(tpTarget.getX(), tpTarget.getY(), tpTarget.getZ());
                if (ground != null) tpTarget = ground;
                tourist.setPos(tpTarget.getX() + 0.5, tpTarget.getY(), tpTarget.getZ() + 0.5);
                noMoveTicks = 0;
                totalNavTicks = 0;
            }
            return;
        }

        // Check arrival at entry point (fallback if proximity check doesn't fire)
        double distSqr = pos.distSqr(target);
        int interactionRange = getInteractionRange();
        if (distSqr < interactionRange * interactionRange) {
            // Reached entry point — switch to indoor micro-nav
            switchToIndoorNav();
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

    /** Micro-navigation phase: inside building, navigate to interact point then exit. */
    private void tickIndoorNav() {
        UUID buildingId = tourist.getTargetBuildingId();
        if (buildingId == null) {
            finishBuildingStop();
            return;
        }

        var nav = tourist.getNavigation();
        BlockPos pos = tourist.blockPosition();

        // ── Real stuck detection & hard fallback ──
        totalNavTicks++;
        if (lastPos != null && pos.distSqr(lastPos) < 1.0) {
            noMoveTicks++;
        } else {
            noMoveTicks = 0;
            lastPos = pos;
        }

        if (noMoveTicks > 100 || totalNavTicks > 400) {
            if (exitingPhase) {
                // Leaving the building: keep teleporting toward the entry ground.
                BlockPos tpTarget = entryPoint != null ? entryPoint : tourist.getCommuteTarget();
                if (tpTarget == null) {
                    finishBuildingStop();
                    return;
                }
                BlockPos ground = findGround(tpTarget.getX(), tpTarget.getY(), tpTarget.getZ());
                if (ground != null) tpTarget = ground;
                Log.info(TAG, "[Tourist] {} indoor exit fallback. Teleporting to {}", tourist.getTouristName(), tpTarget.toShortString());
                tourist.setPos(tpTarget.getX() + 0.5, tpTarget.getY(), tpTarget.getZ() + 0.5);
                noMoveTicks = 0;
                totalNavTicks = 0;
            } else {
                // Stuck navigating to the interact point. Do NOT teleport back onto the
                // interact point — that just re-loops. Abandon the visit instead.
                abandonBuildingVisit();
            }
            return;
        }

        if (exitingPhase) {
            // Heading back to entry point after interaction
            BlockPos exitTarget = entryPoint != null ? entryPoint : tourist.getCommuteTarget();
            if (exitTarget == null) {
                finishBuildingStop();
                return;
            }

            BlockPos ground = findGround(exitTarget.getX(), exitTarget.getY(), exitTarget.getZ());
            if (ground != null) exitTarget = ground;

            double distSqr = pos.distSqr(exitTarget);
            if (distSqr < 4.0 || !isInsideBuilding(buildingId)) {
                // Reached exit point or left building → back to macro
                Log.debug(TAG, "[Tourist] {} exited building, switching to macro nav",
                        tourist.getTouristName());
                finishBuildingStop();
                return;
            }

            // Stuck recovery
            if (nav.isDone()) {
                if (++stuckTicks > 40) {
                    stuckTicks = 0;
                }
                nav.moveTo(exitTarget.getX() + 0.5, exitTarget.getY(), exitTarget.getZ() + 0.5, touristSpeed);
            } else {
                stuckTicks = Math.max(0, stuckTicks - 1);
            }
            return;
        }

        // Navigating to interact point
        BlockPos target = interactPoint;
        if (target == null) {
            // Fallback: use commute target
            target = tourist.getCommuteTarget();
        }
        if (target == null) {
            finishBuildingStop();
            return;
        }

        // interactPoint is already a walkable spot (spiral-scanned for air-above-solid);
        // do NOT re-derive ground via findGround — it scans from Y+5 downward and can
        // land on the roof/shelf above the interaction floor.

        // Arrival check: if interact_zones exist, check AABB containment (Y ±2 for entity height);
        // otherwise fall back to distance check
        boolean arrived;
        if (!touristInteractZones.isEmpty()) {
            arrived = false;
            for (BoundingBox zone : touristInteractZones) {
                if (zone.isInside(pos)) {
                    arrived = true;
                    break;
                }
            }
        } else {
            double distSqr = pos.distSqr(target);
            arrived = distSqr <= 4.0;
        }

        if (arrived) {
            // Arrived at interact point — perform the interaction immediately.
            // Effects (satisfaction/energy/itinerary) are recorded now, and the
            // building's interaction_duration doubles as the rest-cooldown: the
            // tourist then wanders / visits POIs during that window.
            tourist.getNavigation().stop();

            boolean hotelStayed = performBuildingInteraction();
            if (hotelStayed) {
                return; // Hotel check-in handled everything
            }
            // After interaction, start exiting
            if (entryPoint != null && isInsideBuilding(buildingId)) {
                exitingPhase = true;
                stuckTicks = 0;
                noMoveTicks = 0;
                totalNavTicks = 0;
                BlockPos exitGround = findGround(entryPoint.getX(), entryPoint.getY(), entryPoint.getZ());
                BlockPos exitTarget = exitGround != null ? exitGround : entryPoint;
                nav.moveTo(exitTarget.getX() + 0.5, exitTarget.getY(), exitTarget.getZ() + 0.5, touristSpeed);
                Log.debug(TAG, "[Tourist] {} interaction done, exiting to {}",
                        tourist.getTouristName(), entryPoint.toShortString());
            } else {
                // Not inside building or no entry point → finish directly
                finishBuildingStop();
            }
            return;
        }

        // Stuck recovery for indoor navigation
        if (nav.isDone()) {
            if (++stuckTicks > 40) {
                stuckTicks = 0;
            }
            nav.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, touristSpeed);
        } else {
            stuckTicks = Math.max(0, stuckTicks - 1);
        }
    }

    /** Switch from outdoor macro-nav to indoor micro-nav. */
    private void switchToIndoorNav() {
        indoorPhase = true;
        exitingPhase = false;
        syncDebugData();
        waypoints = null;
        wpIndex = 0;
        stuckTicks = 0;
        lastPos = null;
        noMoveTicks = 0;
        totalNavTicks = 0;
        usingRoad = false;
        BlockPos target = interactPoint;
        if (target == null) target = tourist.getCommuteTarget();
        if (target != null) {
            tourist.getNavigation().moveTo(
                    target.getX() + 0.5, target.getY(), target.getZ() + 0.5, touristSpeed);
            Log.debug(TAG, "[Tourist] {} switching to indoor micro-nav → {}",
                    tourist.getTouristName(), target.toShortString());
        }
    }

    /** Check if the tourist is within {@code dist} blocks of a building's bounding box. */
    private boolean isWithinDistanceOfBbox(UUID buildingId, int dist) {
        BuildingApi api = getBuildingApi();
        if (api == null) return false;
        var data = api.getBuilding(buildingId);
        if (!(data instanceof BuildingState state)) return false;
        net.minecraft.world.level.levelgen.structure.BoundingBox bbox = state.getBounds();
        if (bbox == null) return false;
        BlockPos pos = tourist.blockPosition();
        return pos.getX() >= bbox.minX() - dist && pos.getX() <= bbox.maxX() + dist
                && pos.getZ() >= bbox.minZ() - dist && pos.getZ() <= bbox.maxZ() + dist
                && pos.getY() >= bbox.minY() - 1 && pos.getY() <= bbox.maxY() + 1;
    }

    /** Check if the tourist is inside a building's bounding box. */
    private boolean isInsideBuilding(UUID buildingId) {
        BuildingApi api = getBuildingApi();
        if (api == null) return false;
        var data = api.getBuilding(buildingId);
        if (!(data instanceof BuildingState state)) return false;
        net.minecraft.world.level.levelgen.structure.BoundingBox bbox = state.getBounds();
        if (bbox == null) return false;
        return bbox.isInside(tourist.blockPosition());
    }

    private void onBuildingArrived() {
        boolean hotelStayed = performBuildingInteraction();
        if (!hotelStayed) {
            finishBuildingStop();
        }
    }

    /**
     * Execute the building interaction (shop, service, or hotel check-in).
     * Does NOT handle navigation cleanup or mode switching — callers must
     * handle that themselves.
     *
     * @return true if the tourist checked into a hotel (caller should stop navigation)
     */
    private boolean performBuildingInteraction() {
        tourist.getNavigation().stop();
        waypoints = null;
        wpIndex = 0;
        idleTicks = 0;

        UUID buildingId = tourist.getTargetBuildingId();
        if (buildingId == null) {
            return false;
        }

        // Re-validate the target building still exists and is operational. A building
        // may be demolished/damaged while the tourist was en route — never settle an
        // interaction against a ghost. Returning true tells the caller to stop
        // navigation; finishBuildingStop already re-planned the next move.
        BuildingApi api = getBuildingApi();
        var target = api != null ? api.getBuilding(buildingId) : null;
        if (target == null || target.isShutdown() || !target.isStructureIntact() || target.isDemolishing()) {
            Log.info(TAG, "[Tourist] {} skipped interaction with invalid building {} ({})",
                    tourist.getTouristName(), shortId(buildingId),
                    target == null ? "removed" : target.getBuildingTypeId());
            finishBuildingStop();
            return true;
        }

        String category = tourist.getTargetBuildingCategory();
        String bldType = getBuildingTypeId(buildingId);
        boolean isHotel = isHotelBuilding(buildingId);

        if (isHotel) {
            long dayTime = tourist.level().getDayTime() % 24000;
            boolean isNight = dayTime >= 13000;
            int sat = tourist.getSatisfaction();
            boolean energyDepleted = tourist.getEnergy() <= 0;
            // 入住条件: 满意度 >= 50 且 < 100, 同时 到了夜晚 或 精力耗尽
            if (sat >= 50 && sat < 100 && (isNight || energyDepleted)) {
                HotelStayHandler hotel = HotelStayHandler.getActive();
                UUID colonyId = tourist.getColonyId();
                if (hotel != null && colonyId != null && hotel.checkIn(tourist, buildingId, colonyId)) {
                    tourist.addVisitedBuilding(buildingId);
                    applyPreferenceDecay(buildingId);
                    tourist.setCommuteTarget(null);
                    tourist.setTargetBuildingId(null);
                    tourist.setTargetBuildingCategory(null);
                    indoorPhase = false;
                    exitingPhase = false;
                    syncDebugData();
                    showActionBar("✨ " + tourist.getTouristName() + " 入住了旅馆 " + (bldType != null ? bldType : "?") + "!");

                    sparkleSatisfaction();

                    // Emit HOTEL_CHECKIN narrative
                    long gameTime = tourist.level().getGameTime();
                    String bldName = getBuildingDisplayName(buildingId, bldType);
                    NarrativeEvent checkinEvent = NarrativeGenerator.generateHotelCheckin(
                            tourist.getTouristName(), bldType != null ? bldType : "unknown", bldName, gameTime);
                    emitNarrativeEvent(checkinEvent);

                    return true;
                }
            }
            // Daytime or conditions not met: fall through to regular service interaction
        }

        if ("shop".equals(category)) {
            interactWithShop(buildingId);
        } else if ("service".equals(category)) {
            interactWithService(buildingId);
        }

        tourist.addVisitedBuilding(buildingId);
        return false;
    }

    private void finishBuildingStop() {
        tourist.setCommuteTarget(null);
        tourist.setTargetBuildingId(null);
        tourist.setTargetBuildingCategory(null);
        idleTicks = 0;
        indoorPhase = false;
        exitingPhase = false;
        entryPoint = null;
        interactPoint = null;
        touristInteractZones = List.of();
        syncDebugData();

        // Probability-based next mode
        switchMode(decideNextMode(MoveMode.VISITING_BUILDING));
        dispatchStart();
    }

    /**
     * Abandon a stuck building visit: teleport out near the entry point, put the
     * building on a short per-building cooldown, and force the tourist back to
     * WANDERING.
     *
     * <p>This is the "治标" safety net. It does NOT go through
     * {@link #finishBuildingStop()} — that re-plans probabilistically and can
     * immediately re-target the same trap. Forcing WANDERING (and applying a
     * per-building cooldown) breaks the stuck loop.
     */
    private void abandonBuildingVisit() {
        UUID failed = tourist.getTargetBuildingId();

        // Teleport to a safe, walkable ground spot near the entry point (already outside the bbox).
        BlockPos safe = entryPoint != null ? entryPoint : tourist.getCommuteTarget();
        if (safe == null) safe = tourist.blockPosition();
        BlockPos ground = findGround(safe.getX(), safe.getY(), safe.getZ());
        if (ground != null) safe = ground;
        tourist.setPos(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5);

        if (failed != null) {
            // Avoid re-targeting the same trap for a while, but do NOT set the global
            // rest cooldown — the tourist should still be able to visit other buildings.
            int avoidTicks = Math.max(1200, getInteractionDuration(failed) / 2);
            tourist.setServiceCooldown(failed, tourist.tickCount + avoidTicks);
        }

        tourist.setCommuteTarget(null);
        tourist.setTargetBuildingId(null);
        tourist.setTargetBuildingCategory(null);
        indoorPhase = false;
        exitingPhase = false;
        entryPoint = null;
        interactPoint = null;
        touristInteractZones = List.of();
        noMoveTicks = 0;
        totalNavTicks = 0;
        lastPos = null;
        lastNodeIndex = -1;
        syncDebugData();

        Log.warn(TAG, "[Tourist] {} abandoned stuck building visit, forced to WANDER", tourist.getTouristName());
        switchMode(MoveMode.WANDERING);
        startWander();
    }

    // ════════════════════════════════════════════════════════════════
    // EXPLORING_POI
    // ════════════════════════════════════════════════════════════════

    private void startPoiExplore() {
        waypoints = null;
        wpIndex = 0;
        stuckTicks = 0;
        poiPauseTicks = 0;
        lastPos = null;
        noMoveTicks = 0;
        totalNavTicks = 0;
        pickNextPoiAndGo();
    }

    private void tickPoiExplore() {
        var nav = tourist.getNavigation();
        BlockPos pos = tourist.blockPosition();

        // ── Real stuck detection & hard fallback ──
        if (poiPauseTicks <= 0) {
            totalNavTicks++;
            if (lastPos != null && pos.distSqr(lastPos) < 1.0) {
                noMoveTicks++;
            } else {
                noMoveTicks = 0;
                lastPos = pos;
            }

            if (noMoveTicks > 100 || totalNavTicks > 400) {
                BlockPos wp = currentTarget();
                if (wp != null) {
                    Log.info(TAG, "[Tourist] {} POI nav hard fallback. Teleporting to {}", tourist.getTouristName(), wp.toShortString());
                    BlockPos ground = findGround(wp.getX(), wp.getY(), wp.getZ());
                    if (ground != null) wp = ground;
                    tourist.setPos(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5);
                    noMoveTicks = 0;
                    totalNavTicks = 0;
                }
                return;
            }
        }

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
        lastPos = null;
        noMoveTicks = 0;
        totalNavTicks = 0;
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
        lastPos = null;
        noMoveTicks = 0;
        lastNodeIndex = -1;
    }

    private void tickWander() {
        BlockPos anchor = tourist.getWanderAnchor();
        // If no anchor, use current position
        if (anchor == null) {
            anchor = tourist.blockPosition();
            tourist.setWanderAnchor(anchor);
            tourist.setWanderRadius(WANDER_RADIUS);
        }
        int radius = tourist.getWanderRadius();
        if (radius <= 0) radius = WANDER_RADIUS;
        BlockPos pos = tourist.blockPosition();
        int manDist = Math.abs(pos.getX() - anchor.getX()) + Math.abs(pos.getZ() - anchor.getZ());
        var nav = tourist.getNavigation();

        // ── Stuck detection ──
        // Primary signal: the path's node index stops advancing (borrowed from
        // MineColonies PathingStuckHandler). Jittering around an obstacle while the
        // navigator still progresses along the path is NOT stuck, so we no longer rely
        // on raw net displacement (which mis-fired and caused "teleport near an
        // easy-to-reach target").
        if (!nav.isDone()) {
            Path path = nav.getPath();
            if (path != null) {
                int idx = path.getNextNodeIndex();
                if (idx == lastNodeIndex) {
                    noMoveTicks++;
                } else {
                    noMoveTicks = 0;
                    lastNodeIndex = idx;
                    lastPos = pos;
                }
            } else {
                // No path object but navigator busy (recomputing) → fall back to position.
                if (lastPos != null && pos.distSqr(lastPos) < 1.0) {
                    noMoveTicks++;
                } else {
                    noMoveTicks = 0;
                    lastPos = pos;
                }
            }
            if (noMoveTicks > WANDER_STUCK_TICKS) {
                BlockPos ground = findGround(anchor.getX(), anchor.getY(), anchor.getZ());
                BlockPos tp = ground != null ? ground : anchor;
                Log.info(TAG, "[Tourist] {} wander stuck, teleporting to {}", tourist.getTouristName(), tp.toShortString());
                tourist.setPos(tp.getX() + 0.5, tp.getY(), tp.getZ() + 0.5);
                nav.stop();
                noMoveTicks = 0;
                lastNodeIndex = -1;
                lastPos = null;
                return;
            }
        } else {
            noMoveTicks = 0;
        }

        // ── Anchor drift: let the wander area follow the tourist instead of pinning it
        //    to one fixed point (fixes the "activity range is tiny" complaint). ──
        if (manDist > radius / 2) {
            anchor = pos;
            tourist.setWanderAnchor(pos);
            manDist = 0;
        }

        // Too far from anchor → head back
        if (manDist > radius + 3) {
            if (nav.isDone())
                nav.moveTo(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5, wanderSpeed);
            tickWanderEvaluate();
            return;
        }

        // Periodic mode re-evaluation
        if (tickWanderEvaluate()) return;

        // ── Pick a new wander target on cooldown expiry, OR as soon as the current path
        //    finishes — no more idling on an exhausted/unreachable path. When the path
        //    finishes early, shorten the remaining cooldown so we re-pick within a few
        //    ticks instead of standing around. ──
        boolean wantNew = --wanderCooldown <= 0;
        if (!wantNew && nav.isDone()) {
            wanderCooldown = Math.min(wanderCooldown, WANDER_RECHOOSE_TICKS);
        }
        if (wanderCooldown <= 0) {
            BlockPos g = pickWanderTarget(anchor, radius);
            if (g != null)
                nav.moveTo(g.getX() + 0.5, g.getY(), g.getZ() + 0.5, wanderSpeed);
            wanderCooldown = 60 + tourist.getRandom().nextInt(120);
        }
    }

    /** Pick a reachable ground point within {@code radius} of {@code anchor}, retrying a few times. */
    @Nullable
    private BlockPos pickWanderTarget(BlockPos anchor, int radius) {
        for (int attempt = 0; attempt < 8; attempt++) {
            int tx = anchor.getX() + tourist.getRandom().nextInt(radius * 2 + 1) - radius;
            int tz = anchor.getZ() + tourist.getRandom().nextInt(radius * 2 + 1) - radius;
            BlockPos g = findGround(tx, anchor.getY(), tz);
            if (g != null && !isInsideAnyBuilding(g)) return g;
        }
        // Fallback: ground at the anchor (outside any building in the common case).
        return findGround(anchor.getX(), anchor.getY(), anchor.getZ());
    }

    /** True if {@code pos} lies inside any colony building's bounding box. */
    private boolean isInsideAnyBuilding(BlockPos pos) {
        UUID colonyId = tourist.getColonyId();
        if (colonyId == null) return false;
        BuildingApi api = getBuildingApi();
        if (api == null) return false;
        for (BuildingData b : api.getColonyBuildings(colonyId)) {
            if (!(b instanceof BuildingState state)) continue;
            BoundingBox box = state.getBounds();
            if (box != null && box.isInside(pos)) return true;
        }
        return false;
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
     *
     * <p>During the post-interaction rest cooldown (after a shop or service
     * interaction), the tourist is restricted to free movement: it wanders or
     * strolls to POIs but never selects a building visit until the cooldown ends.
     */
    private MoveMode decideNextMode(@Nullable MoveMode from) {
        // Rest cooldown: free movement (wander or POI strolling), never a building visit.
        if (isInRestCooldown()) {
            return (!tourist.getPoiList().isEmpty() && tourist.getRandom().nextDouble() < 0.5)
                    ? MoveMode.EXPLORING_POI
                    : MoveMode.WANDERING;
        }

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
            // Broke tourists don't target shops — service buildings remain visitable.
            if ("shop".equals(cat)) {
                ShopStockManager stock = ShopStockManager.getActive();
                if (stock == null || !stock.hasStock(b.getBuildingId()) || tourist.getWallet() <= 0) continue;
            }
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
        indoorPhase = false;
        exitingPhase = false;
        entryPoint = null;
        interactPoint = null;
        touristInteractZones = List.of();
        syncDebugData();
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

    /** Reverse of {@link #mapModeToState}. Returns null for IDLE/SLEEPING (no MoveMode equivalent). */
    @javax.annotation.Nullable
    private static MoveMode mapStateToMoveMode(TouristState state) {
        return switch (state) {
            case VISITING -> MoveMode.VISITING_BUILDING;
            case EXPLORING -> MoveMode.EXPLORING_POI;
            case WANDERING -> MoveMode.WANDERING;
            case IDLE, SLEEPING -> null;
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

    /** Notify nearby players of a purchase / service bubble event above this tourist. */
    private void sendBubble(int iconKind, @Nullable String iconId, int count,
                            int satBefore, int satAfter) {
        ServerLevel level = getServerLevel();
        if (level == null) return;
        TouristBubblePacket packet =
                new TouristBubblePacket(tourist.getId(), iconKind, iconId, count, satBefore, satAfter);
        for (ServerPlayer p : level.getEntitiesOfClass(
                ServerPlayer.class, tourist.getBoundingBox().inflate(32))) {
            PacketDistributor.sendToPlayer(p, packet);
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
            // 白天普通逛过 inn 会记入 visitedBuildings，但夜晚不应阻止入住 → 酒店豁免该排除
            boolean nightHotel = isNight && "service".equals(cat) && isHotelBuilding(b.getBuildingId());
            if (!nightHotel && tourist.hasVisitedBuilding(b.getBuildingId())) continue;

            if ("service".equals(cat) && tourist.getServiceCooldown(b.getBuildingId()) > tourist.tickCount)
                continue;

            BuildingState state = savedData.getBuilding(b.getBuildingId());
            if (state == null) continue;

            if ("shop".equals(cat)) {
                ShopStockManager stock = ShopStockManager.getActive();
                if (stock != null && stock.hasStock(b.getBuildingId())
                        && tourist.getWallet() > 0) {
                    shopTargets.add(state);
                }
            } else {
                if (inServiceCooldown) continue;

                if (isHotelBuilding(b.getBuildingId())) {
                    if (isNight) {
                        // Night: hotel is a check-in target
                        HotelStayHandler hotel = HotelStayHandler.getActive();
                        if (hotel != null && hotel.hasVacancy(b.getBuildingId())) {
                            hotelTargets.add(state);
                        }
                    } else {
                        // Daytime: hotel acts as a regular service building
                        serviceTargets.add(state);
                    }
                } else {
                    serviceTargets.add(state);
                }
            }
        }

        int sat = tourist.getSatisfaction();

        BuildingState chosen = null;
        if (isNight && sat >= 50 && sat < 100 && !hotelTargets.isEmpty()) {
            chosen = weightedPick(hotelTargets);
        } else if (!shopTargets.isEmpty()) {
            chosen = weightedPick(shopTargets);
        } else if (!serviceTargets.isEmpty()) {
            chosen = weightedPick(serviceTargets);
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
                    "\n  hotel_targets=%d | shop_targets=%d | service_targets=%d",
                    hotelTargets.size(), shopTargets.size(), serviceTargets.size()));

            Log.info(TAG, report.toString());
            return;
        }

        // Resolve entry point (macro nav destination) and interact point (micro nav destination)
        entryPoint = api.getEntryPoint(chosen.getBuildingId());
        if (entryPoint == null) entryPoint = chosen.getAnchor();
        interactPoint = api.getTouristInteractPoint(chosen.getBuildingId());
        if (interactPoint == null) interactPoint = chosen.getAnchor();

        // Compute world-space tourist interact zones for indoor arrival detection.
        // Zones MUST be rotated by the building's rotationSteps — interactPoint and the
        // rendered orange box are already rotated; an unrotated zone here makes the arrival
        // check fail for rotated buildings (tourist stands inside the zone but never
        // "arrives" → VISITING stuck).
        BuildingConfig chosenConfig = BuildingConfigLoader.getInstance().get(chosen.getBuildingTypeId());
        BlockPos chosenAnchor = chosen.getAnchor();
        int rotationSteps = chosen.getRotationSteps();
        if (chosenConfig != null && !chosenConfig.touristInteractAabb().isEmpty()) {
            List<BoundingBox> zones = new ArrayList<>();
            for (BuildingConfig.BoundaryBox zone : chosenConfig.touristInteractAabb()) {
                BuildingConfig.BoundaryBox rotated = BuildingRotation.rotateBoundary(zone, rotationSteps);
                int yMin = chosenAnchor.getY() + rotated.min().y() - 2; // 2 below for character feet
                int yMax = chosenAnchor.getY() + rotated.max().y() + 2; // 2 above for character head
                zones.add(new BoundingBox(
                        chosenAnchor.getX() + rotated.min().x(),
                        yMin,
                        chosenAnchor.getZ() + rotated.min().z(),
                        chosenAnchor.getX() + rotated.max().x(),
                        yMax,
                        chosenAnchor.getZ() + rotated.max().z()));
            }
            touristInteractZones = List.copyOf(zones);
        } else {
            touristInteractZones = List.of();
        }
        indoorPhase = false;
        exitingPhase = false;
        syncDebugData();

        tourist.setTargetBuildingId(chosen.getBuildingId());
        tourist.setTargetBuildingCategory(chosen.getCategory());
        // Macro navigation starts toward the entry point
        tourist.setCommuteTarget(entryPoint);
        Log.debug(TAG, "[Tourist] {} next stop: {} '{}' entry={} interact={}",
                tourist.getTouristName(), chosen.getCategory(),
                chosen.getBuildingTypeId(),
                entryPoint.toShortString(), interactPoint.toShortString());
    }

    /** True while the post-interaction rest cooldown is active (wander freely, skip building visits). */
    private boolean isInRestCooldown() {
        return tourist.getServiceCooldownEndTick() > tourist.tickCount;
    }

    private void interactWithShop(UUID buildingId) {
        ServerLevel level = getServerLevel();
        if (level == null) return;
        UUID colonyId = tourist.getColonyId();
        if (colonyId == null) return;

        var result = TouristSimulation.performShopInteraction(level, tourist, buildingId, colonyId);
        if (result == null) return;

        String bldType = TouristSimulation.getBuildingTypeId(level, buildingId);
        String bldName = getBuildingDisplayName(buildingId, bldType);
        VisitMemory memory = TouristSimulation.addVisitMemory(tourist, bldType, bldName, "shop",
                tourist.level().getGameTime(), result.satBefore(), result.satDelta(), result.energyDelta(),
                result.whatHappened());

        NarrativeEvent shopEvent = NarrativeGenerator.generateVisit(memory);
        emitNarrativeEvent(shopEvent);

        var purchase = result.purchase();
        sendBubble(TransientBubbleStore.ICON_ITEM,
                purchase != null ? purchase.itemId() : null,
                purchase != null ? purchase.count() : 0,
                result.satBefore(), tourist.getSatisfaction());

        sparkleSatisfaction();
    }

    private void interactWithService(UUID buildingId) {
        ServerLevel level = getServerLevel();
        if (level == null) return;
        UUID colonyId = tourist.getColonyId();
        if (colonyId == null) return;

        var result = TouristSimulation.performServiceInteraction(level, tourist, buildingId, colonyId);
        if (result == null) return;

        String bldType = TouristSimulation.getBuildingTypeId(level, buildingId);
        String bldName = getBuildingDisplayName(buildingId, bldType);
        VisitMemory memory = TouristSimulation.addVisitMemory(tourist, bldType, bldName, "service",
                tourist.level().getGameTime(), result.satBefore(), result.satDelta(), result.energyDelta(),
                result.whatHappened());

        NarrativeEvent serviceEvent = NarrativeGenerator.generateVisit(memory);
        emitNarrativeEvent(serviceEvent);

        var config = TouristSimulation.getConfig(level, buildingId);
        if (config != null && config.service() != null && !config.service().elementOutput().isEmpty()) {
            var entries = List.copyOf(config.service().elementOutput().entrySet());
            var pick = entries.get(tourist.level().random.nextInt(entries.size()));
            sendBubble(TransientBubbleStore.ICON_ELEMENT, pick.getKey(), pick.getValue(),
                    result.satBefore(), tourist.getSatisfaction());
        } else {
            sendBubble(TransientBubbleStore.ICON_NONE, null, 0, result.satBefore(), tourist.getSatisfaction());
        }

        sparkleSatisfaction();
    }

    /** 满意度提升：游客位置撒金色星光（商店/服务/酒店入住共用）。粒子纯装饰，缺失静默跳过。 */
    private void sparkleSatisfaction() {
        ServerLevel level = getServerLevel();
        if (level == null) return;
        ParticleService.burstColored(level, tourist.position().add(0, 1.2, 0),
                1.0f, 0.85f, 0.30f, 10, 0.12f, 25, false);
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

    private void applyPreferenceDecay(UUID buildingId) {
        if (TouristCooldownDebug.skipPreferenceDecay) return;
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
        lastPos = null;
        noMoveTicks = 0;
        totalNavTicks = 0;
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
        List<BlockPos> wps = RoadWalkPlanner.plan(roadApi, tourist.level(), from, target);
        if (wps.isEmpty()) return false;

        waypoints = wps.stream().map(this::jitter).toArray(BlockPos[]::new);
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

    /** Get the display name for a building, falling back to its type id. */
    private String getBuildingDisplayName(UUID buildingId, @javax.annotation.Nullable String typeId) {
        var config = BuildingConfigLoader.getInstance().get(typeId);
        if (config != null && config.displayName() != null && !config.displayName().isEmpty()) {
            return config.displayName();
        }
        return typeId != null ? typeId : "建筑";
    }

    private int getInteractionRange() {
        return Config.ARRIVAL_RADIUS.get();
    }

    /**
     * Get the interaction duration (in ticks) for the current building.
     * Tourist will stand still at the interact point for this duration
     * before the interaction effects are applied.
     */
    private int getInteractionDuration(UUID buildingId) {
        if (buildingId == null) return 0;
        BuildingApi api = getBuildingApi();
        if (api == null) return 0;
        var data = api.getBuilding(buildingId);
        if (data == null) return 0;
        var config = BuildingConfigLoader.getInstance().get(data.getBuildingTypeId());
        if (config == null) return 0;
        String cat = config.category();
        if ("shop".equals(cat) && config.shop() != null) {
            return config.shop().interactionDurationTicks();
        }
        if ("service".equals(cat) && config.service() != null) {
            return config.service().interactionDurationTicks();
        }
        return 0;
    }

    /** Universal insurance: if stuck on a floating surface (building roof) while roaming, teleport down. */
    private boolean tickRoofRescue() {
        // Never interfere with an active building interaction or POI stand-still.
        if (currentMode == MoveMode.VISITING_BUILDING || poiPauseTicks > 0) return false;
        BlockPos pos = tourist.blockPosition();
        if (rescueLastPos == null || pos.distSqr(rescueLastPos) >= 1.0) {
            rescueLastPos = pos;
            roofStuckTicks = 0;
            return false;
        }
        if (++roofStuckTicks <= ROOF_STUCK_TICKS) return false;
        if (!isStandingOnFloatingSurface()) return false;

        BlockPos below = findGroundBelow(pos.getX(), pos.getY() - 1, pos.getZ());
        if (below == null) return false;
        Log.info(TAG, "[Tourist] {} stuck on floating surface, rescuing down to {}", tourist.getTouristName(), below.toShortString());
        tourist.getNavigation().stop();
        tourist.setPos(below.getX() + 0.5, below.getY(), below.getZ() + 0.5);
        noMoveTicks = 0;
        totalNavTicks = 0;
        rescueLastPos = null;
        roofStuckTicks = 0;
        return true;
    }

    /** True if the tourist stands on a solid block with open air directly beneath it (building roof / shelf). */
    private boolean isStandingOnFloatingSurface() {
        var lvl = tourist.level();
        BlockPos feet = tourist.blockPosition();
        if (!lvl.getBlockState(feet).isAir()) return false;
        if (!lvl.getBlockState(feet.below()).isSolid()) return false;
        return lvl.getBlockState(feet.below(2)).isAir();
    }

    /** Scan downward from {@code startY} for the first walkable spot (air above solid). */
    @Nullable
    private BlockPos findGroundBelow(int x, int startY, int z) {
        var lvl = tourist.level();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos(
                x, Math.min(lvl.getMaxBuildHeight() - 1, startY), z);
        while (mp.getY() > lvl.getMinBuildHeight()) {
            if (lvl.getBlockState(mp).isAir() && lvl.getBlockState(mp.below()).isSolid()) {
                return mp.immutable();
            }
            mp.move(0, -1, 0);
        }
        return null;
    }

    /**
     * Find a safe, walkable ground spot at (x, z). Scans from the world surface height
     * at that column (not {@code baseY + 5}, which could land on a roof or upper floor)
     * and requires two solid blocks below so it never stops on a floating roof or a
     * thin shelf. Returns the block standing ON the ground.
     */
    @Nullable
    private BlockPos findGround(int x, int baseY, int z) {
        var lvl = tourist.level();
        int topY = Math.max(lvl.getMinBuildHeight(),
                Math.min(lvl.getMaxBuildHeight() - 1,
                        lvl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)));
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos(x, topY, z);
        while (mp.getY() > lvl.getMinBuildHeight()) {
            if (lvl.getBlockState(mp).isAir()
                    && lvl.getBlockState(mp.below()).isSolid()
                    && lvl.getBlockState(mp.below(2)).isSolid()) {
                return mp.immutable();
            }
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

    private static void emitNarrativeEvent(NarrativeEvent ne) {
        var world = WandscapeEngine.getWorld();
        if (world != null && world.eventBus != null) {
            world.eventBus.emit(new NarrativeEventTriggered(ne));
        }
    }
}
