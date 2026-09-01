package com.wsteam.wandscape.magic.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import com.wsteam.wandscape.content.magic.internal.CastBrain;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;
import com.wsteam.wandscape.core.component.CastStrategyComponent;
import com.wsteam.wandscape.content.magic.data.MagicDef;
import com.wsteam.wandscape.content.magic.data.SpellRef;
import com.wsteam.wandscape.content.magic.data.WorldSnapshot;

class CastBrainTest {

    /** 构造法术引用（target_mode 版）：组默认 single_target，多数目标规则测试不在乎组。 */
    private static SpellRef spell(String id, String targetMode) {
        return new SpellRef(MagicDef.fromJson(id, JsonParser.parseString(
                "{\"id\": \"" + id + "\", \"target_mode\": \"" + targetMode + "\"}")), "single_target");
    }

    /** 构造法术引用（带组版）：第二个参数为**策略组**（single_target/aoe/defense/support）。 */
    private static SpellRef spell(String id, String group, String targetMode) {
        return new SpellRef(MagicDef.fromJson(id, JsonParser.parseString(
                "{\"id\": \"" + id + "\", \"target_mode\": \"" + targetMode + "\"}")), group);
    }

    private static WorldSnapshot snap(int enemies) {
        return new WorldSnapshot(enemies, 1f, 1f, Set.of());
    }

    // ── select：优先级 + 门控 + 目标规则 ──

    @Test
    void picksFirstCastableInOrder() {
        SpellRef beam = spell("beam", "hostile_nearest");
        SpellRef fireball = spell("fireball", "hostile_lowest_hp");
        SpellRef chosen = CastBrain.select(List.of(beam, fireball),
                def -> true, snap(1));
        assertEquals("beam", chosen.def().id(), "列表顺序决定优先级");
    }

    @Test
    void skipsUncastableAndFallsToNext() {
        SpellRef beam = spell("beam", "hostile_nearest");
        SpellRef fireball = spell("fireball", "hostile_lowest_hp");
        SpellRef chosen = CastBrain.select(List.of(beam, fireball),
                def -> def.id().equals("fireball"), snap(1));
        assertEquals("fireball", chosen.def().id(), "CD/蓝不过的魔法应跳过");
    }

    @Test
    void hostileModesRequireEnemies() {
        SpellRef beam = spell("beam", "hostile_nearest");
        SpellRef fireball = spell("fireball", "hostile_lowest_hp");
        assertNull(CastBrain.select(List.of(beam, fireball), def -> true, snap(0)),
                "快照敌数=0 时 HOSTILE 系魔法不可选");
        // SELF 魔法插在 HOSTILE 之后、无目标时照常可选
        SpellRef buff = spell("buff", "self");
        SpellRef chosen = CastBrain.select(List.of(beam, buff), def -> true, snap(0));
        assertEquals("buff", chosen.def().id(), "SELF 魔法无目标也可施放");
    }

    @Test
    void selfAndNoneCastableWithoutTarget() {
        SpellRef self = spell("shield", "self");
        SpellRef none = spell("rain", "none");
        assertEquals("shield", CastBrain.select(List.of(self), def -> true, WorldSnapshot.EMPTY).def().id());
        assertEquals("rain", CastBrain.select(List.of(none), def -> true, WorldSnapshot.EMPTY).def().id());
    }

    @Test
    void allyModeRequiresInjuredAlly() {
        SpellRef heal = spell("heal", "ally_lowest_hp");
        assertNull(CastBrain.select(List.of(heal), def -> true, new WorldSnapshot(0, 1f, 1f, Set.of())),
                "无受伤友方不施治疗");
        assertEquals("heal", CastBrain.select(List.of(heal), def -> true,
                new WorldSnapshot(0, 1f, 0.5f, Set.of())).def().id(), "有受伤友方才奶");
    }

    @Test
    void emptyKnownReturnsNull() {
        assertNull(CastBrain.select(List.of(), def -> true, snap(1)));
    }

    @Test
    void nullSnapshotFallsBackToEmpty() {
        SpellRef beam = spell("beam", "hostile_nearest");
        assertNull(CastBrain.select(List.of(beam), def -> true, null), "空快照无敌对目标");
    }

