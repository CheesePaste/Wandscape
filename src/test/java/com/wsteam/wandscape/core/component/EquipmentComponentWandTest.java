package com.wsteam.wandscape.core.component;

import java.util.List;

import com.wsteam.wandscape.core.types.AttributeModifier;
import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.core.types.EquipmentSlot;
import com.wsteam.wandscape.core.types.ModifierOperation;
import com.wsteam.wandscape.core.types.NpcAttributes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 法杖预设属性应用到 NPC 装备槽的守卫测试：装备 trade-off 法杖（含负加成）后
 * 有效属性 = base + Σ 修饰符；卸下恢复 base。
 */
class EquipmentComponentWandTest {

    /** Lv20 中位数法师（MageHutAttributes 曲线 + perLevel 加成）。 */
    private static final NpcAttributes LV20 = new NpcAttributes(
            68f, 0.68f, 1.95f, 1.95f, 1.95f, 13.5f, 485f);

    @Test
    void equipTradeoffWand_appliesNegativeModifiers() {
        EquipmentComponent eq = new EquipmentComponent();
        eq.seedBaseValues(LV20);
        eq.equip(EquipmentSlot.WAND, "bastion_wand", List.of(
                new AttributeModifier(AttributeType.MOVE_SPEED, -0.18f, ModifierOperation.ADDITION),
                new AttributeModifier(AttributeType.MAX_HP, 55f, ModifierOperation.ADDITION),
                new AttributeModifier(AttributeType.ARMOR_VALUE, 8f, ModifierOperation.ADDITION)));
        assertEquals(0.50f, eq.getAttribute(AttributeType.MOVE_SPEED), 0.001f);
        assertEquals(123f, eq.getAttribute(AttributeType.MAX_HP), 0.001f);
        assertEquals(21.5f, eq.getAttribute(AttributeType.ARMOR_VALUE), 0.001f);
        // 未受影响的属性保持 base
        assertEquals(485f, eq.getAttribute(AttributeType.MAX_MANA), 0.001f);
        assertEquals(1.95f, eq.getAttribute(AttributeType.SPELL_POWER), 0.001f);
    }

    @Test
    void equipGlassCannon_lowersDefenseRaisesSpell() {
        EquipmentComponent eq = new EquipmentComponent();
        eq.seedBaseValues(LV20);
        eq.equip(EquipmentSlot.WAND, "oblivion_wand", List.of(
                new AttributeModifier(AttributeType.MAX_HP, -40f, ModifierOperation.ADDITION),
                new AttributeModifier(AttributeType.ARMOR_VALUE, -5f, ModifierOperation.ADDITION),
                new AttributeModifier(AttributeType.SPELL_POWER, 2f, ModifierOperation.ADDITION)));
        assertEquals(28f, eq.getAttribute(AttributeType.MAX_HP), 0.001f);
        assertEquals(8.5f, eq.getAttribute(AttributeType.ARMOR_VALUE), 0.001f);
        assertEquals(3.95f, eq.getAttribute(AttributeType.SPELL_POWER), 0.001f);
    }

    @Test
    void unequipWand_restoresBase() {
        EquipmentComponent eq = new EquipmentComponent();
        eq.seedBaseValues(LV20);
        eq.equip(EquipmentSlot.WAND, "carpenter_wand", List.of(
                new AttributeModifier(AttributeType.WORK_SPEED, 0.4f, ModifierOperation.ADDITION)));
        assertEquals(2.35f, eq.getAttribute(AttributeType.WORK_SPEED), 0.001f);
        eq.unequip(EquipmentSlot.WAND);
        assertEquals(1.95f, eq.getAttribute(AttributeType.WORK_SPEED), 0.001f);
    }

    @Test
    void defaultWandModifiers_areNeutral() {
        EquipmentComponent eq = new EquipmentComponent();
        eq.seedBaseValues(LV20);
        eq.equip(EquipmentSlot.WAND, EquipmentComponent.DEFAULT_WAND_PRESET_ID,
                EquipmentComponent.DEFAULT_WAND_MODIFIERS);
        for (AttributeType type : AttributeType.values()) {
            assertEquals(LV20Value(type), eq.getAttribute(type), 0.001f, "attribute " + type);
        }
    }

    private static float LV20Value(AttributeType type) {
        return switch (type) {
            case MAX_HP -> 68f;
            case MOVE_SPEED -> 0.68f;
            case SPELL_POWER -> 1.95f;
            case WORK_SPEED -> 1.95f;
            case SPELL_SPEED -> 1.95f;
            case ARMOR_VALUE -> 13.5f;
            case MAX_MANA -> 485f;
        };
    }
}
