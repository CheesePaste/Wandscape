package com.wsteam.wandscape.content.production.internal;

import com.wsteam.wandscape.shared.data.ElementType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the max-affordability computation for production recipes.
 *
 * <p>The boundary here is "how many units can the colony afford with its element stock";
 * the previous behaviour capped at a single stack (64) and a test now pins the fact that
 * the true affordability is the limit — so the UI quantity stepper can go beyond 64.
 */
class ProductionAffordabilityTest {

    private static final ElementType FIRE = ElementType.FIRE;
    private static final ElementType WATER = ElementType.WATER;

    @Test
    void singleIngredientReturnsFloorOfStockOverCost() {
        // cost 4/unit, stock 10 → 2 units (integer floor, no rounding up)
        assertEquals(2, ProductionAffordability.computeMaxAffordable(
                Map.of(FIRE, 4L), Map.of(FIRE, 10L)));
    }

    @Test
    void multipleIngredientsTakesMinimum() {
        // fire 10/4=2, water 20/8=2 → 2
        assertEquals(2, ProductionAffordability.computeMaxAffordable(
                Map.of(FIRE, 4L, WATER, 8L), Map.of(FIRE, 10L, WATER, 20L)));
        // limiting ingredient: water only 3 → 3/8 = 0
        assertEquals(0, ProductionAffordability.computeMaxAffordable(
                Map.of(FIRE, 4L, WATER, 8L), Map.of(FIRE, 100L, WATER, 3L)));
    }

    @Test
    void zeroCostIngredientIsSkipped() {
        // cost 0 for fire → ignored; water cost 2, stock 6 → 3
        assertEquals(3, ProductionAffordability.computeMaxAffordable(
                Map.of(FIRE, 0L, WATER, 2L), Map.of(FIRE, 999L, WATER, 6L)));
    }

    @Test
    void noStockReturnsZero() {
        assertEquals(0, ProductionAffordability.computeMaxAffordable(
                Map.of(FIRE, 1L), Map.of(FIRE, 0L)));
    }

    @Test
    void affordabilityExceedsSixtyFourWhenStockAllows() {
        // Guard: no artificial per-operation cap — 500 units affordable when stock supports it.
        assertEquals(500, ProductionAffordability.computeMaxAffordable(
                Map.of(FIRE, 1L), Map.of(FIRE, 500L)));
    }

    @Test
    void emptyCostIsUnbounded() {
        assertEquals(Integer.MAX_VALUE, ProductionAffordability.computeMaxAffordable(
                Map.of(), Map.of(FIRE, 1L)));
    }
}