    @Test
    void skipsAltarOnlySpells() {
        SpellRef beam = spell("beam", "hostile_nearest");
        MagicDef reviveDef = MagicDef.fromJson("revive", JsonParser.parseString(
                "{\"category\": \"altar\", \"target_mode\": \"dead_ally\", \"altar_only\": true}"));
        SpellRef revive = new SpellRef(reviveDef, null);
        SpellRef chosen = CastBrain.select(List.of(beam, revive), def -> true, snap(1));
        assertEquals("beam", chosen.def().id(), "altarOnly 魔法应被 NPC 自动施法跳过");
        assertNull(CastBrain.select(List.of(revive), def -> true, snap(1)),
                "只有 altarOnly 魔法时不施放（等待祭坛）");
    }

    @Test
    void specialHealSelectableWhenAllyInjured() {
        SpellRef beam = spell("beam", "hostile_nearest");
        MagicDef healDef = MagicDef.fromJson("heal", JsonParser.parseString(
                "{\"category\": \"special\", \"target_mode\": \"ally_lowest_hp\"}"));
        SpellRef heal = new SpellRef(healDef, null);
        SpellRef chosen = CastBrain.select(List.of(beam, heal), def -> true, snap(1));
        assertEquals("beam", chosen.def().id(), "敌数=1 时 beam 占优（列表序）");
        // SPECIAL 锁已移除：heal 装备后允许被自动施法选中（有受伤友方才奶，仍看目标规则）
        assertEquals("heal", CastBrain.select(List.of(heal), def -> true,
                new WorldSnapshot(0, 1f, 0.5f, Set.of())).def().id(),
                "SPECIAL(heal) 可被自动施法选中");
        assertNull(CastBrain.select(List.of(heal), def -> true,
                new WorldSnapshot(0, 1f, 1f, Set.of())),
                "无受伤友方仍不奶");
    }

    // ── select：conditions 门控 ──

    @Test
    void enemyCountGateByGroupSingleMax3AoeMin3() {
        SpellRef single = spell("beam", "single_target", "hostile_nearest");
        SpellRef aoe = spell("meteor_", "aoe", "hostile_nearest");
        // 单体攻击组：敌数 ≤ 3 正常优先；> 3 降级为最低优先级（仍可放，仅在没有匹配法术时）
        assertEquals("beam", CastBrain.select(List.of(single), def -> true, snap(3)).def().id(), "单体组敌数=3 正常放");
        assertEquals("beam", CastBrain.select(List.of(single), def -> true, snap(4)).def().id(),
                "单体组敌数=4 门控不匹配：仅剩它时仍放（降级而非硬禁用）");
        // 群体攻击组：敌数 ≥ 3 正常优先；< 3 降级为最低优先级
        assertEquals("meteor_", CastBrain.select(List.of(aoe), def -> true, snap(2)).def().id(),
                "群攻组敌数=2 门控不匹配：仅剩它时仍放（降级而非硬禁用）");
        assertEquals("meteor_", CastBrain.select(List.of(aoe), def -> true, snap(3)).def().id(), "群攻组敌数=3 正常放");
    }

    @Test
    void aoeSpellInSingleGroupCastsOnSingleEnemy() {
        // 本轮修复核心：群攻法术被玩家拖进单体攻击组 → 按组门控 ≤3，敌数 1 也能放
        SpellRef meteorInSingle = spell("meteor_", "single_target", "hostile_nearest");
        assertEquals("meteor_", CastBrain.select(List.of(meteorInSingle), def -> true, snap(1)).def().id(),
                "群攻法术放单体组按单体组门槛（≤3），敌数 1 可放（陨石对单体修复）");
        assertEquals("meteor_", CastBrain.select(List.of(meteorInSingle), def -> true, snap(4)).def().id(),
                "放单体组则按 ≤3：敌数 4 门控不匹配，但仅剩它时仍施放（不硬禁用）");
    }

