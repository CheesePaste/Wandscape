package com.wsteam.wandscape.compat.ironspellbooks;

import java.util.ArrayList;
import java.util.List;

import com.wsteam.wandscape.core.types.AttributeModifier;
import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.core.types.ModifierOperation;

import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * Iron's Spells 'n Spellbooks 装备属性 → Wandscape 属性映射。
 *
 * <p>铁魔法装备（{@code ExtendedArmorItem}）把属性加成放在 vanilla
 * {@link ItemAttributeModifiers} 里，但 NPC 的 4 盔甲格走独立 {@code armorInventory}、
 * 不挂 vanilla 装备槽，加成从不被 vanilla 属性系统结算。这里把能对到 Wandscape 属性的
 * 三条加成手动映射回 ECS {@code EquipmentComponent}：
 * <ul>
 *   <li>{@code MAX_MANA}（如流浪法师兜帽 +25 最大法力）→ {@link AttributeType#MAX_MANA}</li>
 *   <li>{@code SPELL_POWER}（如 +5% 法术强度）→ {@link AttributeType#SPELL_POWER}</li>
 *   <li>vanilla {@code MOVEMENT_SPEED}（如速度靴 +25% 移速）→ {@link AttributeType#MOVE_SPEED}</li>
 * </ul>
 * 百分比加成（{@code ADD_MULTIPLIED_BASE}）映射为 {@link ModifierOperation#MULTIPLY_BASE}，
 * 使提升随 NPC 基础值正确放大、且在基础值被重新播种（法师小屋训练/复活）后仍正确。
 *
 * <p>其余铁魔法特色属性没有 Wandscape 对应属性，一律不映射：各学派 {@code *_spell_power}
 * （某系法术增强）、{@code casting_movespeed}（施法时移速）、{@code mana_regen}、
 * {@code cooldown_reduction}、{@code cast_time_reduction}、{@code summon_damage}、
 * {@code spell_resist} 与各系抗性。
 */
public final class IronSpellsAttributes {

    private IronSpellsAttributes() {}

    /**
     * 把铁魔法装备物品堆的属性修饰符映射为 Wandscape 属性修饰符。
     * 未加载铁魔法 / 空堆 / 无映射属性时返回空列表。
     */
    public static List<AttributeModifier> modifiersFor(ItemStack stack) {
        if (!IronSpellsCompat.isLoaded() || stack == null || stack.isEmpty()) return List.of();
        List<AttributeModifier> out = new ArrayList<>(3);
        for (ItemAttributeModifiers.Entry entry : stack.getAttributeModifiers().modifiers()) {
            AttributeType type = mapType(entry.attribute());
            if (type == null) continue;
            ModifierOperation op = mapOperation(entry.modifier().operation());
            if (op == null) continue;
            out.add(new AttributeModifier(type, (float) entry.modifier().amount(), op));
        }
        return out;
    }

    /** Iron's 属性 → Wandscape 属性；无对应返回 null（跳过）。 */
    private static AttributeType mapType(Holder<Attribute> attribute) {
        if (attribute.is(AttributeRegistry.MAX_MANA)) return AttributeType.MAX_MANA;
        if (attribute.is(AttributeRegistry.SPELL_POWER)) return AttributeType.SPELL_POWER;
        if (attribute.is(Attributes.MOVEMENT_SPEED)) return AttributeType.MOVE_SPEED;
        return null;
    }

    /** MC 修饰符操作 → Wandscape 操作；不支持的（ADD_MULTIPLIED_TOTAL）返回 null（跳过）。 */
    private static ModifierOperation mapOperation(
            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation op) {
        return switch (op) {
            case ADD_VALUE -> ModifierOperation.ADDITION;
            case ADD_MULTIPLIED_BASE -> ModifierOperation.MULTIPLY_BASE;
            default -> null;
        };
    }
}
