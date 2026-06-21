package com.wsteam.wandscape.core.road;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TemplateExpanderTest {

    private static EntryExit e(int dx, int dz, CardinalFacing f) {
        return new EntryExit(dx, dz, f);
    }

    private static TemplateMeta meta(String id, int budgetCost, int weight,
                                      List<EntryExit> entries, List<EntryExit> exits) {
        return new TemplateMeta(id, "test:" + id, 3, budgetCost, weight, entries, exits);
    }

    /**
     * Build a typical road pool with straight and corner templates.
     * Both entry and exit face the road's travel direction.
     * Straight default: +Z (SOUTH), 8 tiles long.
     * Corner default: +Z then +X (entry SOUTH, exit EAST).
     */
    private static RoadTemplatePool standardPool() {
        return RoadTemplatePool.of(List.of(
                meta("straight", 8, 5,
                        List.of(e(1, 0, CardinalFacing.SOUTH)),
                        List.of(e(1, 7, CardinalFacing.SOUTH))),
                meta("corner", 8, 1,
                        List.of(e(1, 0, CardinalFacing.SOUTH)),
                        List.of(e(7, 1, CardinalFacing.EAST)))
        ));
    }

    @Test
    void expandTowardSouthUsesStraights() {
        RoadTemplatePool pool = standardPool();
        XZPoint start = new XZPoint(1, 0);
        XZPoint target = new XZPoint(1, 20); // south of start (+Z)
        Set<XZPoint> obstacles = Set.of();
        Random rng = new Random(123);

        List<TemplatePlacement> result = TemplateExpander.expand(
                start, target, 30, pool, obstacles, rng);

        assertFalse(result.isEmpty(), "Should have at least one placement heading south");
        // All placements should be "straight" (heading south, rotation 0)
        for (TemplatePlacement p : result) {
            assertEquals("straight", p.templateId());
            assertEquals(0, p.rotation(), "Straight template faces south at exit by default — rotation 0");
        }
    }

    @Test
    void expandBudgetExhaustion() {
        RoadTemplatePool pool = standardPool();
        XZPoint start = new XZPoint(1, 0);
        XZPoint target = new XZPoint(1, 100); // far away
        Set<XZPoint> obstacles = Set.of();
        Random rng = new Random(123);

        // Budget = 8 → at most 1 straight template (cost 8)
        List<TemplatePlacement> result = TemplateExpander.expand(
                start, target, 8, pool, obstacles, rng);

        assertTrue(result.size() <= 1, "Budget 8 = at most one template (cost 8)");
    }

    @Test
    void expandTargetWithinThresholdReturnsEmpty() {
        RoadTemplatePool pool = standardPool();
        XZPoint start = new XZPoint(1, 0);
        XZPoint target = new XZPoint(1, 4); // distance 4 < CLOSE_THRESHOLD (8)
        Set<XZPoint> obstacles = Set.of();
        Random rng = new Random(123);

        List<TemplatePlacement> result = TemplateExpander.expand(
                start, target, 100, pool, obstacles, rng);

        assertTrue(result.isEmpty(), "Target within threshold → no placements needed");
    }

    @Test
    void expandSkipsBlockedOrigin() {
        RoadTemplatePool pool = standardPool();
        XZPoint start = new XZPoint(1, 0);
        XZPoint target = new XZPoint(1, 20);
        Set<XZPoint> obstacles = new HashSet<>();
        obstacles.add(start); // start is blocked
        Random rng = new Random(123);

        List<TemplatePlacement> result = TemplateExpander.expand(
                start, target, 30, pool, obstacles, rng);

        // Should still produce placements (jittered position) or skip
        // Either way, shouldn't crash
        assertNotNull(result);
    }

    @Test
    void emptyPoolReturnsEmpty() {
        RoadTemplatePool pool = RoadTemplatePool.of(List.of());
        XZPoint start = new XZPoint(0, 0);
        XZPoint target = new XZPoint(10, 0);
        Random rng = new Random(123);

        List<TemplatePlacement> result = TemplateExpander.expand(
                start, target, 30, pool, Set.of(), rng);

        assertTrue(result.isEmpty());
    }

    @Test
    void placementsAdvanceTowardTarget() {
        RoadTemplatePool pool = standardPool();
        XZPoint start = new XZPoint(1, 0);
        XZPoint target = new XZPoint(1, 30);
        Set<XZPoint> obstacles = Set.of();
        Random rng = new Random(123);

        List<TemplatePlacement> result = TemplateExpander.expand(
                start, target, 40, pool, obstacles, rng);

        assertFalse(result.isEmpty());
        // The last placement's exit should be closer to target than start
        if (!result.isEmpty()) {
            TemplatePlacement last = result.get(result.size() - 1);
            int distStart = start.manhattanTo(target);
            // exit position = template origin + exit offset
            TemplateMeta tm = pool.get(last.templateId());
            assertNotNull(tm);
            EntryExit bestExit = TemplateExpander.findBestExit(tm, last.rotation(),
                    CardinalFacing.toward(target.x() - last.x(), target.z() - last.z()));
            XZPoint exitPos = new XZPoint(last.x() + bestExit.dx(), last.z() + bestExit.dz());
            int distExit = exitPos.manhattanTo(target);
            assertTrue(distExit <= distStart,
                    "Last exit " + exitPos + " should be closer to target " + target
                            + " than start " + start + " (exit dist=" + distExit + " start dist=" + distStart + ")");
        }
    }
}
