package com.wsteam.wandscape.road.core;

/**
 * A spline-based leg in a transport route.
 * 
 * @param spline   The spline model this leg follows.
 * @param uStart   The parameter on the spline where this leg starts.
 * @param uEnd     The parameter on the spline where this leg ends.
 * @param offRoad  If true, this leg is off-road and the client should apply a jump arc.
 */
public record SplineLeg(SplineModel spline, double uStart, double uEnd, boolean offRoad) {
    /**
     * Compute approximate length of this leg (Euclidean distance between endpoints).
     */
    public double getApproxLength() {
        if (spline == null) return 0;
        SplineVec3 startPos = spline.evaluate(uStart).position();
        SplineVec3 endPos = spline.evaluate(uEnd).position();
        return startPos.subtract(endPos).length();
    }
}
