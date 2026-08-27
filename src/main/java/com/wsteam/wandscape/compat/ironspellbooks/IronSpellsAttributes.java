package com.wsteam.wandscape.compat.ironspellbooks;

import java.util.ArrayList;
import java.util.List;

import com.wsteam.wandscape.core.types.AttributeModifier;
import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.core.types.ModifierOperation;

import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * Iron's Spells 'n Spellbooks 装备属性 → Wandscape 属性映射。
 *
 * <p>铁魔法装备（{@code ExtendedArmorItem}）把属性加成放在 vanilla
 * {@link ItemAttributeModifiers} 里。NPC 盔甲现已存于 vanilla 装备槽，原版每 tick 装备
 * 结算（{@code detectEquipmentUpdates}）会自动应用**原版属性**（ARMOR/ARMOR_TOUGHNESS/
 * KNOCKBACK_RESISTANCE/MOVEMENT_SPEED），但铁魔法**自有属性**不在 NPC 的 AttributeMap
 * 中（{@code AttributeSupplier.createInstance} 对未注册属性返回 null → 原版静默跳过），
 * 且 Wandscape 的魔力/法强读取走自己的 core 枚举（ECS {@code EquipmentComponent}），
 * 不从原版属性实例读——所以这里只桥 Wandscape 自有属性：
 * <ul>
 *   <li>{@code MAX_MANA}（如流浪法师兜帽 +25 最大法力）→ {@link AttributeType#MAX_MANA}</li>
 *   <li>{@code SPELL_POWER}（如 +5% 法术强度）→ {@link AttributeType#SPELL_POWER}</li>
 *   <li>{@code COOLDOWN_REDUCTION}（法术冷却缩减）→ {@link AttributeType#SPELL_SPEED}</li>
 *   <li>{@code CAST_TIME_REDUCTION}（法术吟唱缩减）→ {@link AttributeType#SPELL_SPEED}</li>
 * </ul>
 * 百分比加成（{@code ADD_MULTIPLIED_BASE}）映射为 {@link ModifierOperation#MULTIPLY_BASE}，
 * 使提升随 NPC 基础值正确放大、且在基础值被重新播种（法师小屋训练/复活）后仍正确。
 *
 * <p>冷却/吟唱缩减折叠进 {@code SPELL_SPEED}：Wandscape 冷却 = 基础 ÷ SPELL_SPEED、
 * 铁魔法吟唱锁时长 = 基础 ÷ SPELL_SPEED（见 {@code IronSpellsCaster}）——两个缩减与
 * {@code SPELL_SPEED} 都是"除以速度"语义，方向一致。常见护甲幅度（+5%~15%）下与铁魔法
 * 自身公式（{@code 基础×(2−值)}）近似等价（0.10 → ÷1.10 ≈ ×0.909 vs ×0.90）。注意
 * 折叠只影响冷却与铁魔法吟唱；原生 Wandscape 法术的锁时长固定（{@code durationTicks/2}
 * 不随 SPELL_SPEED 缩放）。
 *
 * <p>{@code MOVEMENT_SPEED} 不再映射——盔甲进 vanilla 槽后原版直接结算移速加成，
 * 再映射会与 base 推送双重叠加。
 *
 * <p>其余铁魔法特色属性没有 Wandscape 对应属性，一律不映射：各学派 {@code *_spell_power}
 * （某系法术增强，折进通用 SPELL_POWER 会语义错——学派加成 buff 一切）、
 * {@code casting_movespeed}（施法时移速）、{@code mana_regen}（Wandscape 回蓝是配置驱动）、
 * {@code summon_damage}、{@code spell_resist} 与各系抗性（无受击减伤钩子）。
 */
public final class IronSpellsAttributes {

    private IronSpellsAttributes() {}

    /**
     * 把铁魔法装备物品堆的属性修饰符映射为 Wandscape 属性修饰符。
     * 未加载铁魔法 / 空堆 / 无映射属性时返回空列表。
     */
    public static List<AttributeModifier> modifiersFor(ItemStack stack) {
        if (!IronSpellsCompat.isLoaded() || stack == null || stack.isEmpty()) return List.of();
        List<AttributeModifier> out = new ArrayList<>(4);
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
        if (attribute.is(AttributeRegistry.COOLDOWN_REDUCTION)) return AttributeType.SPELL_SPEED;
        if (attribute.is(AttributeRegistry.CAST_TIME_REDUCTION)) return AttributeType.SPELL_SPEED;
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
