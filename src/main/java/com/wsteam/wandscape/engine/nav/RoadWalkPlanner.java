package com.wsteam.wandscape.engine.nav;

import com.wsteam.wandscape.road.algorithm.RoadRouter;
import com.wsteam.wandscape.road.core.PathPoint;
import com.wsteam.wandscape.road.core.RoadNetwork;
import com.wsteam.wandscape.road.core.SplineLeg;
import com.wsteam.wandscape.road.core.SplineVec3;
import com.wsteam.wandscape.road.core.TransportRoute;
import com.wsteam.wandscape.road.engine.RoadSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plans a road-assisted walking route for entities (like Tourists) using the colony road network.
 *
 * <p>Discretizes the high-level {@link TransportRoute} into short, intermediate waypoints
 * so the entity's pathfinder naturally follows roads and stays within local navigation budgets.
 */
public final class RoadWalkPlanner {

    private static final double SAMPLE_INTERVAL = 16.0;

    private RoadWalkPlanner() {}

    /**
     * Plan road-assisted walk waypoints from {@code from} to {@code to}.
     *
     * @param level the level
     * @param from  start position
     * @param to    destination
     * @return intermediate waypoints along the road network, or empty list if no road is beneficial
     */
    public static List<BlockPos> plan(Level level, BlockPos from, BlockPos to) {
        if (level == null || !(level instanceof ServerLevel serverLevel) || from == null || to == null) {
            return Collections.emptyList();
        }

        // Short distance (<= 12 blocks): direct walking is always better than road network routing
        if (from.distSqr(to) <= 144) {
            return Collections.emptyList();
        }

        RoadNetwork network = RoadSavedData.getOrCreate(serverLevel).getNetwork();
        if (network == null || network.isEmpty()) {
            return Collections.emptyList();
        }

        PathPoint startPt = new PathPoint(from.getX(), from.getY(), from.getZ());
        PathPoint endPt = new PathPoint(to.getX(), to.getY(), to.getZ());

        // Walking speeds: onRoad weight = 1, offRoad weight = 2
        TransportRoute route = RoadRouter.plan(network, startPt, endPt, 1, 2);
        if (route == null || route.isEmpty()) {
            return Collections.emptyList();
        }

        // If the route doesn't cruise on any road segment, direct navigation is preferred
        boolean hasOnRoad = route.legs().stream().anyMatch(leg -> !leg.offRoad());
        if (!hasOnRoad) {
            return Collections.emptyList();
        }

        List<BlockPos> waypoints = new ArrayList<>();

        for (SplineLeg leg : route.legs()) {
            double len = leg.getApproxLength();
            int steps = Math.max(1, (int) Math.ceil(len / SAMPLE_INTERVAL));
            double u0 = leg.uStart();
            double u1 = leg.uEnd();

            for (int i = 0; i <= steps; i++) {
                double u = u0 + (u1 - u0) * ((double) i / steps);
                SplineVec3 p = leg.spline().evaluate(u).position();
                int bx = (int) Math.floor(p.x());
                int bz = (int) Math.floor(p.z());

                // Find surface standing height
                int by = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
                BlockPos wp = new BlockPos(bx, by, bz);

                if (waypoints.isEmpty()) {
                    waypoints.add(wp);
                } else {
                    BlockPos last = waypoints.get(waypoints.size() - 1);
                    if (last.distSqr(wp) >= 9.0) { // At least 3 blocks apart
                        waypoints.add(wp);
                    }
                }
            }
        }

        // Ensure final target is at the end if beneficial
        if (!waypoints.isEmpty()) {
            BlockPos last = waypoints.get(waypoints.size() - 1);
            if (last.distSqr(to) >= 9.0) {
                waypoints.add(to);
            }
        }

        return waypoints;
    }
}
