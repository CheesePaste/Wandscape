package com.wsteam.wandscape.shared.data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.shared.data.ElementType;

import static org.junit.jupiter.api.Assertions.*;

class MageHutAttributesTest {

    @Test
    void definitionsCoverAllSevenAttributes() {
        assertEquals(9, AttributeType.values().length);
        assertEquals(7, MageHutAttributes.ORDER.size());
        assertFalse(MageHutAttributes.ORDER.contains(AttributeType.HEALTH_REGEN));
        assertFalse(MageHutAttributes.ORDER.contains(AttributeType.MANA_REGEN));
        for (AttributeType type : MageHutAttributes.ORDER) {
            assertTrue(MageHutAttributes.lower(type) <= MageHutAttributes.upper(type),
                    type + " lower must be ≤ upper");
            assertTrue(MageHutAttributes.perLevel(type) >= 0);
            assertTrue(MageHutAttributes.trainStep(type) > 0);
        }
    }

    @Test
    void allAttributesHaveTwentyUniformTrainSteps() {
        for (AttributeType type : MageHutAttributes.ORDER) {
            float steps = (MageHutAttributes.upper(type) - MageHutAttributes.lower(type))
                    / MageHutAttributes.trainStep(type);
            assertEquals(20f, steps, 0.01f, type + " must train in exactly 20 uniform steps");
        }
    }

    @Test
    void trainStepIndexRangesZeroToNineteen() {
        AttributeType t = AttributeType.SPELL_POWER;
        assertEquals(0, MageHutAttributes.trainStepIndex(t, MageHutAttributes.lower(t)));
        assertEquals(19, MageHutAttributes.trainStepIndex(t, MageHutAttributes.upper(t)));
        assertEquals(19, MageHutAttributes.trainStepIndex(t, MageHutAttributes.upper(t) + 1f));  // clamped
        assertEquals(0, MageHutAttributes.trainStepIndex(t, MageHutAttributes.lower(t) - 1f));   // clamped
    }

    @Test
    void trainElementsTwoDistinctPerAttribute() {
        for (AttributeType type : MageHutAttributes.ORDER) {
            List<ElementType> els = MageHutAttributes.trainElements(type);
            assertEquals(2, els.size(), type + " must use exactly 2 elements");
            assertEquals(2, els.stream().distinct().count(), type + " elements must be distinct");
        }
    }

    @Test
    void elementMappingBalancedAcrossAllSeven() {
        Map<ElementType, Integer> count = new HashMap<>();
        for (AttributeType type : MageHutAttributes.ORDER) {
            for (ElementType e : MageHutAttributes.trainElements(type)) {
                count.merge(e, 1, Integer::sum);
            }
        }
        assertEquals(7, count.size());
        for (int c : count.values()) {
            assertEquals(2, c, "each element must be used by exactly two attributes");
        }
    }

    @Test
    void canLevelUpAllowsOneAboveColony() {
        // Mage cap = colony level + 1 (a level-30 colony can promote to 31).
        assertTrue(MageHutAttributes.canLevelUp(3, 5));
        assertTrue(MageHutAttributes.canLevelUp(1, 1));   // 1 → 2 (cap 2)
        assertTrue(MageHutAttributes.canLevelUp(5, 5));   // 5 → 6 (cap 6)
        assertFalse(MageHutAttributes.canLevelUp(6, 5));  // already at cap 6
        assertFalse(MageHutAttributes.canLevelUp(6, 4));  // above cap 5
    }
}
