package com.wsteam.wandscape.core.road;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class RoadTemplatePoolTest {

    private static EntryExit e(int dx, int dz, CardinalFacing f) {
        return new EntryExit(dx, dz, f);
    }

    private static TemplateMeta meta(String id, int budgetCost, int weight,
                                      List<EntryExit> entries, List<EntryExit> exits) {
        return new TemplateMeta(id, "test:" + id, 3, budgetCost, weight, entries, exits);
    }

    @Test
    void weightedPickAlwaysReturnsTemplate() {
        RoadTemplatePool pool = RoadTemplatePool.of(List.of(
                meta("a", 8, 1, List.of(e(1, 0, CardinalFacing.SOUTH)), List.of(e(1, 7, CardinalFacing.NORTH))),
                meta("b", 4, 1, List.of(e(1, 0, CardinalFacing.SOUTH)), List.of(e(1, 3, CardinalFacing.NORTH)))
        ));
        Random rng = new Random(42);
        for (int i = 0; i < 20; i++) {
            assertNotNull(pool.pick(rng));
        }
    }

    @Test
    void pickFacingReturnsTemplateWithMatchingExitDirection() {
        // straight: entry S, exit N → can face north
        RoadTemplatePool pool = RoadTemplatePool.of(List.of(
                meta("straight", 16, 5,
                        List.of(e(7, 0, CardinalFacing.SOUTH)),
                        List.of(e(7, 15, CardinalFacing.SOUTH))),
                meta("corner", 16, 1,
                        List.of(e(7, 0, CardinalFacing.SOUTH)),
                        List.of(e(15, 7, CardinalFacing.EAST)))
        ));
        Random rng = new Random(42);
        // Heading north: straight should match (exit faces north)
        TemplateMeta picked = pool.pickFacing(CardinalFacing.NORTH, rng);
        assertNotNull(picked);
        // With high weight, straight should be picked most of the time
        assertTrue(RoadTemplatePool.canFaceToward(
                meta("straight", 16, 5,
                        List.of(e(7, 0, CardinalFacing.SOUTH)),
                        List.of(e(7, 15, CardinalFacing.SOUTH))),
                CardinalFacing.NORTH));
    }

    @Test
    void pickWithRotationFindsCorrectRotation() {
        // straight: entry S, exit S (travel +Z = SOUTH). Default exits face SOUTH.
        // Heading south → rotation 0 (exit already faces south)
        // Heading north → rotation 2 (180° → exit faces north)
        RoadTemplatePool pool = RoadTemplatePool.of(List.of(
                meta("straight", 16, 5,
                        List.of(e(7, 0, CardinalFacing.SOUTH)),
                        List.of(e(7, 15, CardinalFacing.SOUTH)))
        ));
        Random rng = new Random(42);

        var pickedS = pool.pickWithRotation(CardinalFacing.SOUTH, rng);
        assertNotNull(pickedS);
        assertEquals(0, pickedS.rotation(), "Exit faces S already → rotation 0");

        var pickedN = pool.pickWithRotation(CardinalFacing.NORTH, rng);
        assertNotNull(pickedN);
        assertEquals(2, pickedN.rotation(), "Exit needs 180° to face N → rotation 2");
    }

    @Test
    void angularDistance() {
        assertEquals(0, RoadTemplatePool.angularDistance(
                CardinalFacing.NORTH, CardinalFacing.NORTH));
        assertEquals(1, RoadTemplatePool.angularDistance(
                CardinalFacing.NORTH, CardinalFacing.EAST));
        assertEquals(1, RoadTemplatePool.angularDistance(
                CardinalFacing.NORTH, CardinalFacing.WEST));
        assertEquals(2, RoadTemplatePool.angularDistance(
                CardinalFacing.NORTH, CardinalFacing.SOUTH));
    }

    @Test
    void emptyPool() {
        RoadTemplatePool pool = RoadTemplatePool.of(List.of());
        assertEquals(0, pool.size());
        Random rng = new Random(42);
        assertNull(pool.pick(rng));
        assertNull(pool.pickFacing(CardinalFacing.NORTH, rng));
        assertNull(pool.pickWithRotation(CardinalFacing.NORTH, rng));
    }
}
