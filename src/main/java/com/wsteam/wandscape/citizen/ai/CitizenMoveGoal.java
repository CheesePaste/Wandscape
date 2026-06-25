package com.wsteam.wandscape.citizen.ai;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.citizen.CitizenEntity;
import com.wsteam.wandscape.citizen.CitizenState;
import com.wsteam.wandscape.core.road.PathPoint;
import com.wsteam.wandscape.core.road.RoadNetwork;
import com.wsteam.wandscape.core.road.RoadRouter;
import com.wsteam.wandscape.core.road.RouteSegment;
import com.wsteam.wandscape.shared.api.RoadApi;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.*;

public class CitizenMoveGoal extends Goal {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final CitizenEntity citizen;
    private final double commuteSpeed;
    private final double wanderSpeed;

    // ── Waypoint chain ──
    private BlockPos[] waypoints;
    private int wpIndex;
    private int stuckTicks;
    private boolean usingRoad;

    // ── Leisure ──
    private int poiPauseTicks;

    // ── IDLE ──
    private int wanderCooldown;

    public CitizenMoveGoal(CitizenEntity citizen, double commuteSpeed, double wanderSpeed) {
        this.citizen = citizen;
        this.commuteSpeed = commuteSpeed;
        this.wanderSpeed = wanderSpeed;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override public boolean canUse() { return citizen.getCurrentState() != null; }
    @Override public boolean canContinueToUse() { return citizen.getCurrentState() != null; }

    @Override
    public void start() {
        switch (citizen.getCurrentState()) {
            case COMMUTING -> startCommute();
            case LEISURE -> startLeisure();
            default -> startWander();
        }
    }

    @Override public void stop() { citizen.getNavigation().stop(); waypoints = null; wpIndex = 0; }

    @Override
    public void tick() {
        switch (citizen.getCurrentState()) {
            case COMMUTING -> tickCommute();
            case LEISURE -> tickLeisure();
            default -> tickWander();
        }
    }

    // ── COMMUTING ──

    private void startCommute() {
        BlockPos target = citizen.getCommuteTarget();
        if (target == null) { citizen.setCommuteArrived(true); return; }
        waypoints = null; wpIndex = 0; stuckTicks = 0;
        citizen.setCommuteArrived(false);

        usingRoad = planRoute(target);
        logNav("COMMUTING", target);
        wpIndex = 1;                           // skip wp[0] (= start pos)
        moveToNext(commuteSpeed, target);
    }

    private void tickCommute() {
        BlockPos target = citizen.getCommuteTarget();
        if (target == null) { citizen.setCommuteArrived(true); return; }

        PathNavigation nav = citizen.getNavigation();
        BlockPos pos = citizen.blockPosition();
        BlockPos wp = currentTarget();

        // Arrived at current waypoint?
        if (wp != null && pos.distSqr(wp) < 2.25) {
            wpIndex++;
            if (waypoints != null && wpIndex < waypoints.length) {
                moveToNext(commuteSpeed, target);
                stuckTicks = 0; return;
            }
            onCommuteArrived(); return;
        }

        if (nav.isDone()) {
            if (++stuckTicks > 40) {
                stuckTicks = 0; usingRoad = planRoute(target); wpIndex = 1;
                logNav("COMMUTING*", target);
            }
            moveToNext(commuteSpeed, target);
        } else { stuckTicks = Math.max(0, stuckTicks - 1); }
    }

    private void onCommuteArrived() {
        citizen.getNavigation().stop();
        citizen.setCommuteArrived(true);
        waypoints = null; wpIndex = 0;
    }

    // ── LEISURE ──

    private void startLeisure() {
        waypoints = null; wpIndex = 0; stuckTicks = 0; poiPauseTicks = 0;
        pickNextPoiAndGo();
    }

    private void tickLeisure() {
        PathNavigation nav = citizen.getNavigation();
        BlockPos pos = citizen.blockPosition();

        if (poiPauseTicks > 0) { poiPauseTicks--; nav.stop(); return; }

        BlockPos wp = currentTarget();
        if (wp == null) { pickNextPoiAndGo(); return; }

        // ── Waypoint advancement ──
        if (pos.distSqr(wp) < 2.25) {
            if (waypoints != null && wpIndex < waypoints.length) {
                wpIndex++;
                if (wpIndex < waypoints.length) {
                    moveToNext(wanderSpeed, wp);
                    stuckTicks = 0; return;
                }
            }
            // End of waypoint chain → arrived at POI
            nav.stop(); waypoints = null; wpIndex = 0;
            poiPauseTicks = 100 + citizen.getRandom().nextInt(200);
            return;
        }

        // ── Stuck recovery ──
        if (nav.isDone()) {
            if (++stuckTicks > 60) {
                stuckTicks = 0; pickNextPoiAndGo(); return;
            }
            moveToNext(wanderSpeed, wp);
        } else { stuckTicks = Math.max(0, stuckTicks - 1); }
    }

    private void pickNextPoiAndGo() {
        List<BlockPos> pois = citizen.getPoiList();
        BlockPos rawTarget = null;

        if (!pois.isEmpty()) {
            BlockPos here = citizen.blockPosition();
            List<BlockPos> far = new ArrayList<>();
            for (BlockPos p : pois) if (p.distSqr(here) > 25) far.add(p);
            rawTarget = !far.isEmpty()
                    ? far.get(citizen.getRandom().nextInt(far.size()))
                    : pois.get(citizen.getRandom().nextInt(pois.size()));
        }
        if (rawTarget == null) {
            BlockPos anchor = citizen.getWanderAnchor();
            if (anchor != null) {
                int r = citizen.getWanderRadius();
                rawTarget = anchor.offset(
                        citizen.getRandom().nextInt(r * 2 + 1) - r, 0,
                        citizen.getRandom().nextInt(r * 2 + 1) - r);
            }
        }
        if (rawTarget == null) return;

        BlockPos target = findGround(rawTarget.getX(), rawTarget.getY(), rawTarget.getZ());
        if (target == null) target = rawTarget;

        usingRoad = planRoute(target);
        logNav("LEISURE", target);
        wpIndex = 1;                           // skip wp[0] (= start pos)
        moveToNext(wanderSpeed, target);
    }

    // ── IDLE ──

    private void startWander() { wanderCooldown = 0; }

    private void tickWander() {
        BlockPos anchor = citizen.getWanderAnchor();
        if (anchor == null) return;
        int radius = citizen.getWanderRadius();
        BlockPos pos = citizen.blockPosition();
        int manDist = Math.abs(pos.getX() - anchor.getX()) + Math.abs(pos.getZ() - anchor.getZ());
        PathNavigation nav = citizen.getNavigation();

        if (manDist > radius + 3) {
            if (nav.isDone())
                nav.moveTo(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5, wanderSpeed);
            return;
        }
        if (--wanderCooldown <= 0) {
            int tx = anchor.getX() + citizen.getRandom().nextInt(radius * 2 + 1) - radius;
            int tz = anchor.getZ() + citizen.getRandom().nextInt(radius * 2 + 1) - radius;
            BlockPos g = findGround(tx, anchor.getY(), tz);
            if (g != null)
                nav.moveTo(g.getX() + 0.5, g.getY(), g.getZ() + 0.5, wanderSpeed);
            wanderCooldown = 60 + citizen.getRandom().nextInt(120);
        }
    }

    // ── Road routing ──

    private boolean planRoute(BlockPos target) {
        RoadApi roadApi = getRoadApiSilently();
        if (roadApi == null) return false;
        RoadNetwork network = roadApi.getNetwork(null);
        if (network == null || network.isEmpty()) return false;

        BlockPos from = citizen.blockPosition();
        List<RouteSegment> segments = RoadRouter.plan(
                network,
                new PathPoint(from.getX(), from.getY(), from.getZ()),
                new PathPoint(target.getX(), target.getY(), target.getZ()));
        if (segments.isEmpty()) return false;

        List<BlockPos> wps = new ArrayList<>();
        RouteSegment first = segments.get(0);
        wps.add(new BlockPos((int) first.fromX(), (int) first.fromY(), (int) first.fromZ()));
        for (RouteSegment seg : segments)
            wps.add(new BlockPos((int) seg.toX(), (int) seg.toY(), (int) seg.toZ()));
        waypoints = wps.toArray(new BlockPos[0]);
        return true;
    }

    // ── Nav helpers ──

    /** Start navigating toward next waypoint (or fallback). Only called on transition. */
    private void moveToNext(double speed, BlockPos fallback) {
        BlockPos wp = currentTarget(fallback);
        BlockPos ground = findGround(wp.getX(), wp.getY(), wp.getZ());
        if (ground != null) wp = ground;
        citizen.getNavigation().moveTo(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5, speed);
    }

    /** The current or next waypoint, or the fallback. */
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
        String name = citizen.getCitizenName();
        BlockPos from = citizen.blockPosition();
        if (usingRoad && waypoints != null) {
            LOGGER.info("[Citizen] {} {} ROAD → {} ({} wps, {}→{})",
                    name, label, target.toShortString(), waypoints.length,
                    from.toShortString(), target.toShortString());
        } else {
            LOGGER.info("[Citizen] {} {} VANILLA → {} ({}→{})",
                    name, label, target.toShortString(),
                    from.toShortString(), target.toShortString());
        }
    }

    // ── Helpers ──

    @Nullable private static RoadApi getRoadApiSilently() {
        try { return WandscapeApis.getRoadApi(); }
        catch (IllegalStateException e) { return null; }
    }

    @Nullable
    private BlockPos findGround(int x, int baseY, int z) {
        var lvl = citizen.level();
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
}
