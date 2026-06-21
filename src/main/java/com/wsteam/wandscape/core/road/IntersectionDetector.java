package com.wsteam.wandscape.core.road;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects intersection points between road paths in the XZ plane.
 */
public final class IntersectionDetector {

    private IntersectionDetector() {}

    /**
     * Find all 2D points where two paths intersect.
     * Both paths are treated as sets of points (order doesn't matter).
     *
     * @param pathA first path
     * @param pathB second path
     * @return set of XZ points present in both paths
     */
    public static Set<XZPoint> detect(List<XZPoint> pathA, List<XZPoint> pathB) {
        if (pathA.isEmpty() || pathB.isEmpty()) {
            return Collections.emptySet();
        }

        Set<XZPoint> setA = new HashSet<>(pathA);
        Set<XZPoint> result = new HashSet<>();
        for (XZPoint p : pathB) {
            if (setA.contains(p)) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * Find all intersection points across multiple edges.
     * Runs pairwise detection; each unique intersection is returned once.
     *
     * @param edges all edges to check
     * @return set of intersection XZ points
     */
    public static Set<XZPoint> detectAll(List<RoadEdge> edges) {
        Set<XZPoint> allPoints = new HashSet<>();
        for (RoadEdge edge : edges) {
            allPoints.addAll(edge.getPath());
        }

        // Intersections are points that appear in at least 2 different edge paths
        Set<XZPoint> intersections = new HashSet<>();
        Set<XZPoint> seen = new HashSet<>();
        for (RoadEdge edge : edges) {
            for (XZPoint p : edge.getPath()) {
                if (!seen.add(p)) {
                    // Already seen in another edge — this is an intersection
                    intersections.add(p);
                }
            }
        }
        return intersections;
    }
}
