package com.wsteam.wandscape.content.road.core;

import java.util.List;

/**
 * An item transport route consisting of one or more sequential spline legs.
 *
 * <p>Pure core record with zero Minecraft dependencies.
 */
public record TransportRoute(List<SplineLeg> legs) {

    public boolean isEmpty() {
        return legs == null || legs.isEmpty();
    }

    /**
     * Compute total duration in ticks over all legs in this route.
     *
     * @param ticksOnRoad  ticks per block on road
     * @param ticksOffRoad ticks per block off road
     * @return total ticks required for the complete route
     */
    public int totalDuration(int ticksOnRoad, int ticksOffRoad) {
        if (isEmpty()) return 1;
        int total = 0;
        for (SplineLeg leg : legs) {
            int rate = leg.offRoad() ? ticksOffRoad : ticksOnRoad;
            int legTicks = Math.max(1, (int) Math.round(leg.getApproxLength() * rate));
            total += legTicks;
        }
        return Math.max(1, total);
    }

    /**
     * Create a direct off-road line route between two points.
     */
    public static TransportRoute direct(PathPoint from, PathPoint to) {
        SplineModel gap = new SplineModel();
        SplineVec3 pA = new SplineVec3(from.x() + 0.5, from.y() + 0.5, from.z() + 0.5);
        SplineVec3 pB = new SplineVec3(to.x() + 0.5, to.y() + 0.5, to.z() + 0.5);
        gap.getPoints().add(new SplinePoint(pA, pA, pA, true));
        gap.getPoints().add(new SplinePoint(pB, pB, pB, true));
        return new TransportRoute(List.of(new SplineLeg(gap, 0.0, 1.0, true)));
    }
}
