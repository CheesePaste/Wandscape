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
        assertEquals(7, AttributeType.values().length);
        assertEquals(7, MageHutAttributes.ORDER.size());
        for (AttributeType type : AttributeType.values()) {
            assertTrue(MageHutAttributes.lower(type) <= MageHutAttributes.upper(type),
                    type + " lower must be ≤ upper");
            assertTrue(MageHutAttributes.perLevel(type) >= 0);
            assertTrue(MageHutAttributes.trainStep(type) > 0);
        }
    }

    @Test
    void effectiveAddsLevelAndEquipmentOnTopOfBase() {
        // maxHp base 30, level 3 → +2/级 × 2 = +4; equip +3
        assertEquals(37f, MageHutAttributes.computeEffective(AttributeType.MAX_HP, 30f, 3, 3f), 0.001f);
        // level 1 → no level bonus
        assertEquals(33f, MageHutAttributes.computeEffective(AttributeType.MAX_HP, 30f, 1, 3f), 0.001f);
        // level below 1 is treated as 1
        assertEquals(33f, MageHutAttributes.computeEffective(AttributeType.MAX_HP, 30f, 0, 3f), 0.001f);
    }

    @Test
    void canTrainOnlyBelowUpperAndStopsAtUpper() {
        assertTrue(MageHutAttributes.canTrain(AttributeType.SPELL_POWER, 0.5f));
        assertFalse(MageHutAttributes.canTrain(AttributeType.SPELL_POWER, 1.5f));
        // Just under upper (within 1e-4 epsilon) is still untrainable
        assertFalse(MageHutAttributes.canTrain(AttributeType.SPELL_POWER, 1.5f - 0.00001f));
    }

    @Test
    void trainedValueCapsAtUpper() {
        assertEquals(0.55f, MageHutAttributes.trainedValue(AttributeType.SPELL_POWER, 0.5f), 0.001f);
        assertEquals(1.5f, MageHutAttributes.trainedValue(AttributeType.SPELL_POWER, 1.45f), 0.001f); // 1.45+0.05 → cap 1.5
        assertEquals(40f, MageHutAttributes.trainedValue(AttributeType.MAX_HP, 39.5f), 0.001f);
    }

    @Test
    void trainStepsMatchSpec() {
        assertEquals(1f, MageHutAttributes.trainStep(AttributeType.MAX_HP), 0.001f);
        assertEquals(5f, MageHutAttributes.trainStep(AttributeType.MAX_MANA), 0.001f);
        assertEquals(0.01f, MageHutAttributes.trainStep(AttributeType.MOVE_SPEED), 0.001f);
        assertEquals(0.05f, MageHutAttributes.trainStep(AttributeType.SPELL_POWER), 0.001f);
        assertEquals(0.05f, MageHutAttributes.trainStep(AttributeType.WORK_SPEED), 0.001f);
        assertEquals(0.05f, MageHutAttributes.trainStep(AttributeType.SPELL_SPEED), 0.001f);
        assertEquals(0.4f, MageHutAttributes.trainStep(AttributeType.ARMOR_VALUE), 0.001f);
    }

    @Test
    void allAttributesHaveTwentyUniformTrainSteps() {
        for (AttributeType type : AttributeType.values()) {
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
    void trainCostStartsCheapEndsExpensive() {
        AttributeType t = AttributeType.SPELL_POWER;
        long first = MageHutAttributes.trainCostPerElement(t, MageHutAttributes.lower(t));
        long last = MageHutAttributes.trainCostPerElement(t, MageHutAttributes.upper(t) - 0.01f);
        assertEquals(500L, first);
        assertTrue(last > first * 100, "last step must cost 100x+ the first step");
        // monotonic: 0.5→0.6 (two steps) is cheap, 1.4→1.5 (two steps) is expensive
        long lowBand = MageHutAttributes.trainCostPerElement(t, 0.55f)
                + MageHutAttributes.trainCostPerElement(t, 0.6f);
        long highBand = MageHutAttributes.trainCostPerElement(t, 1.4f)
                + MageHutAttributes.trainCostPerElement(t, 1.45f);
        assertTrue(highBand > lowBand * 30, "1.4→1.5 must cost far more than 0.5→0.6");
    }

    @Test
    void trainElementsTwoDistinctPerAttribute() {
        for (AttributeType type : AttributeType.values()) {
            List<ElementType> els = MageHutAttributes.trainElements(type);
            assertEquals(2, els.size(), type + " must use exactly 2 elements");
            assertEquals(2, els.stream().distinct().count(), type + " elements must be distinct");
        }
    }

    @Test
    void elementMappingBalancedAcrossAllSeven() {
        Map<ElementType, Integer> count = new HashMap<>();
        for (AttributeType type : AttributeType.values()) {
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
    void upgradeCostScalesWithLevelAcrossAllSeven() {
        assertEquals(300L, MageHutAttributes.upgradeCostPerElement(1));   // 150 × (1+1)
        assertEquals(1500L, MageHutAttributes.upgradeCostPerElement(9));  // 150 × 10
        assertTrue(MageHutAttributes.upgradeCostPerElement(15) > MageHutAttributes.upgradeCostPerElement(5));
        assertEquals(7, MageHutAttributes.upgradeElements().size());
        assertEquals(7, MageHutAttributes.upgradeElements().stream().distinct().count());
    }

    @Test
    void baseFromFlatRemovesBakedLevelBonus() {
        // maxHp flat 34 at level 3 → base = 34 − 2/级 × 2 = 30
        assertEquals(30f, MageHutAttributes.baseFromFlat(AttributeType.MAX_HP, 34f, 3), 0.001f);
        // level 1 → base = flat
        assertEquals(34f, MageHutAttributes.baseFromFlat(AttributeType.MAX_HP, 34f, 1), 0.001f);
    }

    @Test
    void canLevelUpRespectsColonyCap() {
        assertTrue(MageHutAttributes.canLevelUp(3, 5));
        assertTrue(MageHutAttributes.canLevelUp(1, 1) == false);
        assertFalse(MageHutAttributes.canLevelUp(5, 5));
        assertFalse(MageHutAttributes.canLevelUp(6, 4));
    }

    @Test
    void residentStoresBaseByAttributeOrder() {
        float[] base = new float[AttributeType.values().length];
        for (AttributeType type : AttributeType.values()) {
            base[type.ordinal()] = MageHutAttributes.lower(type);
        }
        MageHutResident resident = new MageHutResident(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "Merin", 3, base);

        assertEquals(20f, resident.base(AttributeType.MAX_HP), 0.001f);
        assertEquals(0.2f, resident.base(AttributeType.MOVE_SPEED), 0.001f);

        MageHutResident upgraded = resident.withLevel(4);
        assertEquals(4, upgraded.level());
        assertEquals(3, resident.level()); // original untouched, record is immutable

        MageHutResident trained = resident.withBase(AttributeType.MAX_HP, 30f);
        assertEquals(30f, trained.base(AttributeType.MAX_HP), 0.001f);
        assertEquals(20f, resident.base(AttributeType.MAX_HP), 0.001f); // original untouched
    }
}
