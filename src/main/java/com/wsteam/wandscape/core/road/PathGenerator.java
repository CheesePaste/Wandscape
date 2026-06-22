package com.wsteam.wandscape.core.road;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Generates path coordinates between two points.
 * Uses L-shaped paths: walk X first, then Z.
 *
 * <p>2D variant ({@link #lShape}) for topology;
 * 3D variant ({@link #lShape3D}) distributes the height
 * difference evenly across every horizontal step, producing
 * a smooth ramp. Falls back to a square spiral switchback
 * only when the slope exceeds 45°.</p>
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
     * Generate a 3D L-shaped path with <b>smooth ramp</b> elevation
     * and <b>flat intersection margins</b>.
     *
     * <p>Flat margins at both ends keep the road at the intersection
     * Y-level for the first/last few blocks so it doesn't immediately
     * ramp into the headroom of the crossing road — the road only
     * starts climbing/descending once it clears the crossing width.
     *
     * <p>The height difference (ΔY) is distributed across the middle
     * segment, producing a walkable slope.
     *
     * <p><b>Fallback:</b> If the slope exceeds 45° (total horizontal
     * steps &lt; |ΔY|), the path degenerates to a {@link #spiralPath}
     * switchback to keep the grade walkable.
     *
     * <p>Segments:
     * <ol>
     *   <li>First {@code margin} steps — flat at {@code from.y()}</li>
     *   <li>Middle {@code rampSteps} steps — linear Y ramp</li>
     *   <li>Last {@code margin} steps — flat at {@code to.y()}</li>
     * </ol>
     */
    public static List<PathPoint> lShape3D(PathPoint from, PathPoint to, int amplitude) {
        int dx = to.x() - from.x();
        int dz = to.z() - from.z();
        int dy = to.y() - from.y();

        if (dx == 0 && dz == 0 && dy == 0) {
            return Collections.emptyList();
        }

        int absDx = Math.abs(dx);
        int absDz = Math.abs(dz);
        int total2DSteps = absDx + absDz;

        List<PathPoint> path = new ArrayList<>();

        // ── Steep slope fallback: spiral switchback ──
        if (total2DSteps < Math.abs(dy)) {
            path.addAll(spiralPath(from, to, amplitude));
            PathPoint spiralEnd = path.isEmpty() ? from : path.get(path.size() - 1);
            if (spiralEnd.x() != to.x() || spiralEnd.z() != to.z()) {
                path.addAll(flatLShape(spiralEnd, new PathPoint(to.x(), to.y(), to.z())));
            }
            return path;
        }

        // ── Normal case: smooth ramp with flat intersection margins ──
        int margin = Math.min(3, total2DSteps / 3);
        int rampSteps = total2DSteps - 2 * margin; // steps actually used for climbing

        int cx = from.x();
        int cz = from.z();
        int step = 0;

        int sx = Integer.signum(dx);
        int sz = Integer.signum(dz);

        // Walk X first
        for (int i = 0; i < absDx; i++) {
            cx += sx;
            step++;
            path.add(new PathPoint(cx, rampY(from.y(), to.y(), dy, step, margin, total2DSteps, rampSteps), cz));
        }

        // Then walk Z
        for (int i = 0; i < absDz; i++) {
            cz += sz;
            step++;
            path.add(new PathPoint(cx, rampY(from.y(), to.y(), dy, step, margin, total2DSteps, rampSteps), cz));
        }

        return path;
    }

    /**
     * Compute Y for a single step of the three-segment ramp (flat → ramp → flat).
     *
     * @param fromY          start Y
     * @param toY            target Y
     * @param dy             total Y delta (to.y - from.y)
     * @param step           1-based step index
     * @param margin         flat margin size at each end
     * @param total2DSteps   total horizontal step count
     * @param rampSteps      total2DSteps - 2*margin
     */
    private static int rampY(int fromY, int toY, int dy,
                             int step, int margin,
                             int total2DSteps, int rampSteps) {
        if (step <= margin) {
            return fromY;
        }
        if (step >= total2DSteps - margin) {
            return toY;
        }
        // Linear interpolation across the middle segment
        float progress = (float) (step - margin) / rampSteps;
        return fromY + Math.round(dy * progress);
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
     * Build a continuous square spiral ramp as a <b>steep-slope fallback</b>.
     * Only invoked when |ΔY| exceeds the total horizontal step count (slope &gt; 45°).
     * Walks in a hollow box pattern with 90-degree turns until target Y is reached.
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
