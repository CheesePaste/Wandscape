package com.wsteam.wandscape.compat.ironspellbooks;
import com.wsteam.wandscape.content.npc.types.NpcAttributeModifier;
import com.wsteam.wandscape.content.task.ecs.World;

import com.google.common.collect.Multimap;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import com.wsteam.wandscape.content.npc.types.ModifierOperation;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Iron's Spells 'n Spellbooks 装备属性 → Wandscape 属性映射。
 *
 * <p>铁魔法装备（{@code ExtendedArmorItem}）把属性加成放在 vanilla
 * {@link ItemAttributeModifiers} 里。NPC 盔甲现已存于 vanilla 装备槽，原版每 tick 装备
 * 结算（{@code detectEquipmentUpdates}）会自动应用**原版属性**（ARMOR/ARMOR_TOUGHNESS/
 * KNOCKBACK_RESISTANCE/MOVEMENT_SPEED），但铁魔法**自有属性**不在 NPC 的 AttributeMap
 * 中（{@code AttributeSupplier.createInstance} 对未注册属性返回 null → 原版静默跳过），
 * 且 Wandscape 的魔力/法强读取走自己的 core 枚举（ECS {@code EquipmentComponent}），
 * 不从原版属性实例读——所以这里只桥 Wandscape 自有属性。
 *
 * <p>2026-09-02 起**只桥资源类属性**：
 * <ul>
 *   <li>{@code MAX_MANA}（如流浪法师兜帽 +25 最大法力）→ {@link AttributeType#MAX_MANA}</li>
 *   <li>{@code MANA_REGEN}（魔力恢复倍率）→ {@link AttributeType#MANA_REGEN}</li>
 * </ul>
 * 这两项是装备对 NPC 资源池的合理投入——NPC 用铁魔法/原生施法都消耗同一魔力池，铁魔法加蓝/回蓝装备
 * 应当生效。
 *
 * <p>**伤害/节奏类属性一律不桥**：{@code SPELL_POWER}（法术强度）、{@code COOLDOWN_REDUCTION}
 * （冷却缩减）、{@code CAST_TIME_REDUCTION}（吟唱缩减）**不再**映射为
 * {@link AttributeType#SPELL_POWER}/{@link AttributeType#SPELL_SPEED}。原因：铁魔法库内部
 * 已按施法者（NPC）的 iron 属性结算法术强度——若再把 iron spell_power 桥进我们的 SPELL_POWER，
 * 铁魔法法伤 = iron原生 × (我们 base + 桥进的iron) 会把同一份铁魔法加成算两次（伤害按强度成方增长）；
 * 冷却/吟唱同理会经 SPELL_SPEED 泄漏进我们法术与铁魔法冷却。独立结算规则后，铁魔法法术吃铁魔法
 * 自身属性，我们法术只吃我们自有属性，互不放大。
 *
 * <p>百分比加成（{@code ADD_MULTIPLIED_BASE}）映射为 {@link ModifierOperation#MULTIPLY_BASE}，
 * 使提升随 NPC 基础值正确放大、且在基础值被重新播种（法师小屋训练/复活）后仍正确。
 *
 * <p>{@code MOVEMENT_SPEED} 不再映射——盔甲进 vanilla 槽后原版直接结算移速加成，
 * 再映射会与 base 推送双重叠加。
 *
 * <p>其余铁魔法特色属性没有 Wandscape 对应属性，一律不映射：各学派 {@code *_spell_power}
 * （某系法术增强，折进通用 SPELL_POWER 会语义错——学派加成 buff 一切）、
 * {@code casting_movespeed}（施法时移速）、{@code summon_damage}、{@code spell_resist}
 * 与各系抗性（无受击减伤钩子）。
 */
public final class IronSpellsAttributes {

    private IronSpellsAttributes() {}

    /**
     * 把铁魔法装备物品堆的属性修饰符映射为 Wandscape 属性修饰符。
     * 未加载铁魔法 / 空堆 / 无映射属性时返回空列表。
     */
    public static List<NpcAttributeModifier> modifiersFor(ItemStack stack) {
        if (!IronSpellsCompat.isLoaded() || stack == null || stack.isEmpty()) return List.of();
        List<NpcAttributeModifier> out = new ArrayList<>(4);
        for (ItemAttributeModifiers.Entry entry : stack.getAttributeModifiers().modifiers()) {
            AttributeType type = mapType(entry.attribute());
            if (type == null) continue;
            ModifierOperation op = mapOperation(entry.modifier().operation());
            if (op == null) continue;
            out.add(new NpcAttributeModifier(type, (float) entry.modifier().amount(), op));
        }
        return out;
    }

    /**
     * 把 Curios API 收集到的饰品属性修饰符映射为 Wandscape 属性修饰符。
     * 铁魔法饰品（{@code CurioBaseItem}，如 +100 法力戒指）不走原版 {@link ItemAttributeModifiers}，
     * 属性在 Curios API 的 {@code ICurioItem.getAttributeModifiers(SlotContext, id, stack)} 中声明；
     * Curios 应用属性时要求目标属性注册在穿戴者 AttributeMap（否则静默跳过），而 NPC 属性表没有
     * {@code irons_spellbooks:*} 属性——故由 {@code CuriosCompat#syncIronCurioAttributes} 在此桥进
     * Wandscape 自有属性。未加载铁魔法 / 空表 / 无映射属性时返回空列表。
     */
    public static List<NpcAttributeModifier> modifiersForCurio(
            Multimap<Holder<Attribute>, net.minecraft.world.entity.ai.attributes.AttributeModifier> map) {
        if (!IronSpellsCompat.isLoaded() || map == null || map.isEmpty()) return List.of();
        List<NpcAttributeModifier> out = new ArrayList<>(2);
        for (Map.Entry<Holder<Attribute>, net.minecraft.world.entity.ai.attributes.AttributeModifier> entry : map.entries()) {
            AttributeType type = mapType(entry.getKey());
            if (type == null) continue;
            ModifierOperation op = mapOperation(entry.getValue().operation());
            if (op == null) continue;
            out.add(new NpcAttributeModifier(type, (float) entry.getValue().amount(), op));
        }
        return out;
    }

    /** Iron's 属性 → Wandscape 属性；无对应返回 null（跳过）。 */
    private static AttributeType mapType(Holder<Attribute> attribute) {
        return mapType(attribute.getRegisteredName());
    }

    /** 按属性注册名映射（纯字符串决策表，方便单测）。未注册 / 不相关属性返回 null。 */
    static AttributeType mapType(String registeredName) {
        return switch (registeredName) {
            case "irons_spellbooks:max_mana" -> AttributeType.MAX_MANA;
            case "irons_spellbooks:mana_regen" -> AttributeType.MANA_REGEN;
            // spell_power/cooldown_reduction/cast_time_reduction 故意不映射：铁魔法库内部已按施法者
            // iron 属性结算，再桥进我们的 SPELL_POWER/SPELL_SPEED 会把铁魔法加成算两次（见类级 Javadoc）。
            default -> null;
        };
    }

    /** MC 修饰符操作 → Wandscape 操作；不支持的（ADD_MULTIPLIED_TOTAL）返回 null（跳过）。 */
    static ModifierOperation mapOperation(
            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation op) {
        return switch (op) {
            case ADD_VALUE -> ModifierOperation.ADDITION;
            case ADD_MULTIPLIED_BASE -> ModifierOperation.MULTIPLY_BASE;
            default -> null;
        };
    }
}
