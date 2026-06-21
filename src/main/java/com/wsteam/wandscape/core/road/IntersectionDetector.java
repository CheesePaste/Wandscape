package com.wsteam.wandscape.core.road;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects intersection points between road paths in the XZ plane.
 *
 * <p>An intersection is a point where two or more road edges meet with
 * different travel directions (one edge moves in X, another in Z).
 * Collinear overlaps (two edges sharing the same straight-line segment)
 * are NOT intersections — those are handled by deduplication.
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
     * Find all <em>directional</em> intersection points across multiple edges.
     */
    public static Set<XZPoint> detectAll(List<RoadEdge> edges) {
        Map<XZPoint, List<RoadEdge>> pointToEdges = new HashMap<>();
        for (RoadEdge edge : edges) {
            for (PathPoint pp : edge.getPath()) {
                pointToEdges.computeIfAbsent(pp.xz(), k -> new ArrayList<>()).add(edge);
            }
        }

        Set<XZPoint> crossings = new HashSet<>();
        for (var entry : pointToEdges.entrySet()) {
            if (entry.getValue().size() < 2) continue;

            XZPoint p = entry.getKey();
            List<RoadEdge> containingEdges = entry.getValue();

            boolean hasXDirection = false;
            boolean hasZDirection = false;

            for (RoadEdge edge : containingEdges) {
                List<PathPoint> path = edge.getPath();
                int idx = indexOfXz(path, p);
                if (idx < 0) continue;

                boolean edgeMovesX = directionAt3D(path, idx, true);
                boolean edgeMovesZ = directionAt3D(path, idx, false);

                if (edgeMovesX) hasXDirection = true;
                if (edgeMovesZ) hasZDirection = true;

                if (edgeMovesX && edgeMovesZ) {
                    hasXDirection = true;
                    hasZDirection = true;
                }
            }

            if (hasXDirection && hasZDirection) {
                crossings.add(p);
            }
        }
        return crossings;
    }

    private static int indexOfXz(List<PathPoint> path, XZPoint target) {
        for (int i = 0; i < path.size(); i++) {
            if (path.get(i).xz().equals(target)) return i;
        }
        return -1;
    }

    private static boolean directionAt3D(List<PathPoint> path, int idx, boolean xAxis) {
        if (idx > 0) {
            PathPoint prev = path.get(idx - 1);
            PathPoint cur = path.get(idx);
            if (xAxis && prev.x() != cur.x()) return true;
            if (!xAxis && prev.z() != cur.z()) return true;
        }
        if (idx < path.size() - 1) {
            PathPoint cur = path.get(idx);
            PathPoint next = path.get(idx + 1);
            if (xAxis && next.x() != cur.x()) return true;
            if (!xAxis && next.z() != cur.z()) return true;
        }
        return false;
    }
}
