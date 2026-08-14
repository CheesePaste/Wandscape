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
import com.wsteam.wandscape.engine.service.ParticleService;
import com.wsteam.wandscape.road.engine.WandscapeTags;
import com.wsteam.wandscape.shared.data.Activity;
import com.wsteam.wandscape.shared.data.NarrativeEvent;
import com.wsteam.wandscape.shared.data.VisitMemory;
import com.wsteam.wandscape.shared.api.BuildingApi;
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
import net.minecraft.resources.ResourceLocation;
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
    /** Current POI destination (EXPLORING_POI); null = none. */
    @Nullable
    private BlockPos navTarget;
    private int stuckTicks;

    /** 离路行走减速系数（脚下是路面方块 → 全速）。 */
    private static final double OFF_ROAD_SPEED_FACTOR = 0.8;

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
    /** 本次闲逛会话起点（贴路走硬上限基准）。 */
    @Nullable
    private BlockPos wanderOrigin;
    /** 道路候选缓存（key = 锚点+半径哈希，过期重扫）。 */
    private int roadCacheKey;
    private long roadCacheTick = Long.MIN_VALUE;
    private List<BlockPos> roadCache = List.of();

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
    private static final int WANDER_RECHOOSE_TICKS = 80;
    /** Min game ticks between re-issuing navigation when the navigator reports done
     *  (path finished early or unreachable). Prevents a per-tick synchronous A* hot loop:
     *  with many tourists, re-running vanilla pathfinding every tick for every stuck tourist
     *  was the #1 CPU cost (spark: PathNavigation.createPath ≈ 46%). */
    private static final int REPATH_COOLDOWN_TICKS = 20;
    /** Game tick of the last navigation re-issue; repaths are throttled against this. */
    private int lastRepathTick = Integer.MIN_VALUE;
    /** Default wander radius (blocks) around the (drifting) anchor. */
    private static final int WANDER_RADIUS = 12;
    /** 闲逛硬上限：离闲逛起点超过该距离强制折返（格）。 */
    private static final int WANDER_MAX_ORIGIN_DIST = 32;
    /** 道路方块垂直扫描范围（锚点 Y 上下各几格）。 */
    private static final int ROAD_SCAN_VERTICAL = 3;
    /** 道路候选缓存有效期（tick），避免反复全扫。 */
    private static final long ROAD_CACHE_TICKS = 100;

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
    /** 排在哪一个 spot 的队（-1 = 未排队）；队伍沿该 spot 朝向排开。 */
    private int queueSpotIndex = -1;
    /** 排队到位判定：与站位距离平方 ≤ 该值即视为站定（≈1.4 格内）。 */
    private static final double QUEUE_ARRIVE_DIST_SQ = 2.0;
    /** 上次导航的排队站位（目标没变就不重算 A*，避免每 tick 抖动）。 */
    @Nullable
    private BlockPos queueNavTarget;

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

    /**
     * 交互时长/排队容忍等 goal 内计时器以真实 tick 计：vanilla 默认 goal 每 2 个游戏 tick
     * 才 tick 一次（Mob.serverAiStep 按 (tickCount+id)%2 交替），必须覆盖此方法让 tick 每帧跑，
     * 否则 interaction_duration_ticks=2400 实测会变成 4800 tick（半速倒计时）。
     */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    // ── Entry / exit ──

    @Override
    public void start() {
        // 恢复从存档加载的正在进行的建筑交互（如在长椅上休息/在商店浏览等）
        if (tourist.getOccupiedSpot() >= 0 && tourist.getCurrentActivity() != null
                && tourist.getActivityTicks() > 0 && tourist.getTargetBuildingId() != null) {
            currentMode = MoveMode.VISITING_BUILDING;
            indoorPhase = true;
            performingActivity = true;
            claimedSpot = tourist.getOccupiedSpot();
            ServerLevel sl = serverLevel();
            if (sl != null) {
                interactPoint = TouristSimulation.spotWorldPos(sl, tourist.getTargetBuildingId(), claimedSpot);
                faceSpot(sl, tourist.getTargetBuildingId(), claimedSpot);
            }
            if (tourist.getCurrentActivity() == Activity.EAT) {
                tourist.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, heldFoodStack());
            }
        } else if (tourist.getCommuteTarget() != null && tourist.getTargetBuildingId() != null) {
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
        navTarget = null;
    }

    @Override
    public void tick() {
        // 睡着（住店客在旅店床上）：不动，等清晨晨起（HotelStayHandler.wakeUp 后自然外出）
        if (tourist.isSleeping()) {
            tourist.getNavigation().stop();
            return;
        }

        long dayTime = tourist.level().getDayTime() % 24000;
        boolean isNight = dayTime >= Config.TOURIST_NIGHT_START.get();
        UUID hotelId = tourist.getCheckedInBuildingId();

        // ── 住店客（未满条）：夜晚/凌晨回自己旅店睡觉（空闲即回店；满条住店客夜晚等离场）──
        if ((isNight || dayTime < 1000) && hotelId != null && !tourist.isFullySatisfied()
                && !performingActivity && !queueing) {
            ReturnHomeResult r = returnToOwnHotel();
            if (r == ReturnHomeResult.STOP) {
                tourist.getNavigation().stop();
                return;
            }
            if (r == ReturnHomeResult.ROUTING) {
                return; // 刚设置回店导航，本 tick 不再派发（下一 tick 正常推进）
            }
            // HEADING / NONE → 落正常派发推进导航
        }

        // ── 傍晚路由：无旅店游客停止当前任务去旅店（防夜晚无旅店被清场）──
        if (dayTime >= Config.TOURIST_EVENING_ROUTING_START.get()
                && hotelId == null && !tourist.isFullySatisfied()) {
            if (eveningRouteToHotel()) {
                return; // 刚设置路由，本 tick 不再派发
            }
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
        if (performingActivity) {
            // Already performing activity (restored from saved data) — stay put
            return;
        }
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
                    : dayTime < Config.TOURIST_NIGHT_START.get() ? "afternoon" : "night";
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
            // 旅店入住：游客**进入建筑 bbox** 时触发（bbox+5 外扩已去掉，避免大旅店离门老远就入住）
            if (isHotelBuilding(buildingId) && isInsideBuilding(buildingId)) {
                if (tryHotelCheckIn(buildingId, getBuildingTypeId(buildingId))) {
                    return;
                }
                // 夜晚 + 未满条：意图入住（到达即入，spot time = 0）。旅店满员 → 不排队当 service 逛，
                // 直接放弃本次访问重新规划（去别的旅店/离场窗口兜底），避免排队拖到被清场。
                long dayTime = tourist.level().getDayTime() % 24000;
                if (dayTime >= Config.TOURIST_NIGHT_START.get() && !tourist.isFullySatisfied()) {
                    finishBuildingStop();
                    return;
                }
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
                tourist.resetFallDistance();
                tourist.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            }
            return;
        }

        // Check arrival at entry point (fallback if proximity check doesn't fire)
        double distSqr = pos.distSqr(target);
        int interactionRange = getInteractionRange();
        if (distSqr < interactionRange * interactionRange) {
            // 到达旅店 → 入住即时完成（不占 spot、不等 interaction_duration）
            if (buildingId != null && isHotelBuilding(buildingId)
                    && tryHotelCheckIn(buildingId, getBuildingTypeId(buildingId))) {
                return;
            }
            // Reached entry point — switch to indoor micro-nav
            switchToIndoorNav();
            return;
        }

        // Stuck recovery
        if (nav.isDone()) {
            if (++stuckTicks > 40) {
                stuckTicks = 0;
                moveToNext(touristSpeed, target);
            } else if (repathDue()) {
                moveToNext(touristSpeed, target);
            }
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

        // 已进旅店（进入建筑 bbox）：入住即时完成，不占 spot、不等 interaction_duration。
        // 白天/满条/满员（tryHotelCheckIn 失败）→ 按普通 service 建筑继续。
        if (isHotelBuilding(buildingId) && isInsideBuilding(buildingId)) {
            long dayTime = tourist.level().getDayTime() % 24000;
            if (dayTime >= Config.TOURIST_NIGHT_START.get() && !tourist.isFullySatisfied()) {
                if (tryHotelCheckIn(buildingId, getBuildingTypeId(buildingId))) {
                    return;
                }
                // 夜晚意图入住但旅店满员 → 不当 service 逛/排队，放弃重新规划（避免排队拖到被清场）
                finishBuildingStop();
                return;
            }
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
                tourist.resetFallDistance();
                tourist.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
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
                if (allowRepath()) {
                    nav.moveTo(exitTarget.getX() + 0.5, exitTarget.getY(), exitTarget.getZ() + 0.5, touristSpeed);
                }
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
            if (allowRepath()) {
                nav.moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, touristSpeed);
            }
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
            spot = TouristSimulation.claimSpot(level, buildingId, tourist.getUUID());
            if (spot < 0) {
                // spot 全满 → 排队
                startQueueing();
                return;
            }
            claimedSpot = spot;
        }
        // 精确落到 spot 中心再开始交互：游客移动有误差，直接 setPos 钉死，保证整队与 spot 对齐
        BlockPos sp = TouristSimulation.spotWorldPos(level, buildingId, spot);
        if (sp != null) {
            double floorY = TouristSimulation.getFloorSurfaceY(level, sp);
            tourist.setPos(sp.getX() + 0.5, floorY, sp.getZ() + 0.5);
            tourist.resetFallDistance();
            tourist.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            tourist.getNavigation().stop();
        }
        Activity action = TouristSimulation.interactSpotAction(level, buildingId, spot);
        int duration = Math.max(1, TouristSimulation.interactionDuration(level, buildingId));
        tourist.setCurrentActivity(action);
        tourist.setOccupiedSpot(spot);
        tourist.setActivityTicks(duration);
        performingActivity = true;
        queueing = false;
        faceSpot(level, buildingId, spot);
        // EAT：手里拿上食物（进食粒子从嘴边冒）
        if (action == Activity.EAT) {
            tourist.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, heldFoodStack());
        }
    }

    /** 游客用餐手持的食物栈（非法 id 回退面包）。 */
    private net.minecraft.world.item.ItemStack heldFoodStack() {
        ResourceLocation rl = ResourceLocation.tryParse(tourist.getHeldFoodItem());
        var item = rl != null ? net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl) : null;
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            item = net.minecraft.world.item.Items.BREAD;
        }
        return new net.minecraft.world.item.ItemStack(item);
    }

    /** 活动期间面向 spot 朝向（游客做动作时面朝该方向；锁定朝向防转身）。 */
    private void faceSpot(ServerLevel level, UUID buildingId, int spot) {
        float yaw = TouristSimulation.spotFacing(level, buildingId, spot).toYRot();
        tourist.setFrozenYaw(yaw);
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
        tourist.setFrozenYaw(null);
        tourist.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
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
            stampRepath();
            tourist.getNavigation().moveTo(exitTarget.getX() + 0.5, exitTarget.getY(), exitTarget.getZ() + 0.5, touristSpeed);
        } else {
            finishBuildingStop();
        }
    }

    /** 排队等待：轮询本队 spot 空位，超 TOURIST_QUEUE_WAIT_TOLERANCE_TICKS 放弃去别处。 */
    private void tickQueue() {
        if (++queueTicks > Config.TOURIST_QUEUE_WAIT_TOLERANCE_TICKS.get()) {
            abandonBuildingVisit();
            return;
        }
        ServerLevel level = serverLevel();
        UUID buildingId = tourist.getTargetBuildingId();
        if (level == null || buildingId == null || queueSpotIndex < 0) {
            abandonBuildingVisit();
            return;
        }
        // 严格 FIFO：只有本队队首可认领该 spot 空位（队首离队后下一个自然成为队首）
        if (TouristSpotManager.getActive().queuePosition(buildingId, queueSpotIndex, tourist.getUUID()) == 0) {
            int spot = TouristSimulation.claimSpotAt(level, buildingId, queueSpotIndex, tourist.getUUID());
            if (spot >= 0) {
                leaveQueue();
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
                tourist.setFrozenYaw(null);
                // 队首就在 spot 背后 1 格，直接精确落到 spot 上开始交互（消除移动误差）
                double floorY = TouristSimulation.getFloorSurfaceY(level, target);
                tourist.setPos(target.getX() + 0.5, floorY, target.getZ() + 0.5);
                tourist.resetFallDistance();
                tourist.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                stampRepath();
                tourist.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, touristSpeed);
                return;
            }
        }
        navigateToQueueSlot();
    }

    /** 进入排队状态（spot 全满）：排到队最短的 spot 后，沿该 spot 朝向站成一列。 */
    private void startQueueing() {
        queueing = true;
        queueTicks = 0;
        tourist.setCurrentActivity(Activity.QUEUE);
        tourist.setFrozenYaw(null);
        ServerLevel level = serverLevel();
        UUID buildingId = tourist.getTargetBuildingId();
        if (level != null && buildingId != null) {
            int total = TouristSimulation.interactSpotCount(level, buildingId);
            if (total > 0) {
                // 均匀分布：排到当前队最短（并列取最小下标）的 spot 后
                int spot = TouristSpotManager.getActive().shortestQueueSpot(buildingId, total);
                TouristSpotManager.getActive().joinQueue(buildingId, spot, tourist.getUUID());
                queueSpotIndex = spot;
            }
        }
        navigateToQueueSlot();
    }

    /** 导航到本队当前队序对应的站位（沿 spot 朝向向后排开），到位后朝向与 spot 一致。 */
    private void navigateToQueueSlot() {
        UUID buildingId = tourist.getTargetBuildingId();
        ServerLevel level = serverLevel();
        if (buildingId == null || level == null || queueSpotIndex < 0) {
            tourist.getNavigation().stop();
            return;
        }
        int slot = TouristSpotManager.getActive().queuePosition(buildingId, queueSpotIndex, tourist.getUUID());
        if (slot < 0) {
            tourist.getNavigation().stop();
            return;
        }
        BlockPos raw = TouristSimulation.queueSlotPos(level, buildingId, queueSpotIndex, slot);
        BlockPos ground = raw != null ? findGround(raw.getX(), raw.getY(), raw.getZ()) : null;
        BlockPos target = ground != null ? ground : raw;
        if (target == null) {
            tourist.getNavigation().stop();
            return;
        }
        // 已到位且目标没变：首次到达时精确对齐站位中心与地面高度，之后保持静止防高频抖动，
        // 朝向与 spot 的 facing 一致（和交互游客同向）
        boolean sameTarget = target.equals(queueNavTarget);
        boolean arrived = tourist.blockPosition().distSqr(target) <= QUEUE_ARRIVE_DIST_SQ;
        if (sameTarget && arrived) {
            tourist.getNavigation().stop();
            if (tourist.getFrozenYaw() == null) {
                double floorY = TouristSimulation.getFloorSurfaceY(level, target);
                tourist.setPos(target.getX() + 0.5, floorY, target.getZ() + 0.5);
                tourist.resetFallDistance();
                tourist.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                float yaw = TouristSimulation.spotFacing(level, buildingId, queueSpotIndex).toYRot();
                tourist.setFrozenYaw(yaw);
            }
            return;
        }
        // 需移动：队序前移（目标变化）换目标，或导航已结束（被撞开/寻路失败）重新引导
        tourist.setFrozenYaw(null);
        if (!sameTarget || (tourist.getNavigation().isDone() && allowRepath())) {
            queueNavTarget = target;
            tourist.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, touristSpeed);
        }
    }

    /** 离开建筑所有队列（幂等），清排队状态。 */
    private void leaveQueue() {
        UUID buildingId = tourist.getTargetBuildingId();
        if (buildingId != null) {
            TouristSpotManager.getActive().leaveAllQueues(buildingId, tourist.getUUID());
        }
        queueSpotIndex = -1;
        queueNavTarget = null;
    }

    /** 清空 spot 占用与活动/排队状态（所有清理路径共用）。 */
    private void clearSpotState() {
        leaveQueue();
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
        tourist.setFrozenYaw(null);
        tourist.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
    }

    /** Switch from outdoor macro-nav to indoor micro-nav. */
    private void switchToIndoorNav() {
        indoorPhase = true;
        exitingPhase = false;
        syncDebugData();
        navTarget = null;
        stuckTicks = 0;
        lastPos = null;
        noMoveTicks = 0;
        totalNavTicks = 0;

        // 到达建筑：认领一个空 spot（spot 数 = 同时交互人数上限），导航到该 spot 世界坐标。
        // spot 全满 → 排队（均匀分散到队最短的 spot 后，沿该 spot 朝向站一列）。
        ServerLevel level = serverLevel();
        UUID buildingId = tourist.getTargetBuildingId();
        int spot = -1;
        if (level != null && buildingId != null) {
            spot = TouristSimulation.claimSpot(level, buildingId, tourist.getUUID());
        }
        if (spot >= 0) {
            claimedSpot = spot;
            BlockPos target = TouristSimulation.spotWorldPos(level, buildingId, spot);
            interactPoint = target;
            if (target != null) {
                stampRepath();
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
        navTarget = null;
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
        boolean isNight = dayTime >= Config.TOURIST_NIGHT_START.get();
        // 夜晚 + 未满条 → 入住/回店睡（满条游客夜晚等离场，不入旅店）
        if (!(isNight && !tourist.isFullySatisfied())) return false;

        HotelStayHandler hotel = HotelStayHandler.getActive();
        UUID colonyId = tourist.getColonyId();
        if (hotel == null || colonyId == null) return false;

        boolean alreadyResident = buildingId.equals(tourist.getCheckedInBuildingId());
        if (!alreadyResident) {
            // 首次入住：登记 + 填一次满意值（住宿贡献三条）+ 记行程 + 叙事
            if (!hotel.checkIn(tourist, buildingId, colonyId)) return false;
            tourist.addVisitedBuilding(buildingId);
            String bldName = getBuildingDisplayName(buildingId, bldType);
            ServerLevel level = serverLevel();
            if (level != null) {
                int[] delta = TouristSimulation.fillBars(level, tourist, buildingId);
                TouristSimulation.addVisitMemory(tourist, bldType, bldName, "service",
                        level.getGameTime(), delta[0], delta[1], delta[2], 0, "入住");
            }
            showActionBar("✨ " + tourist.getTouristName() + " 入住了旅馆 " + (bldType != null ? bldType : "?") + "!");
            NarrativeEvent checkinEvent = NarrativeGenerator.generateHotelCheckin(
                    tourist.getTouristName(), bldType != null ? bldType : "unknown", bldName,
                    tourist.level().getGameTime());
            emitNarrativeEvent(checkinEvent);
        }

        // 住店客夜晚回店：直接强制躺床（不复填满意值/不重复叙事）
        hotel.settleIntoBed(tourist, serverLevel(), buildingId);

        // 先清 spot 再清目标：clearSpotState 靠 getTargetBuildingId() 定位释放位，
        // 若先置 null，占用的 spot（如白天在自家旅店做的服务位）会用 null 建筑 key 释放 → NPE。
        clearSpotState();
        tourist.setCommuteTarget(null);
        tourist.setTargetBuildingId(null);
        tourist.setTargetBuildingCategory(null);
        indoorPhase = false;
        exitingPhase = false;
        syncDebugData();
        return true;
    }

    // ── 夜晚回店 / 傍晚路由（住店客机制）──

    private enum ReturnHomeResult {
        /** 不需要处理（无旅店/旅店失效已解除登记）→ 正常派发。 */
        NONE,
        /** 已在回店路上 → 正常派发推进导航。 */
        HEADING,
        /** 刚设置回店导航 → 本 tick 不再派发。 */
        ROUTING,
        /** 已上床/睡着 → 停住。 */
        STOP
    }

    /**
     * 住店客夜晚回自己旅店：已睡着 → 停住；在旅店旁 → 强制躺床；在路上 → 继续走；
     * 否则开始回店（过远直接传送）。旅店被拆/停用 → 解除登记，按无旅店游客处理。
     */
    private ReturnHomeResult returnToOwnHotel() {
        UUID hotel = tourist.getCheckedInBuildingId();
        if (hotel == null) return ReturnHomeResult.NONE;
        if (tourist.isSleeping()) {
            tourist.getNavigation().stop();
            return ReturnHomeResult.STOP;
        }

        BuildingApi api = getBuildingApi();
        var data = api != null ? api.getBuilding(hotel) : null;
        if (data == null || data.isShutdown() || !data.isStructureIntact() || !isHotelBuilding(hotel)) {
            // 旅店已失效 → 解除登记，按无旅店游客处理（傍晚路由去别的旅店 / 离场窗口兜底）
            clearSpotState();
            tourist.setCommuteTarget(null);
            tourist.setTargetBuildingId(null);
            tourist.setTargetBuildingCategory(null);
            HotelStayHandler h = HotelStayHandler.getActive();
            if (h != null) h.checkOut(tourist, serverLevel());
            Log.info(TAG, "[Tourist] {} hotel invalid — released from hotel {}", tourist.getTouristName(), shortId(hotel));
            return ReturnHomeResult.NONE;
        }

        // 已在自己旅店内（进入建筑 bbox，无 +5 外扩）→ 回店睡（alreadyResident 路径，直接强制躺床）。
        // 无床卡原地（wakeUpPos == 当前位置且未睡着）→ 站定等晨起，不重复 settle；
        // 重载后站在床上（wakeUpPos != 当前位置）→ 仍重新躺床。
        if (isInsideBuilding(hotel)) {
            if (tourist.getWakeUpPos() != null && !tourist.isSleeping()
                    && tourist.getWakeUpPos().equals(tourist.blockPosition())) {
                tourist.getNavigation().stop();
                return ReturnHomeResult.STOP;
            }
            tryHotelCheckIn(hotel, getBuildingTypeId(hotel));
            tourist.getNavigation().stop();
            return ReturnHomeResult.STOP;
        }
        // 已在回店路上 → 继续走
        if (hotel.equals(tourist.getTargetBuildingId())) {
            return ReturnHomeResult.HEADING;
        }
        // 旅店区块未加载 → 现在无法寻路回店；保持住店客身份（登记在案，不会被清），等区块加载
        ServerLevel level = serverLevel();
        if (level == null || !level.isLoaded(data.getPosition())) {
            return ReturnHomeResult.NONE;
        }

        BlockPos target = api.getTouristInteractionTarget(hotel);
        if (target == null) target = data.getPosition();
        routeToHotelBuilding(hotel, target, true);
        return ReturnHomeResult.ROUTING;
    }

    /**
     * 傍晚路由（无旅店游客）：已在去旅店路上 → 交正常派发；否则找最近可用旅店并停止当前任务去旅店
     * （过远直接传送）。无旅店可用 → 返回 false，正常行为（18000+ 离场窗口兜底）。
     *
     * @return true = 刚设置路由（本 tick 不再派发）
     */
    private boolean eveningRouteToHotel() {
        if (targetingHotel()) return false;
        BuildingState hotel = TouristSimulation.findHotelTarget(serverLevel(), tourist, true);
        if (hotel == null) return false;
        BuildingApi api = getBuildingApi();
        BlockPos target = api != null ? api.getTouristInteractionTarget(hotel.getBuildingId()) : null;
        if (target == null) target = hotel.getAnchor();
        routeToHotelBuilding(hotel.getBuildingId(), target, true);
        return true;
    }

    /**
     * 停止当前任务并设置去旅店的导航（清 spot/队列，过远直接传送到旅店入口附近）。
     * @return 设置成功
     */
    private boolean routeToHotelBuilding(UUID hotelId, BlockPos target, boolean teleportIfFar) {
        if (target == null) return false;

        // 停止当前任务（释放 spot/队列）
        clearSpotState();
        tourist.setCommuteTarget(null);
        tourist.setTargetBuildingId(null);
        tourist.setTargetBuildingCategory(null);
        indoorPhase = false;
        exitingPhase = false;
        entryPoint = null;
        interactPoint = null;
        syncDebugData();

        tourist.setTargetBuildingId(hotelId);
        tourist.setTargetBuildingCategory("service");
        tourist.setCommuteTarget(target);

        // 过远 → 直接传送（寻路到远/未加载区块开销大）
        if (teleportIfFar) {
            int max = Config.TOURIST_HOTEL_TELEPORT_DISTANCE.get();
            if (tourist.blockPosition().distSqr(target) > (long) max * max) {
                BlockPos tp = TouristTeleport.findSafeSpotNearEntry(serverLevel(), target, tourist.getColonyId());
                if (tp == null) {
                    tp = TouristTeleport.findSafeSpot(serverLevel(), target, tourist.getColonyId(), hotelId);
                }
                if (tp != null) {
                    Log.info(TAG, "[Tourist] {} teleporting to hotel {} ({} blocks away)",
                            tourist.getTouristName(), shortId(hotelId),
                            (int) Math.sqrt(tourist.blockPosition().distSqr(target)));
                    tourist.setPos(tp.getX() + 0.5, tp.getY(), tp.getZ() + 0.5);
                    tourist.resetFallDistance();
                    tourist.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                }
            }
        }

        switchMode(MoveMode.VISITING_BUILDING);
        dispatchStart();
        return true;
    }

    /** 当前目标是否是一座仍在营业的旅店（含回店/傍晚路由进行中）。 */
    private boolean targetingHotel() {
        UUID target = tourist.getTargetBuildingId();
        if (target == null) return false;
        if (!isHotelBuilding(target)) return false;
        BuildingApi api = getBuildingApi();
        var data = api != null ? api.getBuilding(target) : null;
        return data != null && !data.isShutdown() && data.isStructureIntact();
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
            tourist.resetFallDistance();
            tourist.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
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
        navTarget = null;
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
                    tourist.resetFallDistance();
                    tourist.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
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

        BlockPos wp = navTarget;
        if (wp == null) {
            // No destination — pick another POI, or wander fallback
            if (!pickNextPoiAndGo()) {
                switchMode(MoveMode.WANDERING);
                startWander();
            }
            return;
        }

        // Arrived at the POI
        if (pos.distSqr(wp) < 2.25) {
            nav.stop();
            navTarget = null;
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
            if (repathDue()) moveToNext(wanderSpeed, wp);
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
            // No POIs — fall back to the same road-based wander picker so the
            // tourist never roams open field.
            BlockPos anchor = tourist.getWanderAnchor();
            if (anchor != null) {
                rawTarget = pickWanderTarget(anchor, tourist.getWanderRadius());
            }
        }
        if (rawTarget == null) return false;

        BlockPos target = findGround(rawTarget.getX(), rawTarget.getY(), rawTarget.getZ());
        if (target == null) target = rawTarget;

        navTarget = target;
        logNav("POI", target);
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
        wanderOrigin = tourist.blockPosition();
    }

    private void tickWander() {
        BlockPos anchor = tourist.getWanderAnchor();
        // If no anchor, use current position (snapped onto the nearest road so the
        // wander area centers on roads).
        if (anchor == null) {
            anchor = tourist.blockPosition();
            BlockPos nearRoad = nearestRoadBlock(anchor, WANDER_RADIUS * 2);
            if (nearRoad != null) anchor = roadStandingSpot(nearRoad);
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
                    tourist.resetFallDistance();
                    tourist.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                }
                return;
            }
        } else {
            noMoveTicks = 0;
        }

        // ── Hard cap: never drift more than WANDER_MAX_ORIGIN_DIST from where this
        //    wander session started. Head back to a road near the origin. ──
        if (wanderOrigin != null && pos.distSqr(wanderOrigin) > WANDER_MAX_ORIGIN_DIST * WANDER_MAX_ORIGIN_DIST) {
            BlockPos back = nearestRoadBlock(wanderOrigin, WANDER_RADIUS);
            if (back == null) {
                back = findGround(wanderOrigin.getX(), wanderOrigin.getY(), wanderOrigin.getZ());
            } else {
                BlockPos stand = roadStandingSpot(back);
                if (stand != null) back = stand;
            }
            if (back != null) {
                tourist.setWanderAnchor(back);
                if (allowRepath()) {
                    nav.moveTo(back.getX() + 0.5, back.getY(), back.getZ() + 0.5, wanderSpeed);
                }
            }
            noMoveTicks = 0;
            lastNodeIndex = -1;
            return;
        }

        // ── Anchor drift: let the wander area follow the tourist instead of pinning it
        //    to one fixed point (fixes the "activity range is tiny" complaint). Only
        //    re-center when standing ON a road, so the roam area tracks the road
        //    network and never drifts across open field. ──
        if (manDist > radius / 2 && isOnRoad(pos)) {
            anchor = pos;
            tourist.setWanderAnchor(pos);
            manDist = 0;
        }

        // Too far from anchor → head back
        if (manDist > radius + 3) {
            if (nav.isDone() && allowRepath())
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
            if (g != null) {
                stampRepath();
                nav.moveTo(g.getX() + 0.5, g.getY(), g.getZ() + 0.5, wanderSpeed);
            }
            wanderCooldown = 60 + tourist.getRandom().nextInt(120);
        }
    }

    /**
     * Pick a road-based wander target near {@code anchor}: prefer a random
     * {@code custom_roads} block inside the radius, then the nearest road just
     * outside (to pull the tourist back onto roads), then a tiny bounded
     * micro-wander so the tourist never strays far when no roads exist nearby.
     */
    @Nullable
    private BlockPos pickWanderTarget(BlockPos anchor, int radius) {
        List<BlockPos> roads = cachedRoadBlocks(anchor, radius);
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos r : roads) {
            if (isInsideAnyBuilding(r)) continue;
            BlockPos stand = roadStandingSpot(r);
            if (stand != null) candidates.add(stand);
        }
        if (!candidates.isEmpty()) {
            return candidates.get(tourist.getRandom().nextInt(candidates.size()));
        }

        // No road in radius — pull toward the nearest road at double the radius.
        BlockPos near = nearestRoadBlock(anchor, radius * 2);
        if (near != null) {
            BlockPos stand = roadStandingSpot(near);
            if (stand != null) return stand;
        }

        // No road nearby at all: small bounded micro-wander, never far.
        for (int attempt = 0; attempt < 8; attempt++) {
            int half = Math.max(2, radius / 2);
            int tx = anchor.getX() + tourist.getRandom().nextInt(half * 2 + 1) - half;
            int tz = anchor.getZ() + tourist.getRandom().nextInt(half * 2 + 1) - half;
            BlockPos g = findGround(tx, anchor.getY(), tz);
            if (g != null && !isInsideAnyBuilding(g)) return g;
        }
        // Fallback: ground at the anchor (outside any building in the common case).
        return findGround(anchor.getX(), anchor.getY(), anchor.getZ());
    }

    /** Collect {@code custom_roads} tag blocks in a box around {@code center}. */
    private List<BlockPos> scanRoadBlocks(BlockPos center, int radius) {
        var lvl = tourist.level();
        List<BlockPos> out = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -ROAD_SCAN_VERTICAL; dy <= ROAD_SCAN_VERTICAL; dy++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    if (lvl.getBlockState(p).is(WandscapeTags.Blocks.CUSTOM_ROADS)) {
                        out.add(p);
                    }
                }
            }
        }
        return out;
    }

    /** {@link #scanRoadBlocks} with a short-lived cache so we don't re-scan every tick. */
    private List<BlockPos> cachedRoadBlocks(BlockPos center, int radius) {
        int key = ((center.getX() * 31 + center.getY()) * 31 + center.getZ()) * 31 + radius;
        long now = tourist.level().getGameTime();
        if (key == roadCacheKey && now - roadCacheTick < ROAD_CACHE_TICKS) {
            return roadCache;
        }
        roadCache = scanRoadBlocks(center, radius);
        roadCacheKey = key;
        roadCacheTick = now;
        return roadCache;
    }

    /** Nearest {@code custom_roads} tag block within {@code radius} of {@code center}. */
    @Nullable
    private BlockPos nearestRoadBlock(BlockPos center, int radius) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos r : scanRoadBlocks(center, radius)) {
            double d = center.distSqr(r);
            if (d < bestDist) {
                bestDist = d;
                best = r;
            }
        }
        return best;
    }

    /** The block a tourist stands in when standing ON the road block {@code road}. */
    @Nullable
    private BlockPos roadStandingSpot(BlockPos road) {
        BlockPos g = findGround(road.getX(), road.getY(), road.getZ());
        return g != null ? g : road.above();
    }

    /** True if the entity stands directly on a {@code custom_roads} block. */
    private boolean isOnRoad(BlockPos pos) {
        return tourist.level().getBlockState(pos.below()).is(WandscapeTags.Blocks.CUSTOM_ROADS);
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
        navTarget = null;
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
                    (level.getDayTime() % 24000) >= Config.TOURIST_NIGHT_START.get(),
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
        navTarget = null;
        stuckTicks = 0;
        idleTicks = 0;
        lastPos = null;
        noMoveTicks = 0;
        totalNavTicks = 0;
        moveToNext(speed, target);
    }

    /** True when enough ticks have passed since the last nav re-issue to allow another. */
    private boolean repathDue() {
        return tourist.timeBase() - lastRepathTick >= REPATH_COOLDOWN_TICKS;
    }

    /** Consumes the repath throttle slot if due; call before a retry {@code moveTo}. */
    private boolean allowRepath() {
        if (!repathDue()) return false;
        lastRepathTick = tourist.timeBase();
        return true;
    }

    /** Marks a navigation re-issue so subsequent retries are throttled for {@link #REPATH_COOLDOWN_TICKS}. */
    private void stampRepath() {
        lastRepathTick = tourist.timeBase();
    }

    private void moveToNext(double spd, BlockPos dest) {
        BlockPos ground = findGround(dest.getX(), dest.getY(), dest.getZ());
        if (ground != null) dest = ground;
        // 方块条件（无图）：脚下是路面 → 全速；否则减速，让铺路有实际价值。
        double effectiveSpeed = isOnRoad(tourist.blockPosition()) ? spd : spd * OFF_ROAD_SPEED_FACTOR;
        stampRepath();
        tourist.getNavigation().moveTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5, effectiveSpeed);
    }

    // ── Logging ──

    private void logNav(String label, BlockPos target) {
        String name = tourist.getTouristName();
        BlockPos from = tourist.blockPosition();
        Log.info(TAG, "[Tourist] {} {} → {} ({}→{})",
                name, label, target.toShortString(),
                from.toShortString(), target.toShortString());
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
        tourist.resetFallDistance();
        tourist.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
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
