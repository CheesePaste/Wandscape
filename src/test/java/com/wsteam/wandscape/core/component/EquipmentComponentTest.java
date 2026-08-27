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
 * per-NPC seeding, additive modifiers, and percentage (MULTIPLY_BASE) modifiers.
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
        assertEquals(4f, eq.getAttribute(AttributeType.ARMOR_VALUE));
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

    @Test
    void multiplyBase_percentage_boostsBase() {
        // +25% 移速（Iron's 速度靴）：0.3 × 1.25 = 0.375，而非 0.3 + 0.25
        EquipmentComponent eq = new EquipmentComponent();
        eq.equip(EquipmentSlot.WAND, "iron_boots",
                List.of(new AttributeModifier(AttributeType.MOVE_SPEED, 0.25f, ModifierOperation.MULTIPLY_BASE)));
        assertEquals(0.3f * 1.25f, eq.getAttribute(AttributeType.MOVE_SPEED), 0.0001f);
    }

    @Test
    void multiplyBase_onSpellPower() {
        // +5% 法术强度（Iron's 流浪法师套）：1.0 × 1.05 = 1.05
        EquipmentComponent eq = new EquipmentComponent();
        eq.equip(EquipmentSlot.WAND, "iron_hood",
                List.of(new AttributeModifier(AttributeType.SPELL_POWER, 0.05f, ModifierOperation.MULTIPLY_BASE)));
        assertEquals(1.05f, eq.getAttribute(AttributeType.SPELL_POWER), 0.0001f);
    }

    @Test
    void addition_thenMultiplyBase_appliedInOrder() {
        // vanilla 顺序：(base + ΣADDITION) × (1 + ΣMULTIPLY_BASE) = (0.3 + 0.1) × 1.2
        EquipmentComponent eq = new EquipmentComponent();
        eq.equip(EquipmentSlot.WAND, "mixed",
                List.of(
                        new AttributeModifier(AttributeType.MOVE_SPEED, 0.1f, ModifierOperation.ADDITION),
                        new AttributeModifier(AttributeType.MOVE_SPEED, 0.2f, ModifierOperation.MULTIPLY_BASE)));
        assertEquals(0.4f * 1.2f, eq.getAttribute(AttributeType.MOVE_SPEED), 0.0001f);
    }

    @Test
    void multipleMultiplyBase_modifiers_sum() {
        // 多个百分比求和后再乘：(1 + 0.05 + 0.10)
        EquipmentComponent eq = new EquipmentComponent();
        eq.equip(EquipmentSlot.WAND, "stacked",
                List.of(
                        new AttributeModifier(AttributeType.SPELL_POWER, 0.05f, ModifierOperation.MULTIPLY_BASE),
                        new AttributeModifier(AttributeType.SPELL_POWER, 0.10f, ModifierOperation.MULTIPLY_BASE)));
        assertEquals(1f * 1.15f, eq.getAttribute(AttributeType.SPELL_POWER), 0.0001f);
    }

    @Test
    void multiplyBase_zero_isNeutral() {
        EquipmentComponent eq = new EquipmentComponent();
        eq.equip(EquipmentSlot.WAND, "zero_mult",
                List.of(new AttributeModifier(AttributeType.SPELL_POWER, 0f, ModifierOperation.MULTIPLY_BASE)));
        assertEquals(1f, eq.getAttribute(AttributeType.SPELL_POWER));
    }

    @Test
    void multiplyBase_followsReseededBase() {
        // 基础值被重新播种（法师小屋训练/复活）后百分比仍正确放大
        EquipmentComponent eq = new EquipmentComponent();
        eq.equip(EquipmentSlot.WAND, "iron_boots",
                List.of(new AttributeModifier(AttributeType.MOVE_SPEED, 0.25f, ModifierOperation.MULTIPLY_BASE)));
        eq.seedBaseValues(new NpcAttributes(30f, 0.36f, 1f, 1f, 1f, 6f, 200f));
        assertEquals(0.36f * 1.25f, eq.getAttribute(AttributeType.MOVE_SPEED), 0.0001f);
    }
}
