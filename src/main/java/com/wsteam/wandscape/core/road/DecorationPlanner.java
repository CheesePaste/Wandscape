package com.wsteam.wandscape.core.road;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plans decoration placements along completed road edges.
 *
 * <p>Scans each edge's 3D path and samples decoration points
 * at configurable intervals, alternating left/right of the road.
 * Pure computation — zero MC dependencies.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Walk the path, accumulating XZ distance from edge start</li>
 *   <li>At each step, compute forward and perpendicular directions</li>
 *   <li>Offset perpendicular by {@code halfWidth + 1} to place
 *       decorations at the road shoulder</li>
 *   <li>Match distance against lamp/bench spacing intervals</li>
 *   <li>Alternate sides for visual variety</li>
 * </ol>
 */
public final class DecorationPlanner {

    private DecorationPlanner() {}

    /**
     * Plan decoration points for a single edge.
     *
     * @param edge      the completed road edge
     * @param lampStep  spacing between lamps (0 = disabled)
     * @param benchStep spacing between benches (0 = disabled)
     * @param halfWidth half of road width (from {@code defaultWidth / 2})
     * @return ordered list of decoration points; empty if both spacings are disabled
     */
    public static List<DecorationPoint> planForEdge(RoadEdge edge,
                                                     int lampStep,
                                                     int benchStep,
                                                     int halfWidth) {
        List<PathPoint> path = edge.getPath();
        if (path.size() < 2) return Collections.emptyList();
        if (lampStep <= 0 && benchStep <= 0) return Collections.emptyList();

        List<DecorationPoint> points = new ArrayList<>();
        int dist = 0;
        int side = 1;
        int placedCount = 0;

        for (int i = 0; i < path.size(); i++) {
            if (i > 0) {
                dist += path.get(i).manhattanXZTo(path.get(i - 1));
            } else {
                continue; // skip start — too close to building anchor
            }

            PathPoint p = path.get(i);

            // Forward direction at this step
            int fwdDx, fwdDz;
            if (i < path.size() - 1) {
                fwdDx = Integer.signum(path.get(i + 1).x() - p.x());
                fwdDz = Integer.signum(path.get(i + 1).z() - p.z());
            } else {
                fwdDx = Integer.signum(p.x() - path.get(i - 1).x());
                fwdDz = Integer.signum(p.z() - path.get(i - 1).z());
            }

            // Perpendicular: rotate (dx, dz) 90° CCW → (-dz, dx)
            int perpDx = -fwdDz * side;
            int perpDz = fwdDx * side;

            int offset = halfWidth; // road edge tile (already at road Y)
            int dx = p.x() + perpDx * offset;
            int dz = p.z() + perpDz * offset;

            // Facing toward road (opposite of perpendicular outward)
            String facing = facingFromDelta(-perpDx, -perpDz);

            boolean placed = false;
            if (lampStep > 0 && dist % lampStep == 0) {
                points.add(new DecorationPoint(dx, p.y(), dz, "lamp", facing));
                placed = true;
            }
            if (benchStep > 0 && dist % benchStep == 0 && !placed) {
                points.add(new DecorationPoint(dx, p.y(), dz, "bench", facing));
                placed = true;
            }

            if (placed) {
                placedCount++;
                side = (placedCount % 2 == 0) ? 1 : -1;
            }
        }

        return points;
    }

    // ---- helpers ----

    /** Map a normalised 2D direction to a cardinal string. */
    static String facingFromDelta(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? "east" : "west";
        } else {
            return dz >= 0 ? "south" : "north";
        }
    }
}
