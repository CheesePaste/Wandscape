package com.wsteam.wandscape.compat.ironspellbooks;

import java.util.Locale;

import javax.annotation.Nullable;

import com.wsteam.wandscape.magic.data.MagicDef;
import com.wsteam.wandscape.magic.data.SpellConditions;

import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.item.Scroll;
import io.redspace.ironsspellbooks.registries.ItemRegistry;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Iron's Spells 'n Spellbooks 辅助桥接类（物品识别/元数据转换/合成 MagicDef）。
 */
public final class IronSpellsHelper {

    private IronSpellsHelper() {}

    /** 检查物品堆是否为铁魔法法术卷轴。 */
    public static boolean isScroll(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.getItem() instanceof Scroll || ISpellContainer.isSpellContainer(stack);
    }

    /** 从卷轴提取法术数据（SpellData）；若非法或空返回 null。 */
    @Nullable
    public static SpellData getSpellData(ItemStack stack) {
        if (!isScroll(stack)) return null;
        ISpellContainer container = ISpellContainer.get(stack);
        if (container == null || container.isEmpty()) return null;
        return container.getSpellAtIndex(0);
    }

    /** 从卷轴提取法术 ID（如 "irons_spellbooks:firebolt"）。 */
    @Nullable
    public static String getSpellId(ItemStack stack) {
        SpellData data = getSpellData(stack);
        return data != null && data.getSpell() != null ? data.getSpell().getSpellId() : null;
    }

    /** 从卷轴提取法术等级（默认为 1）。 */
    public static int getSpellLevel(ItemStack stack) {
        SpellData data = getSpellData(stack);
        return data != null ? data.getLevel() : 1;
    }

    /** 根据法术 ID 与等级构造一个铁魔法卷轴物品堆。 */
    public static ItemStack createScroll(String spellId, int level) {
        if (!IronSpellsCompat.isLoaded()) return ItemStack.EMPTY;
        AbstractSpell spell = SpellRegistry.getSpell(spellId);
        if (spell == null || spell == SpellRegistry.none()) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(ItemRegistry.SCROLL.get());
        ISpellContainer.createScrollContainer(spell, Math.max(1, level), stack);
        return stack;
    }

    /** 是否为有效的铁魔法法术 ID。 */
    public static boolean isValidSpell(String spellId) {
        if (!IronSpellsCompat.isLoaded() || spellId == null || spellId.isBlank()) return false;
        AbstractSpell spell = SpellRegistry.getSpell(spellId);
        return spell != null && spell != SpellRegistry.none() && spell.isEnabled();
    }

    /**
     * 根据铁魔法法术与装备放入的门类，构造动态合成的 {@link MagicDef}。
     *
     * <p>大类语义规则：
     * <ul>
     *   <li>single_target: 目标 hostile_nearest，无条件触发。</li>
     *   <li>aoe: 目标 hostile_nearest，敌数 ≥ 2 触发。</li>
     *   <li>defense: 目标 self，自身血量 &lt; 80% 触发。</li>
     *   <li>support: 目标 ally_lowest_hp，友方血量 &lt; 80% 触发。</li>
     * </ul>
     */
    @Nullable
    public static MagicDef getSyntheticDef(String spellId, int level, String categoryName) {
        if (!isValidSpell(spellId)) return null;
        AbstractSpell spell = SpellRegistry.getSpell(spellId);
        if (spell == null) return null;

        MagicDef.Category cat;
        try {
            cat = MagicDef.Category.valueOf(categoryName.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            cat = MagicDef.Category.SINGLE_TARGET;
        }

        // 蓝耗 1:1：铁魔法蓝耗直接对等 NPC 魔力池（2026-08-26 用户要求），不再按 0.25 缩放 / 下限 5。
        // 昂贵的铁魔法（黑洞 300、传送门 200 等）会超过 NPC 默认蓝池 200，经 CastBrain 门控自动跳过。
        int manaCost = spell.getManaCost(level);
        int baseCooldown = spell.getSpellCooldown() > 0 ? (int) Math.round(spell.getSpellCooldown() * 20.0) : 40;
        int castTime = Math.max(0, spell.getCastTime(level));
        double range = 32.0;

        MagicDef.TargetMode targetMode;
        SpellConditions conditions;

        switch (cat) {
            case AOE -> {
                targetMode = MagicDef.TargetMode.HOSTILE_NEAREST;
                // 敌数门控（群发 ≥ 阈值）由 CastBrain 按类别统一判定，这里不再设 per-spell 条件
                conditions = SpellConditions.NONE;
            }
            case DEFENSE -> {
                targetMode = MagicDef.TargetMode.SELF;
                conditions = new SpellConditions(0.8f, null, null);
            }
            case SUPPORT -> {
                targetMode = MagicDef.TargetMode.ALLY_LOWEST_HP;
                conditions = new SpellConditions(null, 0.8f, null);
            }
            case SINGLE_TARGET -> {
                targetMode = MagicDef.TargetMode.HOSTILE_NEAREST;
                conditions = SpellConditions.NONE;
            }
            default -> {
                targetMode = MagicDef.TargetMode.HOSTILE_NEAREST;
                conditions = SpellConditions.NONE;
            }
        }

        return new MagicDef(
                spellId,
                cat,
                manaCost,
                baseCooldown,
                castTime,
                range,
                targetMode,
                null,
                null,
                null,
                false,
                0,
                0,
                conditions
        );
    }

    /** 获取法术的显示名称。 */
    public static Component getSpellDisplayName(String spellId, int level) {
        if (!isValidSpell(spellId)) return Component.literal(spellId);
        AbstractSpell spell = SpellRegistry.getSpell(spellId);
        if (spell == null) return Component.literal(spellId);
        Component baseName = spell.getDisplayName(null);
        return level > 1 ? Component.translatable("%s Lv.%s", baseName, level) : baseName;
    }
}
