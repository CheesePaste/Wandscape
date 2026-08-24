package com.wsteam.wandscape.shared.data;

import org.junit.jupiter.api.Test;

import com.wsteam.wandscape.core.types.AttributeType;

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
        assertEquals(0.6f, MageHutAttributes.trainedValue(AttributeType.SPELL_POWER, 0.5f), 0.001f);
        assertEquals(1.5f, MageHutAttributes.trainedValue(AttributeType.SPELL_POWER, 1.45f), 0.001f); // 1.55 → cap 1.5
        assertEquals(40f, MageHutAttributes.trainedValue(AttributeType.MAX_HP, 39.5f), 0.001f);
    }

    @Test
    void trainStepsMatchSpec() {
        assertEquals(2f, MageHutAttributes.trainStep(AttributeType.MAX_HP), 0.001f);
        assertEquals(5f, MageHutAttributes.trainStep(AttributeType.MAX_MANA), 0.001f);
        assertEquals(0.02f, MageHutAttributes.trainStep(AttributeType.MOVE_SPEED), 0.001f);
        assertEquals(0.1f, MageHutAttributes.trainStep(AttributeType.SPELL_POWER), 0.001f);
        assertEquals(0.1f, MageHutAttributes.trainStep(AttributeType.WORK_SPEED), 0.001f);
        assertEquals(0.1f, MageHutAttributes.trainStep(AttributeType.SPELL_SPEED), 0.001f);
        assertEquals(0.5f, MageHutAttributes.trainStep(AttributeType.ARMOR_VALUE), 0.001f);
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
