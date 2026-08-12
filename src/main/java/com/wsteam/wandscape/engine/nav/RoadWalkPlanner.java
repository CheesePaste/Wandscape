package com.wsteam.wandscape.engine.nav;

import com.wsteam.wandscape.road.core.SplineLeg;
import com.wsteam.wandscape.road.core.SplineVec3;
import com.wsteam.wandscape.road.core.TransportRoute;
import com.wsteam.wandscape.road.engine.RoadRoutingHelper;
import com.wsteam.wandscape.shared.api.RoadApi;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Plans a walkable coarse route from the colony road network.
 *
 * <p>The road graph is the "highway" layer of hierarchical pathfinding:
 * long distances are planned on the road graph (Dijkstra), then sampled into
 * short waypoints that vanilla A* can reliably bridge. Each leg is sampled
 * every {@value #SAMPLE_INTERVAL} blocks so every local segment stays well
 * inside the vanilla pathfinder node budget and can route around buildings.
 */
public final class RoadWalkPlanner {

    private static final double SAMPLE_INTERVAL = 24.0;

    private RoadWalkPlanner() {}

    /**
     * Plan road-assisted walk waypoints from {@code from} to {@code to}.
     *
     * <p>Returns the full chain from start to target sampled every
     * {@value #SAMPLE_INTERVAL} blocks. Empty list = no roads / no route /
     * NPC-incompatible — the caller falls back to direct vanilla navigation.
     */
    public static List<BlockPos> plan(@Nullable RoadApi roadApi, Level level,
                                      BlockPos from, BlockPos to) {
        if (roadApi == null) return List.of();
        TransportRoute route = RoadRoutingHelper.planNpcWithRoads(
                roadApi, level, null, from, to);
        if (route.isEmpty()) return List.of();
        return waypointsFromRoute(route);
    }

    /** Convenience: resolve the road API via {@link WandscapeApis} and plan. */
    public static List<BlockPos> plan(Level level, BlockPos from, BlockPos to) {
        RoadApi roadApi;
        try {
            roadApi = WandscapeApis.getRoadApi();
        } catch (Exception e) {
            return List.of();
        }
        return plan(roadApi, level, from, to);
    }

    private static List<BlockPos> waypointsFromRoute(TransportRoute route) {
        List<BlockPos> wps = new ArrayList<>();
        for (SplineLeg leg : route.legs()) {
            double len = leg.getApproxLength();
            int steps = Math.max(1, (int) Math.ceil(len / SAMPLE_INTERVAL));
            double u0 = leg.uStart();
            double u1 = leg.uEnd();
            for (int i = 0; i <= steps; i++) {
                double u = u0 + (u1 - u0) * i / steps;
                SplineVec3 p = leg.spline().evaluate(u).position();
                wps.add(new BlockPos((int) p.x(), (int) p.y(), (int) p.z()));
            }
        }
        return wps;
    }
}
