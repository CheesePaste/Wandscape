package com.wsteam.wandscape.compat.ironspellbooks;

import java.util.Locale;

import javax.annotation.Nullable;

import com.wsteam.wandscape.magic.data.MagicDef;
import com.wsteam.wandscape.magic.data.SpellConditions;

import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.item.Scroll;
import io.redspace.ironsspellbooks.registries.ItemRegistry;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 铁魔法辅助工具类：卷轴识别、法术等级解析、卷轴物品构造与动态 MagicDef 转换。
 */
public final class IronSpellsHelper {

    private IronSpellsHelper() {}

    /** 检查物品堆是否为铁魔法法术卷轴。 */
    public static boolean isScroll(ItemStack stack) {
        if (!IronSpellsCompat.isLoaded() || stack == null || stack.isEmpty()) return false;
        return stack.getItem() instanceof Scroll || ISpellContainer.isSpellContainer(stack);
    }

    /** 从物品堆中提取铁魔法法术 ID（如 irons_spellbooks:fireball）。 */
    @Nullable
    public static String getSpellId(ItemStack stack) {
        if (!isScroll(stack)) return null;
        ISpellContainer container = ISpellContainer.get(stack);
        if (container.isEmpty()) return null;
        SpellData spellData = container.getSpellAtIndex(0);
        return spellData != null && spellData.getSpell() != null ? spellData.getSpell().getSpellId() : null;
    }

    /** 从物品堆中提取铁魔法法术等级（默认 1）。 */
    public static int getSpellLevel(ItemStack stack) {
        if (!isScroll(stack)) return 1;
        ISpellContainer container = ISpellContainer.get(stack);
        if (container.isEmpty()) return 1;
        SpellData spellData = container.getSpellAtIndex(0);
        return spellData != null ? Math.max(1, spellData.getLevel()) : 1;
    }

    /** 创建指定法术与等级的铁魔法卷轴物品堆。 */
    public static ItemStack createScroll(String spellId, int level) {
        if (!IronSpellsCompat.isLoaded()) return ItemStack.EMPTY;
        AbstractSpell spell = SpellRegistry.getSpell(spellId);
        if (spell == null || spell == SpellRegistry.none()) return ItemStack.EMPTY;

        ItemStack scroll = new ItemStack(ItemRegistry.SCROLL.get());
        ISpellContainer.createScrollContainer(spell, Math.max(1, level), scroll);
        return scroll;
    }

    /** 是否为有效的铁魔法法术 ID。 */
    public static boolean isValidSpell(String spellId) {
        if (!IronSpellsCompat.isLoaded() || spellId == null || spellId.isBlank()) return false;
        AbstractSpell spell = SpellRegistry.getSpell(spellId);
        return spell != null && spell != SpellRegistry.none() && spell.isEnabled();
    }

    /**
     * 根据铁魔法法术与装备放入的门类，构造动态合成的 {@link MagicDef}。
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

        // 蓝耗 1:1：铁魔法蓝耗直接对等 NPC 魔力池（2026-08-26 用户要求），不再按 0.25/0.10 缩放 / 下限钳制。
        // 昂贵的铁魔法（黑洞 300、传送门 200 等）会超过 NPC 默认蓝池 200，经 CastBrain 门控自动跳过。
        int manaCost = spell.getManaCost(level);
        // 冷却：getSpellCooldown() 已返回 tick（COOLDOWN_IN_SECONDS × 20），直接用；SPELL_SPEED 在 MagicState 缩短。
        int baseCooldown = spell.getSpellCooldown() > 0 ? spell.getSpellCooldown() : 40;
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
