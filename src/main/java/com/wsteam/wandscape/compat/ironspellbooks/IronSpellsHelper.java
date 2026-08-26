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
     * 适配铁魔法冷却时间（秒 → tick）：
     * 铁魔法针对玩家多槽轮转设计（终极大招 CD 长达 120s~180s）；
     * NPC 战斗节奏对齐 Wandscape 动态战斗体系（2s ~ 10s 循环），按 0.08 比例换算并封顶 200 tick (10s)。
     */
    public static int getAdaptedCooldown(AbstractSpell spell) {
        int rawCdSeconds = spell.getSpellCooldown();
        if (rawCdSeconds <= 0) return 40; // 默认 2s
        int ticks = (int) Math.round(rawCdSeconds * 20.0 * 0.08);
        return Math.max(20, Math.min(200, ticks));
    }

    /**
     * 适配铁魔法法力消耗：
     * 铁魔法玩家法力池（500~1500）约为 NPC（100~200）的 5~10 倍；
     * 对齐 Wandscape 原生蓝耗区间（5 ~ 30 点），终极法术（原 300 蓝）换算为 25~30 蓝。
     */
    public static int getAdaptedManaCost(AbstractSpell spell, int level) {
        int rawMana = spell.getManaCost(level);
        if (rawMana <= 0) return 5;
        int mana = (int) Math.round(rawMana * 0.10);
        return Math.max(5, Math.min(35, mana));
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

        int manaCost = getAdaptedManaCost(spell, level);
        int baseCooldown = getAdaptedCooldown(spell);
        int castTime = Math.max(0, spell.getCastTime(level));
        double range = 32.0;

        MagicDef.TargetMode targetMode;
        SpellConditions conditions;

        switch (cat) {
            case AOE -> {
                targetMode = MagicDef.TargetMode.HOSTILE_NEAREST;
                conditions = new SpellConditions(1, null, null, null);
            }
            case DEFENSE -> {
                targetMode = MagicDef.TargetMode.SELF;
                conditions = new SpellConditions(0, 0.8f, null, null);
            }
            case SUPPORT -> {
                targetMode = MagicDef.TargetMode.ALLY_LOWEST_HP;
                conditions = new SpellConditions(0, null, 0.8f, null);
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
