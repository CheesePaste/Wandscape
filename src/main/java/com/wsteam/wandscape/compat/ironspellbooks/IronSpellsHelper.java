package com.wsteam.wandscape.compat.ironspellbooks;
import com.wsteam.wandscape.content.npc.component.MagicState;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.content.magic.data.MagicDef;
import com.wsteam.wandscape.content.magic.data.SpellConditions;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.api.spells.SpellData;
import io.redspace.ironsspellbooks.item.Scroll;
import io.redspace.ironsspellbooks.registries.ItemRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

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
     * 铁魔法魔力消耗（对等 NPC 魔力池）× {@code iron.manaCostMultiplier}。
     * 门控（{@link #getSyntheticDef} 的 MagicDef）与实扣（IronSpellsCaster）同走本方法保证一致；
     * 倍率默认 1.0 时与原裸算逐位相同（raw<=0 保持 0，不人为抬到 1）。
     */
    public static int scaledManaCost(AbstractSpell spell, int level) {
        int raw = spell.getManaCost(level);
        return raw <= 0 ? 0 : Math.max(1, (int) Math.round(raw * Config.IRON_MANA_COST_MULTIPLIER.get()));
    }

    /**
     * 铁魔法基础冷却 tick（{@code getSpellCooldown()} 已返回 tick = 秒 × 20；cd<=0 兜底 40）
     * × {@code iron.cooldownMultiplier}；SPELL_SPEED 的缩短在 MagicState 里做。默认 1.0 时与原裸算相同。
     */
    public static int scaledCooldown(AbstractSpell spell) {
        int raw = spell.getSpellCooldown() > 0 ? spell.getSpellCooldown() : 40;
        return Math.max(1, (int) Math.round(raw * Config.IRON_COOLDOWN_MULTIPLIER.get()));
    }

    /**
     * 根据铁魔法法术与装备放入的策略组，构造动态合成的 {@link MagicDef}。
     * {@code categoryName} 现为策略组名（single_target/aoe/defense/support），只决定合成 def 的
     * targetMode/conditions；合成 def 的 {@code category} 恒为 NORMAL（性质），实际策略组由
     * {@code SpellRef}（{@code CastBrain.knownSpells} 的桶循环）携带，不落在这里。
     */
    @Nullable
    public static MagicDef getSyntheticDef(String spellId, int level, String categoryName) {
        if (!isValidSpell(spellId)) return null;
        AbstractSpell spell = SpellRegistry.getSpell(spellId);
        if (spell == null) return null;

        // 蓝耗 1:1：铁魔法蓝耗直接对等 NPC 魔力池（2026-08-26 用户要求），不再按 0.25/0.10 缩放 / 下限钳制。
        // 昂贵的铁魔法（黑洞 300、传送门 200 等）会超过 NPC 默认蓝池 200，经 CastBrain 门控自动跳过。
        // 消耗/冷却乘 Config 平衡倍率（iron.manaCostMultiplier / iron.cooldownMultiplier），
        // 与 IronSpellsCaster 实扣同源（scaledManaCost/scaledCooldown），保证门控与扣费一致。
        int manaCost = scaledManaCost(spell, level);
        // 冷却：getSpellCooldown() 已返回 tick（COOLDOWN_IN_SECONDS × 20），直接用；SPELL_SPEED 在 MagicState 缩短。
        int baseCooldown = scaledCooldown(spell);
        int castTime = Math.max(0, spell.getCastTime(level));
        double range = 32.0;

        MagicDef.TargetMode targetMode;
        SpellConditions conditions;

        switch (categoryName == null ? "" : categoryName) {
            case "aoe" -> {
                targetMode = MagicDef.TargetMode.HOSTILE_NEAREST;
                // 敌数门控（群攻组 ≥ 阈值）由 CastBrain 按策略组统一判定，这里不再设 per-spell 条件
                conditions = SpellConditions.NONE;
            }
            case "defense" -> {
                targetMode = MagicDef.TargetMode.SELF;
                conditions = new SpellConditions(0.8f, null, null);
            }
            case "support" -> {
                targetMode = MagicDef.TargetMode.ALLY_LOWEST_HP;
                conditions = new SpellConditions(null, 0.8f, null);
            }
            default -> {
                // single_target 与未知组：敌对单体
                targetMode = MagicDef.TargetMode.HOSTILE_NEAREST;
                conditions = SpellConditions.NONE;
            }
        }

        return new MagicDef(
                spellId,
                MagicDef.Category.NORMAL,
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
                conditions,
                null,
                null
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

    /** 从魔法书物品堆中提取所有已铭刻的有效法术条目。未安装铁魔法或非魔法书返回空列表。 */
    public static java.util.List<com.wsteam.wandscape.content.npc.component.EquippedMagicComponent.SpellEntry> getSpellsFromSpellbook(ItemStack stack) {
        if (!IronSpellsCompat.isLoaded() || stack == null || stack.isEmpty()) return java.util.List.of();
        if (ISpellContainer.isSpellContainer(stack)) {
            ISpellContainer container = ISpellContainer.get(stack);
            if (container != null && !container.isEmpty()) {
                java.util.List<com.wsteam.wandscape.content.npc.component.EquippedMagicComponent.SpellEntry> list = new java.util.ArrayList<>();
                for (var slot : container.getActiveSpells()) {
                    if (slot != null && slot.getSpell() != null && slot.getSpell() != SpellRegistry.none()) {
                        String id = slot.getSpell().getSpellId();
                        int lvl = Math.max(1, slot.getLevel());
                        if (isValidSpell(id)) {
                            list.add(new com.wsteam.wandscape.content.npc.component.EquippedMagicComponent.SpellEntry(id, lvl));
                        }
                    }
                }
                return list;
            }
        }
        return java.util.List.of();
    }

    /** 根据铁魔法法术名称智能推导其所属策略组（single_target/aoe/defense/support）。 */
    public static String inferCategory(String spellId) {
        if (spellId == null || spellId.isBlank()) return "single_target";
        String lower = spellId.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("heal") || lower.contains("blessing") || lower.contains("cleanse")
                || lower.contains("haste") || lower.contains("charge") || lower.contains("absorb")
                || lower.contains("regen")) {
            return "support";
        }
        if (lower.contains("shield") || lower.contains("armor") || lower.contains("ward")
                || lower.contains("barrier") || lower.contains("evasion") || lower.contains("fortify")
                || lower.contains("heartstop") || lower.contains("invisibility") || lower.contains("teleport")) {
            return "defense";
        }
        if (lower.contains("breath") || lower.contains("ball") || lower.contains("meteor")
                || lower.contains("black_hole") || lower.contains("chain") || lower.contains("storm")
                || lower.contains("cone") || lower.contains("slash") || lower.contains("wave")
                || lower.contains("pillar") || lower.contains("eruption") || lower.contains("wall")
                || lower.contains("blizzard") || lower.contains("earthquake") || lower.contains("shockwave")
                || lower.contains("ring") || lower.contains("quake") || lower.contains("barrage")) {
            return "aoe";
        }
        return "single_target";
    }
}
