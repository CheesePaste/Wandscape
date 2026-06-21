package com.wsteam.wandscape.core.road;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Generates path coordinates between two points.
 * Uses L-shaped paths: walk X first, then Z.
 *
 * <p>2D variant ({@link #lShape}) for topology;
 * 3D variant ({@link #lShape3D}) distributes Y along the path
 * with at most 1 block vertical change per step.
 */
public final class PathGenerator {

    private PathGenerator() {}

    /**
     * Generate an L-shaped path from {@code from} to {@code to}.
     * The path walks the X axis first, then the Z axis.
     * The start point is excluded; the end point is included.
     * If the two points are the same, an empty list is returned.
     *
     * @param from start position (exclusive)
     * @param to   end position (inclusive)
     * @return ordered list of points along the path
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
     * Generate a 3D L-shaped path with Y interpolation.
     * XZ topology is identical to {@link #lShape} (X first, then Z).
     * The Y delta is distributed evenly across all steps,
     * with at most 1 block vertical change per step for walkability.
     *
     * <p>Example: from (0,70,0) to (5,50,3) — N=8 steps, ΔY=-20:
     * each step drops 2 or 3 blocks so the road smoothly descends.
     *
     * @param from start position (exclusive) — typically a building anchor
     * @param to   end position (inclusive) — typically another building anchor
     * @return ordered 3D path points (may be empty if same XZ)
     */
    public static List<PathPoint> lShape3D(PathPoint from, PathPoint to) {
        int dx = to.x() - from.x();
        int dz = to.z() - from.z();
        int totalSteps = Math.abs(dx) + Math.abs(dz);
        if (totalSteps == 0) {
            return Collections.emptyList();
        }

        int dy = to.y() - from.y();
        int sx = Integer.signum(dx);
        int sz = Integer.signum(dz);

        List<PathPoint> path = new ArrayList<>();

        // Simple Y distribution: change Y by at most 1 per XZ step.
        // If |ΔY| > totalSteps, the path won't reach the target
        // during XZ traversal — stair steps are appended at the end.
        int cy = from.y();
        int remainingY = dy;

        // Walk X first
        int cx = from.x();
        int cz = from.z();
        for (int i = 0; i < Math.abs(dx); i++) {
            cx += sx;
            int stepY = clampStep(remainingY);
            cy += stepY;
            remainingY -= stepY;
            path.add(new PathPoint(cx, cy, cz));
        }

        // Then walk Z
        for (int i = 0; i < Math.abs(dz); i++) {
            cz += sz;
            int stepY = clampStep(remainingY);
            cy += stepY;
            remainingY -= stepY;
            path.add(new PathPoint(cx, cy, cz));
        }

        // Append stair steps at final XZ if Y hasn't reached target
        while (remainingY != 0) {
            int stepY = clampStep(remainingY);
            cy += stepY;
            remainingY -= stepY;
            path.add(new PathPoint(cx, cy, cz));
        }

        // Override last point to exact target coordinates
        if (!path.isEmpty()) {
            PathPoint last = path.get(path.size() - 1);
            path.set(path.size() - 1, new PathPoint(to.x(), to.y(), to.z()));
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
     * Return the turn points in a path — positions where
     * the direction changes (from X to Z walk).
     * For an L-shaped path this is exactly the last X-walk point.
     *
     * @param path full path list (must be non-empty)
     * @return indices into the path where turns occur
     */
    public static List<Integer> turnIndices(List<XZPoint> path) {
        if (path.size() <= 1) return Collections.emptyList();

        List<Integer> turns = new ArrayList<>();

        // Find the index of the last point that differs in X from the previous.
        // In an X-first L-shaped path, this is the last point of the X segment.
        int lastXIdx = -1;
        for (int i = 1; i < path.size(); i++) {
            if (path.get(i - 1).x() != path.get(i).x()) {
                lastXIdx = i;
            }
        }

        if (lastXIdx > 0 && lastXIdx < path.size() - 1) {
            // Verify there is also a Z segment after this point
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

    /** Same as {@link #turnIndices(List)} but for 3D paths. */
    public static List<Integer> turnIndices3D(List<PathPoint> path) {
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
            if (hasZAfter) turns.add(lastXIdx);
        }
        return turns;
    }
}
