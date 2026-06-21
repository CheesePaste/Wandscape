package com.wsteam.wandscape.core.road;

/**
 * A 2D point in the XZ plane, used for path generation
 * and road network calculations.
 */
public record XZPoint(int x, int z) {

    /** Manhattan distance to another point. */
    public int manhattanTo(XZPoint other) {
        return Math.abs(x - other.x) + Math.abs(z - other.z);
    }

    /** Create an XZPoint from a building data record (drops Y). */
    public static XZPoint fromBuildData(RoadBuildingData bd) {
        return new XZPoint(bd.x(), bd.z());
    }

    @Override
    public String toString() {
        return "(" + x + "," + z + ")";
    }
}
