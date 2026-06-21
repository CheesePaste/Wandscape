package com.wsteam.wandscape.core.road;

/**
 * An entry or exit point in a road template, defined in local coordinates
 * (relative to the template's origin at rotation 0 / NONE).
 *
 * @param dx     X offset from template origin (before rotation)
 * @param dz     Z offset from template origin (before rotation)
 * @param facing direction faced at this point (before rotation)
 */
public record EntryExit(int dx, int dz, CardinalFacing facing) {

    /**
     * Rotate this entry/exit point and its facing.
     *
     * @param steps 0=0°, 1=90° CCW, 2=180°, 3=270° CCW
     * @return a new EntryExit with rotated position and facing
     */
    public EntryExit rotate(int steps) {
        if (steps == 0) return this;
        int rdx = dx;
        int rdz = dz;
        for (int i = 0; i < (steps & 3); i++) {
            int oldDx = rdx;
            rdx = rdz;
            rdz = -oldDx;
        }
        return new EntryExit(rdx, rdz, facing.rotate(steps));
    }
}
