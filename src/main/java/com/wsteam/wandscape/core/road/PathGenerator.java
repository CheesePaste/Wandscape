package com.wsteam.wandscape.core.road;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Generates path coordinates between two points.
 * Uses L-shaped paths: walk X first, then Z.
 *
 * <p>2D variant ({@link #lShape}) for topology;
 * 3D variant ({@link #lShape3D}) first descends/ascends to
 * the target Y via a square spiral (no 180-degree reversals),
 * then walks a flat L-path horizontally to the target.
 */
public final class PathGenerator {

    private PathGenerator() {}

    /**
     * Generate an L-shaped path from {@code from} to {@code to}.
     * The path walks the X axis first, then the Z axis.
     */
    public static List<XZPoint> lShape(XZPoint from, XZPoint to) {
        if (from.equals(to)) {
            return Collections.emptyList();
        }

        List<XZPoint> path = new ArrayList<>();

        int dx = to.x() - from.x();
        int dz = to.z() - from.z();
        int sx = Integer.signum(dx);
        int sz = Integer.signum(dz);

        // Walk X first
        int cx = from.x();
        int cz = from.z();
        for (int i = 0; i < Math.abs(dx); i++) {
            cx += sx;
            path.add(new XZPoint(cx, cz));
        }

        // Then walk Z
        for (int i = 0; i < Math.abs(dz); i++) {
            cz += sz;
            path.add(new XZPoint(cx, cz));
        }

        return path;
    }

    /**
     * Generate a 3D path from {@code from} to {@code to} with
     * vertical-then-horizontal separation.
     *
     * <p>Algorithm:
     * <ol>
     *   <li><b>Vertical phase</b> — if ΔY ≠ 0, use a <b>Square Spiral</b>
     *       around the starting point. It only uses 90-degree turns
     *       (+X, +Z, -X, -Z) avoiding any 180-degree jagged reversals.
     *       It stops exactly when target Y is reached.</li>
     *   <li><b>Horizontal phase</b> — flat L-shaped walk from wherever
     *       the spiral ended directly to {@code to.xz} at constant {@code to.y}.</li>
     * </ol>
     */
    public static List<PathPoint> lShape3D(PathPoint from, PathPoint to, int amplitude) {
        int dx = to.x() - from.x();
        int dz = to.z() - from.z();
        int dy = to.y() - from.y();

        // Same point → empty
        if (dx == 0 && dz == 0 && dy == 0) {
            return Collections.emptyList();
        }

        List<PathPoint> path = new ArrayList<>();

        // ── Phase 1: Vertical — Spiral down/up to target Y ──
        if (dy != 0) {
            path.addAll(spiralPath(from, to, amplitude));
        }

        // ── Phase 2: Horizontal — Flat walk to target XZ ──
        // Continue smoothly from where the spiral ended (or from start if pure flat)
        PathPoint flatStart = path.isEmpty() ? from : path.get(path.size() - 1);

        // Only walk if we aren't already at the exact XZ
        if (flatStart.x() != to.x() || flatStart.z() != to.z()) {
            PathPoint flatTarget = new PathPoint(to.x(), to.y(), to.z());
            path.addAll(flatLShape(flatStart, flatTarget));
        }

        return path;
    }

    /**
     * Flat L-shaped path (constant Y) — X first, then Z.
     */
    private static List<PathPoint> flatLShape(PathPoint from, PathPoint to) {
        List<PathPoint> path = new ArrayList<>();
        int dx = to.x() - from.x();
        int dz = to.z() - from.z();
        int sx = Integer.signum(dx);
        int sz = Integer.signum(dz);
        int cy = to.y(); // all points at target Y

        int cx = from.x();
        int cz = from.z();
        for (int i = 0; i < Math.abs(dx); i++) {
            cx += sx;
            path.add(new PathPoint(cx, cy, cz));
        }
        for (int i = 0; i < Math.abs(dz); i++) {
            cz += sz;
            path.add(new PathPoint(cx, cy, cz));
        }
        return path;
    }

    /**
     * Build a continuous square spiral ramp to resolve height difference.
     * Replaces the old 180-degree jagged zig-zag with 90-degree turns.
     * Walks in a hollow box pattern until target Y is reached.
     */
    private static List<PathPoint> spiralPath(PathPoint from, PathPoint to, int amplitude) {
        List<PathPoint> path = new ArrayList<>();
        int cx = from.x();
        int cy = from.y();
        int cz = from.z();
        int targetY = to.y();

        // 4 directions for a square loop: +X, +Z, -X, -Z
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        int dirIndex = 0;

        // Ensure minimum side length to make a proper box
        int sideLength = Math.max(2, amplitude);

        while (cy != targetY) {
            int dx = dirs[dirIndex][0];
            int dz = dirs[dirIndex][1];

            // Walk one side of the spiral
            for (int s = 0; s < sideLength && cy != targetY; s++) {
                cx += dx;
                cz += dz;
                cy += clampStep(targetY - cy);
                path.add(new PathPoint(cx, cy, cz));
            }
            // Turn 90 degrees for the next side
            dirIndex = (dirIndex + 1) % 4;
        }

        return path;
    }

    /** Clamp Y step to [-1, 1] for walkability. */
    private static int clampStep(int remaining) {
        if (remaining > 0) return 1;
        if (remaining < 0) return -1;
        return 0;
    }

    /**
     * Return the turn points in a path.
     */
    public static List<Integer> turnIndices(List<XZPoint> path) {
        if (path.size() <= 1) return Collections.emptyList();

        List<Integer> turns = new ArrayList<>();

        int lastXIdx = -1;
        for (int i = 1; i < path.size(); i++) {
            if (path.get(i - 1).x() != path.get(i).x()) {
                lastXIdx = i;
            }
        }

        if (lastXIdx > 0 && lastXIdx < path.size() - 1) {
            boolean hasZAfter = false;
            for (int i = lastXIdx + 1; i < path.size(); i++) {
                if (path.get(i).z() != path.get(lastXIdx).z()) {
                    hasZAfter = true;
                    break;
                }
            }
            if (hasZAfter) {
                turns.add(lastXIdx);
            }
        }

        return turns;
    }

    /**
     * Find turn indices in a 3D path by detecting primary-axis switches.
     * This will now properly detect every 90-degree turn in the spiral.
     */
    public static List<Integer> turnIndices3D(List<PathPoint> path) {
        if (path.size() <= 2) return Collections.emptyList();

        List<Integer> turns = new ArrayList<>();
        int prevDx = path.get(1).x() - path.get(0).x();
        int prevDz = path.get(1).z() - path.get(0).z();
        boolean prevIsX = Math.abs(prevDx) > Math.abs(prevDz);

        for (int i = 1; i < path.size() - 1; i++) {
            int dx = path.get(i + 1).x() - path.get(i).x();
            int dz = path.get(i + 1).z() - path.get(i).z();
            boolean isX = Math.abs(dx) > Math.abs(dz);
            if (isX != prevIsX) {
                turns.add(i);
            }
            prevIsX = isX;
        }
        return turns;
    }
}
