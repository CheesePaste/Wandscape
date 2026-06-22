package com.wsteam.wandscape.core.road;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Generates path coordinates between two points.
 * Uses L-shaped paths: walk X first, then Z.
 *
 * <p>2D variant ({@link #lShape}) for topology;
 * 3D variant ({@link #lShape3D}) distributes the height
 * difference evenly across every horizontal step, producing
 * a smooth ramp. When an {@code existingRoads} map is provided,
 * shared-corridor snapping prevents the new path from ramp-climbing
 * over an older road that occupies the same XZ lane — the new path
 * copies the existing Y until it diverges onto its own unique XZ.</p>
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
     * Convenience overload — generates a 3D path without corridor snapping.
     * Equivalent to {@code lShape3D(from, to, amplitude, null)}.
     */
    public static List<PathPoint> lShape3D(PathPoint from, PathPoint to, int amplitude) {
        return lShape3D(from, to, amplitude, null);
    }

    /**
     * Generate a 3D L-shaped path with <b>smooth ramp</b> elevation,
     * <b>flat intersection margins</b>, and optional
     * <b>shared-corridor snapping</b>.
     *
     * <p><b>Snapping:</b> When {@code existingRoads} is non-null and
     * contains XZ coordinates that lie on the new path's 2D L-shape,
     * the new path copies the existing Y for every shared tile.
     * It only begins its own ramp after the first XZ coordinate that
     * does NOT appear in {@code existingRoads} (the divergence point).
     * This prevents a branch road from ramping over the trunk road
     * they share a lane with.
     *
     * <p>Segments per path:
     * <ol>
     *   <li><b>Shared corridor</b> — Y snapped from {@code existingRoads}</li>
     *   <li><b>Transition margin</b> — flat at the last shared Y</li>
     *   <li><b>Ramp</b> — remaining ΔY distributed evenly</li>
     *   <li><b>Terminal margin</b> — flat at {@code to.y()}</li>
     * </ol>
     *
     * <p><b>Fallback:</b> If the remaining slope after divergence exceeds
     * 45°, the path degenerates to a {@link #spiralPath} switchback.
     *
     * @param from          start position
     * @param to            target position
     * @param amplitude     spiral side length (used only on steep-slope fallback)
     * @param existingRoads map of XZ → road-surface-Y for already-placed roads;
     *                      {@code null} or empty means no snapping
     */
    public static List<PathPoint> lShape3D(PathPoint from, PathPoint to, int amplitude,
                                            Map<XZPoint, Integer> existingRoads) {
        int dx = to.x() - from.x();
        int dz = to.z() - from.z();
        int dy = to.y() - from.y();

        if (dx == 0 && dz == 0 && dy == 0) {
            return Collections.emptyList();
        }

        int absDx = Math.abs(dx);
        int absDz = Math.abs(dz);
        int total2DSteps = absDx + absDz;

        // Same XZ, different Y → pure spiral (no horizontal lane to share)
        if (total2DSteps == 0) {
            List<PathPoint> path = new ArrayList<>();
            path.addAll(spiralPath(from, to, amplitude));
            if (path.get(path.size() - 1).y() != to.y()) {
                path.add(new PathPoint(to.x(), to.y(), to.z()));
            }
            return path;
        }

        // ── Phase 1: generate 2D XZ trajectory ──
        List<XZPoint> steps2D = new ArrayList<>(total2DSteps);
        int cx = from.x();
        int cz = from.z();
        int sx = Integer.signum(dx);
        for (int i = 0; i < absDx; i++) {
            cx += sx;
            steps2D.add(new XZPoint(cx, cz));
        }
        int sz = Integer.signum(dz);
        for (int i = 0; i < absDz; i++) {
            cz += sz;
            steps2D.add(new XZPoint(cx, cz));
        }

        // ── Phase 2: find divergence point in shared corridor ──
        int divergeIdx = -1;   // last index (inclusive) that overlaps existing road
        int currentY = from.y();

        boolean haveExisting = existingRoads != null && !existingRoads.isEmpty();
        if (haveExisting) {
            for (int i = 0; i < steps2D.size(); i++) {
                XZPoint pt = steps2D.get(i);
                Integer existingY = existingRoads.get(pt);
                if (existingY != null) {
                    divergeIdx = i;
                    currentY = existingY; // snap to existing road height
                } else {
                    break; // first tile without an existing road → divergence
                }
            }
        }

        List<PathPoint> path = new ArrayList<>();

        // ── Shared corridor (snapped) ──
        for (int i = 0; i <= divergeIdx; i++) {
            XZPoint pt = steps2D.get(i);
            path.add(new PathPoint(pt.x(), existingRoads.get(pt), pt.z()));
        }

        // ── Phase 3: independent segment after divergence ──
        int remainingSteps = total2DSteps - 1 - divergeIdx;
        int remainingDy = to.y() - currentY;

        if (remainingSteps > 0) {
            // Spiral fallback if remaining ramp is too steep
            if (remainingSteps < Math.abs(remainingDy)) {
                PathPoint spiralStart = path.isEmpty() ? from : path.get(path.size() - 1);
                path.addAll(spiralPath(spiralStart, to, amplitude));
                PathPoint spiralEnd = path.get(path.size() - 1);
                if (spiralEnd.x() != to.x() || spiralEnd.z() != to.z()) {
                    path.addAll(flatLShape(spiralEnd, new PathPoint(to.x(), to.y(), to.z())));
                }
                return path;
            }

            // Normal smooth ramp with margins on the independent segment
            int margin = Math.min(3, remainingSteps / 3);
            int rampSteps = remainingSteps - 2 * margin;

            for (int i = divergeIdx + 1; i < total2DSteps; i++) {
                int stepInRamp = i - divergeIdx; // 1-based within independent segment

                int cy;
                if (rampSteps <= 0) {
                    cy = to.y();
                } else if (stepInRamp <= margin) {
                    cy = currentY;          // flat transition off the shared corridor
                } else if (stepInRamp >= remainingSteps - margin) {
                    cy = to.y();            // flat approach to destination
                } else {
                    float progress = (float) (stepInRamp - margin) / rampSteps;
                    cy = currentY + Math.round(remainingDy * progress);
                }

                XZPoint pt = steps2D.get(i);
                path.add(new PathPoint(pt.x(), cy, pt.z()));
            }
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