    @Test
    void enemyCountGateDemotesNotDisables() {
        SpellRef single = spell("beam", "single_target", "hostile_nearest");
        SpellRef aoe = spell("meteor_", "aoe", "hostile_nearest");
        // 敌数=4：单体组被降级(>3)，有群攻组可用时选群攻组
        assertEquals("meteor_", CastBrain.select(List.of(single, aoe), def -> true, snap(4)).def().id(),
                "敌数=4 单体组被降级(>3)，回落到群攻组");
        // 敌数=2：群攻组被降级(<3)，有单体组可用时选单体组
        assertEquals("beam", CastBrain.select(List.of(aoe, single), def -> true, snap(2)).def().id(),
                "敌数=2 群攻组被降级(<3)，回落到单体组");
        // 群攻组不可用（CD/蓝不够）时，降级的单体组仍被选中施放——最低优先级兜底
        assertEquals("beam", CastBrain.select(List.of(single, aoe), def -> def.id().equals("beam"), snap(4)).def().id(),
                "敌数=4 群攻组不可放时，降级的单体组仍施放");
        // 单体组不可用（CD/蓝不够）时，降级的群攻组仍被选中施放
        assertEquals("meteor_", CastBrain.select(List.of(aoe, single), def -> def.id().equals("meteor_"), snap(2)).def().id(),
                "敌数=2 单体组不可放时，降级的群攻组仍施放");
        // 全不可放仍返回 null（调用方走基础攻击兜底），降级不改变这一契约
        assertNull(CastBrain.select(List.of(single, aoe), def -> false, snap(4)),
                "全不施放时仍 null，不因降级兜底选中不可放法术");
    }

    // ── requiresTarget ──

    @Test
    void requiresTargetByMode() {
        assertTrue(CastBrain.requiresTarget(spell("a", "hostile_nearest").def()));
        assertTrue(CastBrain.requiresTarget(spell("b", "hostile_lowest_hp").def()));
        assertTrue(CastBrain.requiresTarget(spell("c", "ally_lowest_hp").def()));
        assertTrue(CastBrain.requiresTarget(spell("f", "dead_ally").def()), "复活需要死者目标");
        assertFalse(CastBrain.requiresTarget(spell("d", "self").def()));
        assertFalse(CastBrain.requiresTarget(spell("e", "none").def()));
    }

    // ── resolvePriority：预设 ──

    @Test
    void balancedPresetOrdersByGroup() {
        List<SpellRef> known = List.of(
                spell("shield", "defense", "self"),
                spell("heal", "support", "ally_lowest_hp"),
                spell("beam", "single_target", "hostile_nearest"),
                spell("explosion", "aoe", "hostile_nearest"));
        List<SpellRef> priority = CastBrain.resolvePriority(new CastStrategyComponent(), known);
        assertEquals(List.of("explosion", "beam", "heal", "shield"),
                priority.stream().map(r -> r.def().id()).toList(),
                "balanced: 群攻组 > 单体组 > 支援组 > 防御组");
    }

    @Test
    void offensivePresetPrefersSingleGroup() {
        CastStrategyComponent s = new CastStrategyComponent();
        s.setPreset(CastStrategyComponent.Preset.OFFENSIVE);
        List<SpellRef> known = List.of(
                spell("shield", "defense", "self"),
                spell("explosion", "aoe", "hostile_nearest"),
                spell("beam", "single_target", "hostile_nearest"));
        List<SpellRef> priority = CastBrain.resolvePriority(s, known);
        assertEquals(List.of("beam", "explosion", "shield"),
                priority.stream().map(r -> r.def().id()).toList(),
                "offensive: 单体组 > 群攻组 > 防御组");
    }

    @Test
    void defensivePresetPrefersDefense() {
        CastStrategyComponent s = new CastStrategyComponent();
        s.setPreset(CastStrategyComponent.Preset.DEFENSIVE);
        List<SpellRef> known = List.of(
                spell("shield", "defense", "self"),
                spell("heal", "support", "ally_lowest_hp"),
                spell("beam", "single_target", "hostile_nearest"));
        List<SpellRef> priority = CastBrain.resolvePriority(s, known);
        assertEquals(List.of("shield", "heal", "beam"),
                priority.stream().map(r -> r.def().id()).toList(),
                "defensive: 防御组 > 支援组 > 单体组");
    }

    @Test
    void presetExcludesSpecialAndAltar() {
        CastStrategyComponent s = new CastStrategyComponent();
        s.setPreset(CastStrategyComponent.Preset.BALANCED);
        SpellRef beam = spell("beam", "single_target", "hostile_nearest");
        MagicDef teleportDef = MagicDef.fromJson("teleport", JsonParser.parseString(
                "{\"category\": \"special\", \"target_mode\": \"none\"}"));
        SpellRef teleport = new SpellRef(teleportDef, null);
        MagicDef reviveDef = MagicDef.fromJson("revive", JsonParser.parseString(
                "{\"category\": \"altar\", \"target_mode\": \"dead_ally\", \"altar_only\": true}"));
        SpellRef revive = new SpellRef(reviveDef, null);
        List<SpellRef> priority = CastBrain.resolvePriority(s, List.of(beam, teleport, revive));
        assertEquals(List.of("beam"), priority.stream().map(r -> r.def().id()).toList(),
                "SPECIAL/ALTAR 不进策略预设表（L0/祭坛管）");
    }

