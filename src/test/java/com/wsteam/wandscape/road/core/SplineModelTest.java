package com.wsteam.wandscape.road.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

public class SplineModelTest {

    @Test
    public void testSplineVec3Math() {
        SplineVec3 v1 = new SplineVec3(1.0, 2.0, 3.0);
        SplineVec3 v2 = new SplineVec3(4.0, 5.0, 6.0);

        assertEquals(new SplineVec3(5.0, 7.0, 9.0), v1.add(v2));
        assertEquals(new SplineVec3(-3.0, -3.0, -3.0), v1.subtract(v2));
        assertEquals(new SplineVec3(2.0, 4.0, 6.0), v1.scale(2.0));
        assertEquals(Math.sqrt(1.0 + 4.0 + 9.0), v1.length(), 1e-9);

        SplineVec3 norm = new SplineVec3(3.0, 0.0, 0.0).normalize();
        assertEquals(1.0, norm.x(), 1e-9);
        assertEquals(0.0, norm.y(), 1e-9);
        assertEquals(0.0, norm.z(), 1e-9);
    }

    @Test
    public void testSplinePointSymmetry() {
        SplineVec3 anchor = new SplineVec3(10.0, 10.0, 10.0);
        SplineVec3 prev = new SplineVec3(8.0, 10.0, 10.0);
        SplineVec3 next = new SplineVec3(12.0, 10.0, 10.0);

        SplinePoint pt = new SplinePoint(anchor, prev, next, true);
        assertTrue(pt.isLocked());

        // 1. Move Prev handle -> Next should move symmetrically
        pt.setControlPrev(new SplineVec3(7.0, 10.0, 10.0));
        assertEquals(new SplineVec3(13.0, 10.0, 10.0), pt.getControlNext());

        // 2. Unlock -> Move Next handle -> Prev should remain unchanged
        pt.setLocked(false);
        pt.setControlNext(new SplineVec3(15.0, 10.0, 10.0));
        assertEquals(new SplineVec3(7.0, 10.0, 10.0), pt.getControlPrev());

        // 3. Re-lock -> should snap Prev handle symmetrically based on Next
        pt.setLocked(true);
        assertEquals(new SplineVec3(5.0, 10.0, 10.0), pt.getControlPrev());
    }

    @Test
    public void testSplineModelEvaluationAndTessellation() {
        SplineModel model = new SplineModel();
        model.addPoint(new SplineVec3(0.0, 0.0, 0.0));
        model.addPoint(new SplineVec3(10.0, 0.0, 0.0));
        model.addPoint(new SplineVec3(20.0, 0.0, 10.0));

        assertFalse(model.isClosed());
        assertEquals(2, model.getSegmentsCount());

        // Evaluate start, middle, and end
        CurveSample start = model.evaluate(0.0);
        assertEquals(0.0, start.position().x(), 1e-9);
        assertEquals(0.0, start.position().y(), 1e-9);
        assertEquals(0.0, start.position().z(), 1e-9);
        assertTrue(start.tangent().length() > 0.99 && start.tangent().length() < 1.01);

        CurveSample end = model.evaluate(2.0);
        assertEquals(20.0, end.position().x(), 1e-9);
        assertEquals(0.0, end.position().y(), 1e-9);
        assertEquals(10.0, end.position().z(), 1e-9);

        // Test Tessellation
        List<CurveSample> samples = model.tessellate(1.0);
        assertTrue(samples.size() >= 2);

        // The first and last sample positions should match the endpoints
        assertEquals(0.0, samples.get(0).position().x(), 0.1);
        assertEquals(20.0, samples.get(samples.size() - 1).position().x(), 0.1);

        // Distance between consecutive samples should be roughly >= 1.0 (except possibly final snap)
        for (int i = 0; i < samples.size() - 2; i++) {
            double d = samples.get(i + 1).position().subtract(samples.get(i).position()).length();
            assertTrue(d >= 0.8, "Subdivision steps should be reasonably close to target step distance");
        }
    }
}
