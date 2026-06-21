package com.wsteam.wandscape.core.road;

/**
 * A 3D point in the world, used for road path storage.
 * Replaces XZPoint in path contexts where Y matters for
 * vertical connectivity and excavation.
 *
 * <p>XZPoint is retained for obstacle/occupancy tracking
 * and MST topology where only the horizontal plane matters.
 */
public record PathPoint(int x, int y, int z) {

    /** The XZ projection of this point. */
    public XZPoint xz() {
        return new XZPoint(x, z);
    }

    /** Manhattan distance in XZ plane (ignoring Y). */
    public int manhattanXZTo(PathPoint other) {
        return Math.abs(x - other.x) + Math.abs(z - other.z);
    }

    @Override
    public String toString() {
        return "(" + x + "," + y + "," + z + ")";
    }
}
