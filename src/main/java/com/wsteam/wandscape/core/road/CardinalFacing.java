package com.wsteam.wandscape.core.road;

/**
 * 2D cardinal direction (no Y axis).
 * Used for template entry/exit facings and expansion headings.
 *
 * <p>Order is indexed clockwise: S=0, W=1, N=2, E=3
 * (consistent with MC's horizontal index convention).
 */
public enum CardinalFacing {

    SOUTH(0, 0, 1),
    WEST(1, -1, 0),
    NORTH(2, 0, -1),
    EAST(3, 1, 0);

    private final int horizontalIndex;
    private final int dx;
    private final int dz;

    CardinalFacing(int horizontalIndex, int dx, int dz) {
        this.horizontalIndex = horizontalIndex;
        this.dx = dx;
        this.dz = dz;
    }

    /** The MC horizontal facing index (south=0, west=1, north=2, east=3). */
    public int horizontalIndex() { return horizontalIndex; }

    /** Unit step in X. */
    public int dx() { return dx; }

    /** Unit step in Z. */
    public int dz() { return dz; }

    /** Get the opposite direction. */
    public CardinalFacing opposite() {
        return switch (this) {
            case SOUTH -> NORTH;
            case NORTH -> SOUTH;
            case EAST  -> WEST;
            case WEST  -> EAST;
        };
    }

    /**
     * Rotate CCW by {@code steps * 90°}.
     *
     * @param steps 0-3
     */
    public CardinalFacing rotate(int steps) {
        if (steps == 0) return this;
        // CCW in MC horizontal: south→east→north→west→south
        CardinalFacing[] order = { SOUTH, EAST, NORTH, WEST };
        int idx = java.util.Arrays.asList(order).indexOf(this);
        return order[(idx + steps) % 4];
    }

    /**
     * Convert to MC's horizontal index for rotation computation.
     * Steps = (targetHIdx - sourceHIdx + 4) % 4.
     */
    public static int rotationSteps(CardinalFacing from, CardinalFacing to) {
        return (to.horizontalIndex - from.horizontalIndex + 4) & 3;
    }

    /**
     * Best cardinal direction to face toward the given delta.
     * Prefers the axis with the larger absolute delta.
     */
    public static CardinalFacing toward(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? EAST : WEST;
        } else {
            return dz >= 0 ? SOUTH : NORTH;
        }
    }
}
