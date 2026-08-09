package com.wsteam.wandscape.tourist.internal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.core.event.NarrativeEventTriggered;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.engine.nav.RoadWalkPlanner;
import com.wsteam.wandscape.engine.service.ParticleService;
import com.wsteam.wandscape.road.core.RoadNetwork;
import com.wsteam.wandscape.shared.data.Activity;
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
    /** The precise interaction point inside the building (spot 世界坐标). */
    @Nullable
    private BlockPos interactPoint;
    /** 当前占用的 spot 下标（-1 = 未占用）。 */
    private int claimedSpot = -1;
    /** True：游客正在 spot 上做动作（duration 倒计时中，做完才结算）。 */
    private boolean performingActivity;
    /** True：游客在建筑旁排队等待空 spot（spot 全满）。 */
    private boolean queueing;
    /** 排队已持续的 tick 数（超 TOURIST_QUEUE_WAIT_TOLERANCE_TICKS 放弃去别处）。 */
    private int queueTicks;

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
        // 预览假人：不参与 AI，站桩循环做动作
        return tourist.isAlive() && !tourist.isPreview();
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
        clearSpotState();
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

        // Target chunk unloaded while en route → can't path there; re-plan to a
        // loaded building (or wander). Prevents stalling at the load boundary.
        if (!tourist.level().isLoaded(target)) {
            tourist.setCommuteTarget(null);
            tourist.setTargetBuildingId(null);
            tourist.setTargetBuildingCategory(null);
            planNextBuilding();
            if (tourist.getCommuteTarget() != null) {
                beginNavigation(tourist.getCommuteTarget(), touristSpeed);
            } else {
                switchMode(MoveMode.WANDERING);
                startWander();
            }
            return;
        }

        // Check if we're close enough to the building to switch to indoor micro-nav
        UUID buildingId = tourist.getTargetBuildingId();
        if (buildingId != null && isWithinDistanceOfBbox(buildingId, Config.MICRO_NAV_SWITCH_DISTANCE.get())) {
            // Hotels: check in the moment the tourist reaches the building — it
            // teleports into a bed, no need to reach the exact interact point.
            if (tryHotelCheckIn(buildingId, getBuildingTypeId(buildingId))) {
                return;
            }
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
            noMoveTicks = 0;
            totalNavTicks = 0;
            BlockPos tp = TouristTeleport.findSafeSpot(serverLevel(), pos, tourist.getColonyId(), tourist.getTargetBuildingId());
            if (tp != null) {
                Log.info(TAG, "[Tourist] {} outdoor nav hard fallback. Teleporting to {}", tourist.getTouristName(), tp.toShortString());
                tourist.setPos(tp.getX() + 0.5, tp.getY(), tp.getZ() + 0.5);
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

        // 活动中（在 spot 上做动作）：duration 倒计时，结束才结算
        if (performingActivity) {
            tickActivity();
            return;
        }
        // 排队中（spot 全满）：轮询空 spot，超时放弃去别处
        if (queueing) {
            tickQueue();
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
            noMoveTicks = 0;
            totalNavTicks = 0;
            if (exitingPhase) {
                // Leaving the building: teleport to safe ground just outside the entry.
                BlockPos tp = TouristTeleport.findSafeSpotNearEntry(serverLevel(),
                        entryPoint != null ? entryPoint : tourist.getCommuteTarget(),
                        tourist.getColonyId());
                if (tp == null) {
                    finishBuildingStop();
                    return;
                }
                Log.info(TAG, "[Tourist] {} indoor exit fallback. Teleporting to {}", tourist.getTouristName(), tp.toShortString());
                tourist.setPos(tp.getX() + 0.5, tp.getY(), tp.getZ() + 0.5);
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

        // 到达判定：与目标 spot 点的距离（spot 单点寻路，无 AABB 交互区）
        double distSqr = pos.distSqr(target);
        if (distSqr <= 4.0) {
            // 到达 spot → 开始活动（站着做该 spot 的动作，duration 结束才结算）
            tourist.getNavigation().stop();
            startActivityAtSpot();
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

    /** 到达 spot：认领（若未认领）并开始做该 spot 的动作（duration 倒计时）。 */
    private void startActivityAtSpot() {
        UUID buildingId = tourist.getTargetBuildingId();
        if (buildingId == null) {
            finishBuildingStop();
            return;
        }
        ServerLevel level = serverLevel();
        int spot = claimedSpot;
        if (spot < 0) {
            if (level == null) {
                finishBuildingStop();
                return;
            }
            spot = TouristSimulation.claimSpot(level, buildingId);
            if (spot < 0) {
                // spot 全满 → 排队
                startQueueing();
                return;
            }
            claimedSpot = spot;
        }
        Activity action = TouristSimulation.interactSpotAction(level, buildingId, spot);
        int duration = Math.max(1, TouristSimulation.interactionDuration(level, buildingId));
        tourist.setCurrentActivity(action);
        tourist.setOccupiedSpot(spot);
        tourist.setActivityTicks(duration);
        performingActivity = true;
        queueing = false;
        faceSpot(level, buildingId, spot);
    }

    /** 活动期间面向 spot 朝向（游客做动作时面朝该方向）。 */
    private void faceSpot(ServerLevel level, UUID buildingId, int spot) {
        float yaw = TouristSimulation.spotFacing(level, buildingId, spot).toYRot();
        tourist.setYRot(yaw);
        tourist.setYHeadRot(yaw);
        tourist.yBodyRot = yaw;
    }

    /** 活动倒计时：duration 结束 → 释放 spot + 结算（四类交互）+ 退出。 */
    private void tickActivity() {
        // 活动期间持续面向 spot（look 控制/随机张望可能拉偏 yaw）
        UUID bid = tourist.getTargetBuildingId();
        if (bid != null && claimedSpot >= 0 && tourist.level() instanceof ServerLevel sl) {
            faceSpot(sl, bid, claimedSpot);
        }
        int remaining = tourist.getActivityTicks() - 1;
        tourist.setActivityTicks(remaining);
        if (remaining > 0) return;

        UUID buildingId = tourist.getTargetBuildingId();
        TouristSimulation.releaseSpot(buildingId, claimedSpot);
        claimedSpot = -1;
        tourist.setCurrentActivity(null);
        tourist.setOccupiedSpot(-1);
        performingActivity = false;

        if (buildingId != null) {
            performBuildingInteraction();
        }

        // After interaction, start exiting
        if (entryPoint != null && buildingId != null && isInsideBuilding(buildingId)) {
            exitingPhase = true;
            stuckTicks = 0;
            noMoveTicks = 0;
            totalNavTicks = 0;
            BlockPos exitGround = findGround(entryPoint.getX(), entryPoint.getY(), entryPoint.getZ());
            BlockPos exitTarget = exitGround != null ? exitGround : entryPoint;
            tourist.getNavigation().moveTo(exitTarget.getX() + 0.5, exitTarget.getY(), exitTarget.getZ() + 0.5, touristSpeed);
        } else {
            finishBuildingStop();
        }
    }

    /** 排队等待：轮询空 spot，超 TOURIST_QUEUE_WAIT_TOLERANCE_TICKS 放弃去别处。 */
    private void tickQueue() {
        if (++queueTicks > Config.TOURIST_QUEUE_WAIT_TOLERANCE_TICKS.get()) {
            abandonBuildingVisit();
            return;
        }
        ServerLevel level = serverLevel();
        UUID buildingId = tourist.getTargetBuildingId();
        if (level == null || buildingId == null) {
            abandonBuildingVisit();
            return;
        }
        int spot = TouristSimulation.claimSpot(level, buildingId);
        if (spot < 0) return; // 继续等
        claimedSpot = spot;
        queueing = false;
        queueTicks = 0;
        BlockPos target = TouristSimulation.spotWorldPos(level, buildingId, spot);
        if (target == null) {
            clearSpotState();
            finishBuildingStop();
            return;
        }
        interactPoint = target;
        tourist.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, touristSpeed);
    }

    /** 进入排队状态（spot 全满，在建筑旁等；仅机制，无可见标记）。 */
    private void startQueueing() {
        queueing = true;
        queueTicks = 0;
        tourist.setCurrentActivity(Activity.QUEUE);
        tourist.getNavigation().stop();
    }

    /** 清空 spot 占用与活动/排队状态（所有清理路径共用）。 */
    private void clearSpotState() {
        UUID buildingId = tourist.getTargetBuildingId();
        if (claimedSpot >= 0) {
            TouristSimulation.releaseSpot(buildingId, claimedSpot);
        }
        claimedSpot = -1;
        performingActivity = false;
        queueing = false;
        queueTicks = 0;
        tourist.setCurrentActivity(null);
        tourist.setOccupiedSpot(-1);
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

        // 到达建筑：认领一个空 spot（spot 数 = 同时交互人数上限），导航到该 spot 世界坐标。
        // spot 全满 → 排队（在建筑旁等空位）。
        ServerLevel level = serverLevel();
        UUID buildingId = tourist.getTargetBuildingId();
        int spot = -1;
        if (level != null && buildingId != null) {
            spot = TouristSimulation.claimSpot(level, buildingId);
        }
        if (spot >= 0) {
            claimedSpot = spot;
            BlockPos target = TouristSimulation.spotWorldPos(level, buildingId, spot);
            interactPoint = target;
            if (target != null) {
                tourist.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, touristSpeed);
                return;
            }
            // spot 坐标算不出（建筑已消失）→ 放弃本次访问
            clearSpotState();
            finishBuildingStop();
            return;
        }
        startQueueing();
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

    /**
     * Execute the building interaction（四类：shop/service/relax/atm 按 category 分发）。
     * 在 spot 活动结束后由 {@link #tickActivity()} 调用。
     */
    private void performBuildingInteraction() {
        tourist.getNavigation().stop();
        waypoints = null;
        wpIndex = 0;
        idleTicks = 0;

        UUID buildingId = tourist.getTargetBuildingId();
        if (buildingId == null) {
            return;
        }

        // Re-validate the target building still exists and is operational. A building
        // may be demolished/damaged while the tourist was en route — never settle an
        // interaction against a ghost.
        BuildingApi api = getBuildingApi();
        var target = api != null ? api.getBuilding(buildingId) : null;
        if (target == null || target.isShutdown() || !target.isStructureIntact() || target.isDemolishing()) {
            Log.info(TAG, "[Tourist] {} skipped interaction with invalid building {} ({})",
                    tourist.getTouristName(), shortId(buildingId),
                    target == null ? "removed" : target.getBuildingTypeId());
            finishBuildingStop();
            return;
        }

        String category = tourist.getTargetBuildingCategory();
        if (isHotelBuilding(buildingId)) {
            // 夜晚 + 未满条 → 入住；条件不满足（白天/满条）→ 当普通 service 交互
            if (tryHotelCheckIn(buildingId, getBuildingTypeId(buildingId))) {
                return;
            }
        }

        switch (category == null ? "" : category) {
            case "shop" -> interactWithShop(buildingId);
            case "relax" -> interactWithRelax(buildingId);
            case "atm" -> interactWithAtm(buildingId);
            default -> interactWithService(buildingId);
        }

        tourist.addVisitedBuilding(buildingId);
    }

    /**
     * Check a tourist into a hotel and settle it into a free bed. Also called
     * from {@link #tickOutdoorNav()} the moment the tourist reaches the hotel
     * building, so lodging never depends on reaching the exact interact point
     * (an unreachable one used to leave the tourist standing still at night and
     * trip the stuck-teleport fallback).
     *
     * @return true if the tourist checked in (caller must stop navigation)
     */
    private boolean tryHotelCheckIn(UUID buildingId, @Nullable String bldType) {
        if (!isHotelBuilding(buildingId)) return false;
        long dayTime = tourist.level().getDayTime() % 24000;
        boolean isNight = dayTime >= 13000;
        // 夜晚 + 未满条 → 入住（满条游客夜晚等离场，不入旅店）
        if (!(isNight && !tourist.isFullySatisfied())) return false;

        HotelStayHandler hotel = HotelStayHandler.getActive();
        UUID colonyId = tourist.getColonyId();
        if (hotel == null || colonyId == null) return false;
        if (!hotel.checkIn(tourist, buildingId, colonyId)) return false;

        tourist.addVisitedBuilding(buildingId);
        hotel.settleIntoBed(tourist, serverLevel(), buildingId);

        // 入住只睡觉回精力（清晨退房精力回 100），不填三条——旅店不管白天精力，只管夜晚住宿
        String bldName = getBuildingDisplayName(buildingId, bldType);
        ServerLevel level = serverLevel();
        if (level != null) {
            TouristSimulation.addVisitMemory(tourist, bldType, bldName, "service",
                    level.getGameTime(), 0, 0, 0, 0, "入住");
        }

        tourist.setCommuteTarget(null);
        tourist.setTargetBuildingId(null);
        tourist.setTargetBuildingCategory(null);
        indoorPhase = false;
        exitingPhase = false;
        clearSpotState();
        syncDebugData();
        showActionBar("✨ " + tourist.getTouristName() + " 入住了旅馆 " + (bldType != null ? bldType : "?") + "!");

        // Emit HOTEL_CHECKIN narrative
        long gameTime = tourist.level().getGameTime();
        NarrativeEvent checkinEvent = NarrativeGenerator.generateHotelCheckin(
                tourist.getTouristName(), bldType != null ? bldType : "unknown", bldName, gameTime);
        emitNarrativeEvent(checkinEvent);
        return true;
    }

    private void finishBuildingStop() {
        clearSpotState();
        tourist.setCommuteTarget(null);
        tourist.setTargetBuildingId(null);
        tourist.setTargetBuildingCategory(null);
        idleTicks = 0;
        indoorPhase = false;
        exitingPhase = false;
        entryPoint = null;
        interactPoint = null;
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

        // Teleport to safe ground near the entry point (outside the bbox, road-preferred).
        BlockPos safe = entryPoint != null ? entryPoint : tourist.getCommuteTarget();
        if (safe == null) safe = tourist.blockPosition();
        BlockPos tp = TouristTeleport.findSafeSpotNearEntry(serverLevel(), safe, tourist.getColonyId());
        if (tp != null) {
            tourist.setPos(tp.getX() + 0.5, tp.getY(), tp.getZ() + 0.5);
        }

        // 放弃也算「逛过」：本次停留不再尝试该建筑（冷却已删除，靠 visitedBuildings 防重选卡死）。
        if (failed != null) {
            tourist.addVisitedBuilding(failed);
        }

        clearSpotState();
        tourist.setCommuteTarget(null);
        tourist.setTargetBuildingId(null);
        tourist.setTargetBuildingCategory(null);
        indoorPhase = false;
        exitingPhase = false;
        entryPoint = null;
        interactPoint = null;
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
                noMoveTicks = 0;
                totalNavTicks = 0;
                BlockPos tp = TouristTeleport.findSafeSpot(serverLevel(), pos, tourist.getColonyId(), null);
                if (tp != null) {
                    Log.info(TAG, "[Tourist] {} POI nav hard fallback. Teleporting to {}", tourist.getTouristName(), tp.toShortString());
                    tourist.setPos(tp.getX() + 0.5, tp.getY(), tp.getZ() + 0.5);
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
                BlockPos tp = TouristTeleport.findSafeSpot(serverLevel(), pos, tourist.getColonyId(), null);
                nav.stop();
                noMoveTicks = 0;
                lastNodeIndex = -1;
                lastPos = null;
                if (tp != null) {
                    Log.info(TAG, "[Tourist] {} wander stuck, teleporting to {}", tourist.getTouristName(), tp.toShortString());
                    tourist.setPos(tp.getX() + 0.5, tp.getY(), tp.getZ() + 0.5);
                }
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

    /** Quick check whether any valid building targets exist（复用共享 Find-Best-Action 目标选择）。 */
    private boolean hasBuildingsAvailable() {
        ServerLevel level = serverLevel();
        if (level == null) return false;
        return TouristSimulation.selectNextTarget(level, tourist, true) != null;
    }

    private void switchMode(MoveMode next) {
        if (currentMode != next) {
        }
        currentMode = next;
        clearSpotState();
        waypoints = null;
        wpIndex = 0;
        indoorPhase = false;
        exitingPhase = false;
        entryPoint = null;
        interactPoint = null;
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
    private void sendBubble(int iconKind, @Nullable String iconId, int count) {
        ServerLevel level = getServerLevel();
        if (level == null) return;
        TouristBubblePacket packet =
                new TouristBubblePacket(tourist.getId(), iconKind, iconId, count);
        for (ServerPlayer p : level.getEntitiesOfClass(
                ServerPlayer.class, tourist.getBoundingBox().inflate(32))) {
            PacketDistributor.sendToPlayer(p, packet);
        }
    }

    private void planNextBuilding() {
        ServerLevel level = serverLevel();
        if (level == null) return;

        BuildingState chosen = TouristSimulation.selectNextTarget(level, tourist, true);
        if (chosen == null) {
            Log.info(TAG, "[Tourist] {} | NO BUILDING | colony={} | night={} | visited={} | energy={} | bars={}/{}/{} (need {}/{}/{})",
                    tourist.getTouristName(), tourist.getColonyId(),
                    (level.getDayTime() % 24000) >= 13000,
                    tourist.getVisitedBuildings().size(), tourist.getEnergy(),
                    tourist.getComfortSat(), tourist.getMagicSat(), tourist.getWonderSat(),
                    tourist.getComfortNeed(), tourist.getMagicNeed(), tourist.getWonderNeed());
            return;
        }

        BuildingApi api = getBuildingApi();
        // Resolve entry point (macro nav destination) and spot point (micro nav destination)。
        // interactPoint 暂取第一个 spot 的世界坐标；实际 spot 下标在 switchToIndoorNav 认领后确定。
        entryPoint = api != null ? api.getEntryPoint(chosen.getBuildingId()) : null;
        if (entryPoint == null) entryPoint = chosen.getAnchor();
        interactPoint = TouristSimulation.spotWorldPos(level, chosen.getBuildingId(), 0);
        if (interactPoint == null) interactPoint = chosen.getAnchor();
        indoorPhase = false;
        exitingPhase = false;
        syncDebugData();

        tourist.setTargetBuildingId(chosen.getBuildingId());
        tourist.setTargetBuildingCategory(chosen.getCategory());
        // Macro navigation starts toward the entry point
        tourist.setCommuteTarget(entryPoint);
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
                tourist.level().getGameTime(), result.comfortDelta(), result.magicDelta(), result.wonderDelta(),
                result.energyDelta(), result.whatHappened());

        NarrativeEvent shopEvent = NarrativeGenerator.generateVisit(memory);
        emitNarrativeEvent(shopEvent);

        var purchase = result.purchase();
        sendBubble(purchase != null ? TransientBubbleStore.ICON_ITEM : TransientBubbleStore.ICON_NONE,
                purchase != null ? purchase.itemId() : null,
                purchase != null ? purchase.count() : 0);

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
                tourist.level().getGameTime(), result.comfortDelta(), result.magicDelta(), result.wonderDelta(),
                result.energyDelta(), result.whatHappened());

        NarrativeEvent serviceEvent = NarrativeGenerator.generateVisit(memory);
        emitNarrativeEvent(serviceEvent);

        var config = TouristSimulation.getConfig(level, buildingId);
        if (config != null && config.service() != null && !config.service().elementOutput().isEmpty()) {
            var entries = List.copyOf(config.service().elementOutput().entrySet());
            var pick = entries.get(tourist.level().random.nextInt(entries.size()));
            sendBubble(TransientBubbleStore.ICON_ELEMENT, pick.getKey(), pick.getValue());
        } else {
            sendBubble(TransientBubbleStore.ICON_NONE, null, 0);
        }

        sparkleSatisfaction();
    }

    /** Relax 建筑：歇脚回精力 + 填条。 */
    private void interactWithRelax(UUID buildingId) {
        ServerLevel level = getServerLevel();
        if (level == null) return;
        UUID colonyId = tourist.getColonyId();
        if (colonyId == null) return;

        var result = TouristSimulation.performRelaxInteraction(level, tourist, buildingId, colonyId);
        if (result == null) return;

        String bldType = TouristSimulation.getBuildingTypeId(level, buildingId);
        String bldName = getBuildingDisplayName(buildingId, bldType);
        VisitMemory memory = TouristSimulation.addVisitMemory(tourist, bldType, bldName, "relax",
                tourist.level().getGameTime(), result.comfortDelta(), result.magicDelta(), result.wonderDelta(),
                result.energyDelta(), result.whatHappened());

        NarrativeEvent relaxEvent = NarrativeGenerator.generateVisit(memory);
        emitNarrativeEvent(relaxEvent);

        sendBubble(TransientBubbleStore.ICON_NONE, null, 0);
        sparkleSatisfaction();
    }

    /** ATM 建筑：取现补钱包 + 填条。 */
    private void interactWithAtm(UUID buildingId) {
        ServerLevel level = getServerLevel();
        if (level == null) return;
        UUID colonyId = tourist.getColonyId();
        if (colonyId == null) return;

        var result = TouristSimulation.performAtmInteraction(level, tourist, buildingId, colonyId);
        if (result == null) return;

        String bldType = TouristSimulation.getBuildingTypeId(level, buildingId);
        String bldName = getBuildingDisplayName(buildingId, bldType);
        VisitMemory memory = TouristSimulation.addVisitMemory(tourist, bldType, bldName, "atm",
                tourist.level().getGameTime(), result.comfortDelta(), result.magicDelta(), result.wonderDelta(),
                result.energyDelta(), result.whatHappened());

        NarrativeEvent atmEvent = NarrativeGenerator.generateVisit(memory);
        emitNarrativeEvent(atmEvent);

        sendBubble(TransientBubbleStore.ICON_NONE, null, 0);
        sparkleSatisfaction();
    }

    /** 满意度提升：游客位置撒金色星光（四类交互共用）。粒子纯装饰，缺失静默跳过。 */
    private void sparkleSatisfaction() {
        ServerLevel level = getServerLevel();
        if (level == null) return;
        ParticleService.burstColored(level, tourist.position().add(0, 1.2, 0),
                1.0f, 0.85f, 0.30f, 10, 0.12f, 25, false);
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

        BlockPos tp = TouristTeleport.findSafeSpot(serverLevel(), pos, tourist.getColonyId(), tourist.getTargetBuildingId());
        tourist.getNavigation().stop();
        rescueLastPos = null;
        roofStuckTicks = 0;
        if (tp == null) return false;
        Log.info(TAG, "[Tourist] {} stuck on floating surface, rescuing to {}", tourist.getTouristName(), tp.toShortString());
        tourist.setPos(tp.getX() + 0.5, tp.getY(), tp.getZ() + 0.5);
        noMoveTicks = 0;
        totalNavTicks = 0;
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

    /** The tourist's own server level (goals tick server-side), falling back to the overworld. */
    @Nullable
    private ServerLevel serverLevel() {
        if (tourist.level() instanceof ServerLevel sl) return sl;
        return getServerLevel();
    }

    private static void emitNarrativeEvent(NarrativeEvent ne) {
        var world = WandscapeEngine.getWorld();
        if (world != null && world.eventBus != null) {
            world.eventBus.emit(new NarrativeEventTriggered(ne));
        }
    }
}
