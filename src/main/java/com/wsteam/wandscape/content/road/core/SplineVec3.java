package com.wsteam.wandscape.road.core;

/**
 * A pure Java 3D double vector for spline math, ensuring zero Minecraft dependencies.
 */
public record SplineVec3(double x, double y, double z) {
    public static final SplineVec3 ZERO = new SplineVec3(0, 0, 0);

    public SplineVec3 add(SplineVec3 other) {
        return new SplineVec3(this.x + other.x, this.y + other.y, this.z + other.z);
    }

    public SplineVec3 subtract(SplineVec3 other) {
        return new SplineVec3(this.x - other.x, this.y - other.y, this.z - other.z);
    }

    public SplineVec3 scale(double scalar) {
        return new SplineVec3(this.x * scalar, this.y * scalar, this.z * scalar);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public SplineVec3 normalize() {
        double len = length();
        if (len < 1e-9) return ZERO;
        return new SplineVec3(x / len, y / len, z / len);
    }

    public double dot(SplineVec3 other) {
        return this.x * other.x + this.y * other.y + this.z * other.z;
    }

    @Override
    public String toString() {
        return String.format("(%.2f, %.2f, %.2f)", x, y, z);
    }
}