    @Test
    void presetKeepsKnownOrderWithinGroup() {
        CastStrategyComponent s = new CastStrategyComponent();
        List<SpellRef> known = List.of(
                spell("fireball", "single_target", "hostile_nearest"),
                spell("beam", "single_target", "hostile_nearest"));
        List<SpellRef> priority = CastBrain.resolvePriority(s, known);
        assertEquals(List.of("fireball", "beam"),
                priority.stream().map(r -> r.def().id()).toList(),
                "组内按 spellbook 顺序");
    }

    // ── resolvePriority：已配置（显式列表始终生效）──

    @Test
    void configuredUsesExplicitOrder() {
        CastStrategyComponent s = new CastStrategyComponent();
        s.setConfigured(true);
        s.setCustomPriority(List.of("beam", "heal", "shield"));
        List<SpellRef> known = List.of(
                spell("shield", "defense", "self"),
                spell("heal", "support", "ally_lowest_hp"),
                spell("beam", "single_target", "hostile_nearest"));
        List<SpellRef> priority = CastBrain.resolvePriority(s, known);
        assertEquals(List.of("beam", "heal", "shield"),
                priority.stream().map(r -> r.def().id()).toList(), "已配置用显式 magicId 顺序");
    }

    @Test
    void configuredPresetUsesExplicitPriority() {
        CastStrategyComponent s = new CastStrategyComponent();
        s.setPreset(CastStrategyComponent.Preset.OFFENSIVE);
        s.setConfigured(true);
        s.setCustomPriority(List.of("shield", "beam"));
        List<SpellRef> known = List.of(
                spell("shield", "defense", "self"),
                spell("beam", "single_target", "hostile_nearest"));
        List<SpellRef> priority = CastBrain.resolvePriority(s, known);
        assertEquals(List.of("shield", "beam"),
                priority.stream().map(r -> r.def().id()).toList(),
                "非 CUSTOM 预设下显式列表仍生效（分类内手动序被保留，不被预设分类排序覆盖）");
    }

    @Test
    void configuredDropsUnknownIds() {
        CastStrategyComponent s = new CastStrategyComponent();
        s.setConfigured(true);
        s.setCustomPriority(List.of("beam", "not_a_spell"));
        List<SpellRef> known = List.of(spell("beam", "single_target", "hostile_nearest"));
        List<SpellRef> priority = CastBrain.resolvePriority(s, known);
        assertEquals(List.of("beam"), priority.stream().map(r -> r.def().id()).toList(),
                "不在 spellbook 的 id 丢弃");
    }

    @Test
    void configuredEmptyMeansNothingEnabled() {
        CastStrategyComponent s = new CastStrategyComponent();
        s.setConfigured(true);
        s.setCustomPriority(List.of());
        List<SpellRef> known = List.of(
                spell("shield", "defense", "self"),
                spell("beam", "single_target", "hostile_nearest"));
        List<SpellRef> priority = CastBrain.resolvePriority(s, known);
        assertEquals(List.of(), priority.stream().map(r -> r.def().id()).toList(),
                "玩家配置过但全关 → 空优先级（NPC 不施法，走基础攻击），不兜底回预设");
    }

    @Test
    void nullStrategyDefaultsToBalanced() {
        List<SpellRef> known = List.of(spell("beam", "single_target", "hostile_nearest"));
        List<SpellRef> priority = CastBrain.resolvePriority(null, known);
        assertEquals(List.of("beam"), priority.stream().map(r -> r.def().id()).toList());
    }

    @Test
    void knownSpellsFromEquippedComponentNullSafe() {
        assertTrue(CastBrain.knownSpells((com.wsteam.wandscape.core.component.EquippedMagicComponent) null).isEmpty());
        assertTrue(CastBrain.knownSpells(new com.wsteam.wandscape.core.component.EquippedMagicComponent()).isEmpty());
    }
}
