package com.wsteam.wandscape.core.component;

import com.wsteam.wandscape.core.types.AttributeModifier;
import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.core.types.EquipmentSlot;
import com.wsteam.wandscape.core.types.ModifierOperation;
import com.wsteam.wandscape.core.types.NpcAttributes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic tests for the attribute model: default base values,
 * per-NPC seeding, and additive-only equipment modifiers.
 */
public class EquipmentComponentTest {

    @Test
    void defaultBaseValues() {
        EquipmentComponent eq = new EquipmentComponent();
        assertEquals(40f, eq.getAttribute(AttributeType.MAX_HP));
        assertEquals(0.3f, eq.getAttribute(AttributeType.MOVE_SPEED));
        assertEquals(1f, eq.getAttribute(AttributeType.SPELL_POWER));
        assertEquals(1f, eq.getAttribute(AttributeType.WORK_SPEED));
        assertEquals(1f, eq.getAttribute(AttributeType.SPELL_SPEED));
        assertEquals(0f, eq.getAttribute(AttributeType.ARMOR_VALUE));
        assertEquals(200f, eq.getAttribute(AttributeType.MAX_MANA));
    }

    @Test
    void seedBaseValues_overridesDefaults() {
        EquipmentComponent eq = new EquipmentComponent();
        NpcAttributes attrs = new NpcAttributes(60f, 0.4f, 2f, 1.5f, 1.5f, 5f, 250f);
        eq.seedBaseValues(attrs);
        assertEquals(60f, eq.getAttribute(AttributeType.MAX_HP));
        assertEquals(0.4f, eq.getAttribute(AttributeType.MOVE_SPEED));
        assertEquals(2f, eq.getAttribute(AttributeType.SPELL_POWER));
        assertEquals(1.5f, eq.getAttribute(AttributeType.WORK_SPEED));
        assertEquals(1.5f, eq.getAttribute(AttributeType.SPELL_SPEED));
        assertEquals(5f, eq.getAttribute(AttributeType.ARMOR_VALUE));
        assertEquals(250f, eq.getAttribute(AttributeType.MAX_MANA));
    }

    @Test
    void equipDefaultWand_isNeutral() {
        EquipmentComponent eq = new EquipmentComponent();
        eq.equipDefaultWand();
        assertEquals(1f, eq.getAttribute(AttributeType.SPELL_POWER));
        assertEquals(40f, eq.getAttribute(AttributeType.MAX_HP));
    }

    @Test
    void equipmentModifier_additive() {
        EquipmentComponent eq = new EquipmentComponent();
        eq.equip(EquipmentSlot.WAND, "test_wand",
                List.of(new AttributeModifier(AttributeType.SPELL_POWER, 0.5f, ModifierOperation.ADDITION)));
        assertEquals(1.5f, eq.getAttribute(AttributeType.SPELL_POWER));
    }

    @Test
    void unequip_returnsToBase() {
        EquipmentComponent eq = new EquipmentComponent();
        eq.equip(EquipmentSlot.WAND, "test_wand",
                List.of(new AttributeModifier(AttributeType.SPELL_POWER, 0.5f, ModifierOperation.ADDITION)));
        assertEquals(1.5f, eq.getAttribute(AttributeType.SPELL_POWER));
        eq.unequip(EquipmentSlot.WAND);
        assertEquals(1f, eq.getAttribute(AttributeType.SPELL_POWER));
    }

    @Test
    void multipleAdditiveModifiers_sum() {
        EquipmentComponent eq = new EquipmentComponent();
        eq.equip(EquipmentSlot.WAND, "test_wand",
                List.of(
                        new AttributeModifier(AttributeType.WORK_SPEED, 0.3f, ModifierOperation.ADDITION),
                        new AttributeModifier(AttributeType.WORK_SPEED, 0.2f, ModifierOperation.ADDITION)));
        assertEquals(1.5f, eq.getAttribute(AttributeType.WORK_SPEED));
    }
}
