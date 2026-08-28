package com.wsteam.wandscape.engine.boundary;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wsteam.wandscape.shared.data.ElementType;

/**
 * Pure decision logic of {@link ProductionEligibility} — no MC runtime needed.
 */
class ProductionEligibilityTest {

    @Test
    void isElementCosting_recognizesElementConsumingBlueprintsOnly() {
        assertTrue(ProductionEligibility.isElementCosting("production:synthesize"));
        assertTrue(ProductionEligibility.isElementCosting("production:craft"));
        assertTrue(ProductionEligibility.isElementCosting("production:craft_spell"));

        assertFalse(ProductionEligibility.isElementCosting("production:decompose"));
        assertFalse(ProductionEligibility.isElementCosting("build:place_structure"));
        assertFalse(ProductionEligibility.isElementCosting("node:gather"));
        assertFalse(ProductionEligibility.isElementCosting("anything:else"));
        assertFalse(ProductionEligibility.isElementCosting(null));
    }

    @Test
    void missingElements_returnsOnlyShortElements() {
        Map<ElementType, Long> required = Map.of(ElementType.WOOD, 10L, ElementType.EARTH, 5L, ElementType.FIRE, 3L);
        Map<ElementType, Long> available = Map.of(ElementType.WOOD, 10L, ElementType.EARTH, 4L, ElementType.FIRE, 9L);

        assertEquals(List.of(ElementType.EARTH), ProductionEligibility.missingElements(required, available));
    }

    @Test
    void missingElements_emptyWhenEveryElementCovered() {
        Map<ElementType, Long> required = Map.of(ElementType.WOOD, 10L, ElementType.EARTH, 5L);
        Map<ElementType, Long> available = Map.of(ElementType.WOOD, 10L, ElementType.EARTH, 5L);

        assertTrue(ProductionEligibility.missingElements(required, available).isEmpty());
    }

    @Test
    void missingElements_treatsAbsentOrNullAvailableAsZero() {
        Map<ElementType, Long> required = Map.of(ElementType.WOOD, 10L);

        assertTrue(ProductionEligibility.missingElements(required, null).contains(ElementType.WOOD));
        assertTrue(ProductionEligibility.missingElements(required, Map.of()).contains(ElementType.WOOD));
    }

    @Test
    void missingElements_emptyRequiredReturnsEmptyRegardlessOfAvailable() {
        assertTrue(ProductionEligibility.missingElements(Map.of(), Map.of(ElementType.WOOD, 999L)).isEmpty());
        assertTrue(ProductionEligibility.missingElements(null, Map.of(ElementType.WOOD, 999L)).isEmpty());
    }

    @Test
    void isAffordable_trueOnlyWhenAllElementsCovered() {
        Map<ElementType, Long> required = Map.of(ElementType.WOOD, 10L, ElementType.EARTH, 5L);

        assertTrue(ProductionEligibility.isAffordable(required, Map.of(ElementType.WOOD, 10L, ElementType.EARTH, 5L)));
        assertTrue(ProductionEligibility.isAffordable(required, Map.of(ElementType.WOOD, 99L, ElementType.EARTH, 5L)));
        assertFalse(ProductionEligibility.isAffordable(required, Map.of(ElementType.WOOD, 9L, ElementType.EARTH, 5L)));
        assertFalse(ProductionEligibility.isAffordable(required, Map.of(ElementType.WOOD, 10L)));
        assertFalse(ProductionEligibility.isAffordable(required, null));
        assertTrue(ProductionEligibility.isAffordable(Map.of(), null));
    }
}
