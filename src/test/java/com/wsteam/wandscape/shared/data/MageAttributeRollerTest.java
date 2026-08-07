package com.wsteam.wandscape.shared.data;

import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MageAttributeRollerTest {

    @Test
    void levelOneHasNoLevelBonusAndStaysInBaseRange() {
        RecruitmentCandidate c = MageAttributeRoller.roll(1, new Random(42));
        assertEquals(1, c.level());
        assertInRange(c.maxHp(), 20f, 40f);
        assertInRange(c.maxMana(), 150f, 250f);
        assertInRange(c.spellPower(), 0.5f, 1.5f);
        assertInRange(c.workSpeed(), 0.5f, 1.5f);
        assertInRange(c.spellSpeed(), 0.5f, 1.5f);
        assertInRange(c.armorValue(), 0f, 8f);
        assertInRange(c.moveSpeed(), 0.2f, 0.4f);
    }

    @Test
    void levelBonusIsAdditiveAndExactWithSameSeed() {
        RecruitmentCandidate low = MageAttributeRoller.roll(1, new Random(7));
        RecruitmentCandidate high = MageAttributeRoller.roll(3, new Random(7));
        // Same seed → same skew draws; level 3 adds (3-1) × per-level bonus.
        assertEquals(4f, high.maxHp() - low.maxHp(), 0.001f);       // 2/级 × 2
        assertEquals(30f, high.maxMana() - low.maxMana(), 0.001f);   // 15/级 × 2
        assertEquals(0.10f, high.spellPower() - low.spellPower(), 0.011f); // 0.05/级 × 2
        assertEquals(0.10f, high.workSpeed() - low.workSpeed(), 0.011f);
        assertEquals(0.10f, high.spellSpeed() - low.spellSpeed(), 0.011f);
        assertEquals(1f, high.armorValue() - low.armorValue(), 0.001f); // 0.5/级 × 2
        assertEquals(0.04f, high.moveSpeed() - low.moveSpeed(), 0.001f);     // 0.02/级 × 2
    }

    @Test
    void subOneLevelIsClampedToBase() {
        RecruitmentCandidate a = MageAttributeRoller.roll(0, new Random(5));
        RecruitmentCandidate b = MageAttributeRoller.roll(1, new Random(5));
        assertEquals(1, a.level());
        assertEquals(a.maxHp(), b.maxHp(), 0.001f);
        assertEquals(a.spellPower(), b.spellPower(), 0.001f);
        assertEquals(a.maxMana(), b.maxMana(), 0.001f);
    }

    @Test
    void higherLevelScalesStatsAcrossSamples() {
        Random random = new Random(1234);
        for (int i = 0; i < 200; i++) {
            RecruitmentCandidate c = MageAttributeRoller.roll(10, random); // 加成 ×9
            assertInRange(c.maxHp(), 38f, 58f);
            assertInRange(c.maxMana(), 285f, 385f);
            assertInRange(c.spellPower(), 0.95f, 1.95f);
            assertInRange(c.moveSpeed(), 0.38f, 0.58f);
        }
    }

    private static void assertInRange(float value, float min, float max) {
        assertTrue(value >= min, "expected ≥ " + min + " but was " + value);
        assertTrue(value <= max, "expected ≤ " + max + " but was " + value);
    }
}
