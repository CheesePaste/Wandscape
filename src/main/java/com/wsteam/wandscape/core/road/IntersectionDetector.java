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
     *
     * <p>A point is a true intersection (crossing or T-junction) when
     * edges pass through it with different axis directions (X vs Z).
     * Collinear shared segments (both edges moving in X, or both in Z)
     * are deduplication concerns, not intersections — they are excluded.
     *
     * @param edges all edges to check
     * @return set of intersection XZ points (directional crossings only)
     */
    public static Set<XZPoint> detectAll(List<RoadEdge> edges) {
        // Build a map: XZPoint → list of edges that contain it
        Map<XZPoint, List<RoadEdge>> pointToEdges = new HashMap<>();
        for (RoadEdge edge : edges) {
            for (XZPoint p : edge.getPath()) {
                pointToEdges.computeIfAbsent(p, k -> new ArrayList<>()).add(edge);
            }
        }

        Set<XZPoint> crossings = new HashSet<>();
        for (var entry : pointToEdges.entrySet()) {
            if (entry.getValue().size() < 2) continue; // Only one edge at this point

            XZPoint p = entry.getKey();
            List<RoadEdge> containingEdges = entry.getValue();

            boolean hasXDirection = false;
            boolean hasZDirection = false;

            for (RoadEdge edge : containingEdges) {
                List<XZPoint> path = edge.getPath();
                int idx = path.indexOf(p);
                if (idx < 0) continue;

                // Determine travel direction through this point
                boolean edgeMovesX = directionAt(path, idx, true);  // X differs
                boolean edgeMovesZ = directionAt(path, idx, false); // Z differs

                if (edgeMovesX) hasXDirection = true;
                if (edgeMovesZ) hasZDirection = true;

                // Turn point (L-shape corner): has both X and Z direction
                // A turn point shared with any other edge is always a crossing
                if (edgeMovesX && edgeMovesZ) {
                    hasXDirection = true;
                    hasZDirection = true;
                }
            }

            // True intersection only when edges meet from different directions
            if (hasXDirection && hasZDirection) {
                crossings.add(p);
            }
        }
        return crossings;
    }

    /**
     * Check whether the path has movement in the given axis at position {@code idx}.
     *
     * @param path the ordered path
     * @param idx  index of the point to check
     * @param xAxis true to check X movement, false for Z
     * @return true if an adjacent point differs in the given axis
     */
    private static boolean directionAt(List<XZPoint> path, int idx, boolean xAxis) {
        if (idx > 0) {
            XZPoint prev = path.get(idx - 1);
            XZPoint cur = path.get(idx);
            if (xAxis && prev.x() != cur.x()) return true;
            if (!xAxis && prev.z() != cur.z()) return true;
        }
        if (idx < path.size() - 1) {
            XZPoint cur = path.get(idx);
            XZPoint next = path.get(idx + 1);
            if (xAxis && next.x() != cur.x()) return true;
            if (!xAxis && next.z() != cur.z()) return true;
        }
        return false;
    }
}
