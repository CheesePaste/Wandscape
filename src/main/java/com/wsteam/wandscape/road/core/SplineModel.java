package com.wsteam.wandscape.road.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Core mathematical model for a 3D Cubic Bezier Spline.
 * Zero Minecraft dependency, pure Java calculation.
 */
public class SplineModel {
    private final List<SplinePoint> points = new ArrayList<>();
    private boolean closed = false;

    public SplineModel() {}

    public List<SplinePoint> getPoints() {
        return points;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    public int getSegmentsCount() {
        if (points.size() < 2) return 0;
        return closed ? points.size() : points.size() - 1;
    }

    public void addPoint(SplineVec3 pos) {
        // Automatically place control handles 2 blocks away along +X and -X by default
        SplineVec3 prev = pos.add(new SplineVec3(-2.0, 0.0, 0.0));
        SplineVec3 next = pos.add(new SplineVec3(2.0, 0.0, 0.0));
        points.add(new SplinePoint(pos, prev, next, true));
    }

    public void insertPoint(int index, SplinePoint pt) {
        if (index >= 0 && index <= points.size()) {
            points.add(index, pt);
        }
    }

    public void removePoint(int index) {
        if (index >= 0 && index < points.size()) {
            points.remove(index);
        }
    }

    public void clear() {
        points.clear();
        closed = false;
    }

    public void translateAll(SplineVec3 delta) {
        for (SplinePoint pt : points) {
            pt.translate(delta);
        }
    }

    /**
     * Evaluate position and tangent at global parameter u.
     * @param u ranges from [0, segmentsCount]
     */
    public CurveSample evaluate(double u) {
        int segCount = getSegmentsCount();
        if (segCount == 0) {
            if (points.isEmpty()) {
                return new CurveSample(SplineVec3.ZERO, new SplineVec3(1, 0, 0), u);
            }
            SplineVec3 p = points.get(0).getAnchor();
            return new CurveSample(p, new SplineVec3(1, 0, 0), u);
        }

        // Clamp parameter
        if (u < 0) u = 0;
        if (u > segCount) u = segCount;

        int segIdx = (int) Math.floor(u);
        double t = u - segIdx;

        if (segIdx >= segCount) {
            segIdx = segCount - 1;
            t = 1.0;
        }

        SplinePoint p0 = points.get(segIdx);
        SplinePoint p1 = (segIdx + 1 < points.size()) ? points.get(segIdx + 1) : points.get(0);

        SplineVec3 pt0 = p0.getAnchor();
        SplineVec3 pt1 = p0.getControlNext();
        SplineVec3 pt2 = p1.getControlPrev();
        SplineVec3 pt3 = p1.getAnchor();

        // 3D Cubic Bezier Position Formula: B(t) = (1-t)^3 * pt0 + 3*(1-t)^2*t * pt1 + 3*(1-t)*t^2 * pt2 + t^3 * pt3
        double u_t = 1.0 - t;
        double u_t2 = u_t * u_t;
        double u_t3 = u_t2 * u_t;
        double t2 = t * t;
        double t3 = t2 * t;

        SplineVec3 pos = pt0.scale(u_t3)
                .add(pt1.scale(3.0 * u_t2 * t))
                .add(pt2.scale(3.0 * u_t * t2))
                .add(pt3.scale(t3));

        // 3D Cubic Bezier Tangent Formula: B'(t) = 3*(1-t)^2*(pt1-pt0) + 6*(1-t)*t*(pt2-pt1) + 3*t^2*(pt3-pt2)
        SplineVec3 d1 = pt1.subtract(pt0);
        SplineVec3 d2 = pt2.subtract(pt1);
        SplineVec3 d3 = pt3.subtract(pt2);

        SplineVec3 tan = d1.scale(3.0 * u_t2)
                .add(d2.scale(6.0 * u_t * t))
                .add(d3.scale(3.0 * t2));

        if (tan.length() < 1e-6) {
            // Fallback to chord direction if tangent is zero
            tan = pt3.subtract(pt0);
        }

        return new CurveSample(pos, tan.normalize(), u);
    }

    /**
     * Tessellate the spline into a sequence of points separated approximately by stepDistance.
     * Uses subdivision-based sampling to trace the curve.
     */
    public List<CurveSample> tessellate(double stepDistance) {
        List<CurveSample> samples = new ArrayList<>();
        int segCount = getSegmentsCount();
        if (segCount == 0) {
            if (!points.isEmpty()) {
                samples.add(new CurveSample(points.get(0).getAnchor(), new SplineVec3(1, 0, 0), 0.0));
            }
            return samples;
        }

        CurveSample lastSample = null;

        for (int i = 0; i < segCount; i++) {
            // Sample start of segment
            if (i == 0) {
                lastSample = evaluate(0.0);
                samples.add(lastSample);
            }

            SplinePoint p0 = points.get(i);
            SplinePoint p1 = (i + 1 < points.size()) ? points.get(i + 1) : points.get(0);

            double d0 = p0.getAnchor().subtract(p0.getControlNext()).length();
            double d1 = p0.getControlNext().subtract(p1.getControlPrev()).length();
            double d2 = p1.getControlPrev().subtract(p1.getAnchor()).length();
            double approxLength = d0 + d1 + d2;
            if (approxLength < 0.1) approxLength = 0.1;

            // Compute dt to advance by a small physical fraction to ensure we don't skip over stepDistance
            double physicalStep = Math.min(0.05, stepDistance / 4.0);
            double dt = physicalStep / approxLength;
            
            // Clamp dt to reasonable bounds (between 10 and ~5000 steps per segment)
            if (dt < 0.0002) dt = 0.0002;
            if (dt > 0.1) dt = 0.1;

            for (double t = dt; t <= 1.0; t += dt) {
                double u = i + t;
                CurveSample sample = evaluate(u);
                double dist = sample.position().subtract(lastSample.position()).length();
                if (dist >= stepDistance) {
                    samples.add(sample);
                    lastSample = sample;
                }
            }
        }

        // Always make sure the end point is sampled if open
        if (!closed && !points.isEmpty()) {
            CurveSample endSample = evaluate(segCount);
            if (samples.isEmpty() || samples.get(samples.size() - 1).position().subtract(endSample.position()).length() > 0.1) {
                samples.add(endSample);
            }
        }

        return samples;
    }
}
