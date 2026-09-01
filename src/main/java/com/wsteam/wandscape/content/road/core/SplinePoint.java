package com.wsteam.wandscape.content.road.core;

/**
 * A spline control point (anchor point + 2 handle control points).
 */
public class SplinePoint {
    private SplineVec3 anchor;
    private SplineVec3 controlPrev;
    private SplineVec3 controlNext;
    private boolean locked; // Whether the two control handles are locked symmetric

    public SplinePoint(SplineVec3 anchor, SplineVec3 controlPrev, SplineVec3 controlNext, boolean locked) {
        this.anchor = anchor;
        this.controlPrev = controlPrev;
        this.controlNext = controlNext;
        this.locked = locked;
    }

    public SplineVec3 getAnchor() {
        return anchor;
    }

    public void setAnchor(SplineVec3 anchor) {
        SplineVec3 delta = anchor.subtract(this.anchor);
        this.anchor = anchor;
        // Translate handles with anchor
        this.controlPrev = this.controlPrev.add(delta);
        this.controlNext = this.controlNext.add(delta);
    }

    public SplineVec3 getControlPrev() {
        return controlPrev;
    }

    /**
     * Update the previous control handle. If locked, also update the next handle symmetrically.
     */
    public void setControlPrev(SplineVec3 controlPrev) {
        this.controlPrev = controlPrev;
        if (locked) {
            SplineVec3 handleOffset = controlPrev.subtract(anchor);
            this.controlNext = anchor.subtract(handleOffset);
        }
    }

    public SplineVec3 getControlNext() {
        return controlNext;
    }

    /**
     * Update the next control handle. If locked, also update the previous handle symmetrically.
     */
    public void setControlNext(SplineVec3 controlNext) {
        this.controlNext = controlNext;
        if (locked) {
            SplineVec3 handleOffset = controlNext.subtract(anchor);
            this.controlPrev = anchor.subtract(handleOffset);
        }
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
        if (locked) {
            // Force symmetry based on controlNext
            SplineVec3 handleOffset = controlNext.subtract(anchor);
            this.controlPrev = anchor.subtract(handleOffset);
        }
    }

    /**
     * Symmetrize the handles manually using a reference handle (prev or next).
     */
    public void forceSymmetry(boolean useNextAsRef) {
        if (useNextAsRef) {
            SplineVec3 handleOffset = controlNext.subtract(anchor);
            this.controlPrev = anchor.subtract(handleOffset);
        } else {
            SplineVec3 handleOffset = controlPrev.subtract(anchor);
            this.controlNext = anchor.subtract(handleOffset);
        }
    }

    /**
     * Translate the anchor and both handles by a 3D delta offset.
     */
    public void translate(SplineVec3 delta) {
        this.anchor = this.anchor.add(delta);
        this.controlPrev = this.controlPrev.add(delta);
        this.controlNext = this.controlNext.add(delta);
    }
}
