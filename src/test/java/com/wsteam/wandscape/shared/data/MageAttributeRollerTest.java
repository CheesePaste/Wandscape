package com.wsteam.wandscape.shared.data;

import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MageAttributeRollerTest {

    @Test
    void subOneLevelIsClampedToBase() {
        RecruitmentCandidate a = MageAttributeRoller.roll(0, new Random(5));
        RecruitmentCandidate b = MageAttributeRoller.roll(1, new Random(5));
        assertEquals(1, a.level());
        assertEquals(a.maxHp(), b.maxHp(), 0.001f);
        assertEquals(a.spellPower(), b.spellPower(), 0.001f);
        assertEquals(a.maxMana(), b.maxMana(), 0.001f);
    }
}
