package com.wsteam.wandscape.wand.internal;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WandDataValidatorTest {

    private static CompoundTag validTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("wand_color", "#FFD700");
        CompoundTag behaviors = new CompoundTag();
        behaviors.putInt("building", 3);
        tag.put("behaviors", behaviors);
        return tag;
    }

    @Test
    void isValid_completeTag_returnsTrue() {
        CompoundTag tag = validTag();
        tag.putInt("range", 3);
        tag.putFloat("mana_cost_multiplier", 0.8f);
        assertTrue(WandDataValidator.isValid(tag));
    }

    @Test
    void isValid_missingColor_returnsFalse() {
        CompoundTag tag = validTag();
        tag.remove("wand_color");
        assertFalse(WandDataValidator.isValid(tag));
    }

    @Test
    void isValid_invalidColorFormat_noHash_returnsFalse() {
        CompoundTag tag = validTag();
        tag.putString("wand_color", "FFD700");
        assertFalse(WandDataValidator.isValid(tag));
    }

    @Test
    void isValid_invalidColorFormat_tooShort_returnsFalse() {
        CompoundTag tag = validTag();
        tag.putString("wand_color", "#FFF");
        assertFalse(WandDataValidator.isValid(tag));
    }

    @Test
    void isValid_invalidColorFormat_nonHex_returnsFalse() {
        CompoundTag tag = validTag();
        tag.putString("wand_color", "#GGGGGG");
        assertFalse(WandDataValidator.isValid(tag));
    }

    @Test
    void isValid_validColorWithLowercase_returnsTrue() {
        CompoundTag tag = validTag();
        tag.putString("wand_color", "#a020f0");
        assertTrue(WandDataValidator.isValid(tag));
    }

    @Test
    void isValid_emptyBehaviors_returnsFalse() {
        CompoundTag tag = validTag();
        tag.put("behaviors", new CompoundTag());
        assertFalse(WandDataValidator.isValid(tag));
    }

    @Test
    void isValid_invalidBehaviorType_returnsFalse() {
        CompoundTag tag = validTag();
        tag.remove("behaviors");
        CompoundTag behaviors = new CompoundTag();
        behaviors.putInt("exploration", 1);
        tag.put("behaviors", behaviors);
        assertFalse(WandDataValidator.isValid(tag));
    }

    @Test
    void isValid_behaviorLevelZero_returnsFalse() {
        CompoundTag tag = validTag();
        tag.remove("behaviors");
        CompoundTag behaviors = new CompoundTag();
        behaviors.putInt("mining", 0);
        tag.put("behaviors", behaviors);
        assertFalse(WandDataValidator.isValid(tag));
    }

    @Test
    void isValid_behaviorLevelNegative_returnsFalse() {
        CompoundTag tag = validTag();
        tag.remove("behaviors");
        CompoundTag behaviors = new CompoundTag();
        behaviors.putInt("mining", -1);
        tag.put("behaviors", behaviors);
        assertFalse(WandDataValidator.isValid(tag));
    }

    @Test
    void isValid_behaviorLevelOne_returnsTrue() {
        CompoundTag tag = validTag();
        tag.remove("behaviors");
        CompoundTag behaviors = new CompoundTag();
        behaviors.putInt("ritual", 1);
        tag.put("behaviors", behaviors);
        assertTrue(WandDataValidator.isValid(tag));
    }

    @Test
    void isValid_rangeBelowOne_returnsFalse() {
        CompoundTag tag = validTag();
        tag.putInt("range", 0);
        assertFalse(WandDataValidator.isValid(tag));
    }

    @Test
    void isValid_rangeAboveFive_returnsFalse() {
        CompoundTag tag = validTag();
        tag.putInt("range", 6);
        assertFalse(WandDataValidator.isValid(tag));
    }

    @Test
    void isValid_rangeBoundaries_allValid() {
        for (int r = 1; r <= 5; r++) {
            CompoundTag tag = validTag();
            tag.putInt("range", r);
            assertTrue(WandDataValidator.isValid(tag), "range=" + r + " should be valid");
        }
    }

    @Test
    void isValid_manaMultiplierBelowPointThree_returnsFalse() {
        CompoundTag tag = validTag();
        tag.putFloat("mana_cost_multiplier", 0.29f);
        assertFalse(WandDataValidator.isValid(tag));
    }

    @Test
    void isValid_manaMultiplierAboveOne_returnsFalse() {
        CompoundTag tag = validTag();
        tag.putFloat("mana_cost_multiplier", 1.01f);
        assertFalse(WandDataValidator.isValid(tag));
    }

    @Test
    void isValid_onlyRequiredFields_returnsTrue() {
        assertTrue(WandDataValidator.isValid(validTag()));
    }
}
