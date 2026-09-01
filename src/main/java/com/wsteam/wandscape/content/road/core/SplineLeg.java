package com.wsteam.wandscape.content.road.core;

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
     * Compute approximate length of this leg (Euclidean distance between endpoints or sampled curve).
     */
    public double getApproxLength() {
        if (spline == null) return 0;

        // Fast path for off-road (straight lines between two points)
        if (offRoad) {
            SplineVec3 startPos = spline.evaluate(uStart).position();
            SplineVec3 endPos = spline.evaluate(uEnd).position();
            return startPos.subtract(endPos).length();
        }

        // For on-road splines, sample the curve to get a better arc length estimate
        double length = 0;
        int steps = Math.max(5, (int) (Math.abs(uEnd - uStart) * 10)); // 10 samples per segment unit
        double dt = (uEnd - uStart) / steps;

        SplineVec3 lastPos = spline.evaluate(uStart).position();
        for (int i = 1; i <= steps; i++) {
            double u = uStart + i * dt;
            SplineVec3 pos = spline.evaluate(u).position();
            length += lastPos.subtract(pos).length();
            lastPos = pos;
        }
        return length;
    }
}
