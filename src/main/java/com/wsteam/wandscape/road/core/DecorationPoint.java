package com.wsteam.wandscape.road.core;

import com.wsteam.wandscape.road.algorithm.DecorationPlanner;

/**
 * A decoration placement computed by {@link DecorationPlanner}.
 * Pure data — zero MC dependencies.
 *
 * @param type   decoration kind: "lamp", "bench"
 * @param facing cardinal direction the decoration faces toward
 *               ("north", "south", "east", "west")
 */
public record DecorationPoint(int x, int y, int z, String type, String facing) {

    public DecorationPoint {
        if (type == null || type.isBlank())
            throw new IllegalArgumentException("type must not be blank");
        if (facing == null || facing.isBlank())
            throw new IllegalArgumentException("facing must not be blank");
    }
}
