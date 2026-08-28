package com.wsteam.wandscape.compat.ironspellbooks;

import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.core.types.ModifierOperation;

import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * IronSpellsAttributes 属性映射决策表契约：铁魔法注册名 → Wandscape 属性类型、
 * MC 修饰符操作 → Wandscape 操作。守护「铁魔法装备/饰品加成桥进 NPC 自有属性」的路由，
 * 只守路由不守数值（加成数值是平衡表，由物品/MC 配置管，不钉死在这里）。
 */
class IronSpellsAttributesTest {

    @Test
    void mapsIronAttributeNamesToWandscapeTypes() {
        assertEquals(AttributeType.MAX_MANA,
                IronSpellsAttributes.mapType("irons_spellbooks:max_mana"));
        assertEquals(AttributeType.SPELL_POWER,
                IronSpellsAttributes.mapType("irons_spellbooks:spell_power"));
        assertEquals(AttributeType.SPELL_SPEED,
                IronSpellsAttributes.mapType("irons_spellbooks:cooldown_reduction"));
        assertEquals(AttributeType.SPELL_SPEED,
                IronSpellsAttributes.mapType("irons_spellbooks:cast_time_reduction"));
        assertEquals(AttributeType.MANA_REGEN,
                IronSpellsAttributes.mapType("irons_spellbooks:mana_regen"));
    }

    @Test
    void unmappedAttributesAreSkipped() {
        assertNull(IronSpellsAttributes.mapType("irons_spellbooks:spell_resist"));
        assertNull(IronSpellsAttributes.mapType("irons_spellbooks:fire_spell_power"));
        assertNull(IronSpellsAttributes.mapType("minecraft:max_health"));
        assertNull(IronSpellsAttributes.mapType(""));
    }

    @Test
    void mapsModifierOperations() {
        assertEquals(ModifierOperation.ADDITION,
                IronSpellsAttributes.mapOperation(AttributeModifier.Operation.ADD_VALUE));
        assertEquals(ModifierOperation.MULTIPLY_BASE,
                IronSpellsAttributes.mapOperation(AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        assertNull(IronSpellsAttributes.mapOperation(AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }
}