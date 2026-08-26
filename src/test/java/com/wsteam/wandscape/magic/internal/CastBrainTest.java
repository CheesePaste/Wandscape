package com.wsteam.wandscape.magic.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;
import com.wsteam.wandscape.core.component.CastStrategyComponent;
import com.wsteam.wandscape.magic.data.MagicDef;
import com.wsteam.wandscape.magic.data.WorldSnapshot;

class CastBrainTest {

    private static MagicDef spell(String id, String targetMode) {
        return MagicDef.fromJson(id, JsonParser.parseString(
                "{\"id\": \"" + id + "\", \"target_mode\": \"" + targetMode + "\"}"));
    }

    private static MagicDef spell(String id, String category, String targetMode) {
        return MagicDef.fromJson(id, JsonParser.parseString(
                "{\"id\": \"" + id + "\", \"category\": \"" + category + "\", \"target_mode\": \"" + targetMode + "\"}"));
    }

    private static WorldSnapshot snap(int enemies) {
        return new WorldSnapshot(enemies, 1f, 1f, Set.of());
    }

    // ── select：优先级 + 门控 + 目标规则 ──

    @Test
    void picksFirstCastableInOrder() {
        MagicDef beam = spell("beam", "hostile_nearest");
        MagicDef fireball = spell("fireball", "hostile_lowest_hp");
        MagicDef chosen = CastBrain.select(List.of(beam, fireball),
                def -> true, snap(1));
        assertEquals("beam", chosen.id(), "列表顺序决定优先级");
    }

    @Test
    void skipsUncastableAndFallsToNext() {
        MagicDef beam = spell("beam", "hostile_nearest");
        MagicDef fireball = spell("fireball", "hostile_lowest_hp");
        MagicDef chosen = CastBrain.select(List.of(beam, fireball),
                def -> def.id().equals("fireball"), snap(1));
        assertEquals("fireball", chosen.id(), "CD/蓝不过的魔法应跳过");
    }

    @Test
    void hostileModesRequireEnemies() {
        MagicDef beam = spell("beam", "hostile_nearest");
        MagicDef fireball = spell("fireball", "hostile_lowest_hp");
        assertNull(CastBrain.select(List.of(beam, fireball), def -> true, snap(0)),
                "快照敌数=0 时 HOSTILE 系魔法不可选");
        // SELF 魔法插在 HOSTILE 之后、无目标时照常可选
        MagicDef buff = spell("buff", "self");
        MagicDef chosen = CastBrain.select(List.of(beam, buff), def -> true, snap(0));
        assertEquals("buff", chosen.id(), "SELF 魔法无目标也可施放");
    }

    @Test
    void selfAndNoneCastableWithoutTarget() {
        MagicDef self = spell("shield", "self");
        MagicDef none = spell("rain", "none");
        assertEquals("shield", CastBrain.select(List.of(self), def -> true, WorldSnapshot.EMPTY).id());
        assertEquals("rain", CastBrain.select(List.of(none), def -> true, WorldSnapshot.EMPTY).id());
    }

    @Test
    void allyModeRequiresInjuredAlly() {
        MagicDef heal = spell("heal", "ally_lowest_hp");
        assertNull(CastBrain.select(List.of(heal), def -> true, new WorldSnapshot(0, 1f, 1f, Set.of())),
                "无受伤友方不施治疗");
        assertEquals("heal", CastBrain.select(List.of(heal), def -> true,
                new WorldSnapshot(0, 1f, 0.5f, Set.of())).id(), "有受伤友方才奶");
    }

    @Test
    void emptyKnownReturnsNull() {
        assertNull(CastBrain.select(List.of(), def -> true, snap(1)));
    }

    @Test
    void nullSnapshotFallsBackToEmpty() {
        MagicDef beam = spell("beam", "hostile_nearest");
        assertNull(CastBrain.select(List.of(beam), def -> true, null), "空快照无敌对目标");
    }

    @Test
    void skipsAltarOnlySpells() {
        MagicDef beam = spell("beam", "hostile_nearest");
        MagicDef revive = MagicDef.fromJson("revive", JsonParser.parseString(
                "{\"id\": \"revive\", \"target_mode\": \"dead_ally\", \"altar_only\": true}"));
        MagicDef chosen = CastBrain.select(List.of(beam, revive), def -> true, snap(1));
        assertEquals("beam", chosen.id(), "altarOnly 魔法应被 NPC 自动施法跳过");
        assertNull(CastBrain.select(List.of(revive), def -> true, snap(1)),
                "只有 altarOnly 魔法时不施放（等待祭坛）");
    }

    @Test
    void specialHealSelectableWhenAllyInjured() {
        MagicDef beam = spell("beam", "hostile_nearest");
        MagicDef heal = spell("heal", "special", "ally_lowest_hp");
        MagicDef chosen = CastBrain.select(List.of(beam, heal), def -> true, snap(1));
        assertEquals("beam", chosen.id(), "敌数=1 时 beam 占优（列表序）");
        // SPECIAL 锁已移除：heal 装备后允许被自动施法选中（有受伤友方才奶，仍看目标规则）
        assertEquals("heal", CastBrain.select(List.of(heal), def -> true,
                new WorldSnapshot(0, 1f, 0.5f, Set.of())).id(),
                "SPECIAL(heal) 可被自动施法选中");
        assertNull(CastBrain.select(List.of(heal), def -> true,
                new WorldSnapshot(0, 1f, 1f, Set.of())),
                "无受伤友方仍不奶");
    }

    // ── select：conditions 门控 ──

    @Test
    void enemyCountGateSingleMax3AoeMin3() {
        MagicDef single = spell("beam", "single_target", "hostile_nearest");
        MagicDef aoe = spell("meteor_", "aoe", "hostile_nearest");
        // 单发：敌数 ≤ 3 可放；> 3 不选（改等群发）
        assertEquals("beam", CastBrain.select(List.of(single), def -> true, snap(3)).id(), "单发敌数=3 可放");
        assertNull(CastBrain.select(List.of(single), def -> true, snap(4)), "单发敌数=4 不选");
        // 群发：敌数 ≥ 3 可放；< 3 不选
        assertNull(CastBrain.select(List.of(aoe), def -> true, snap(2)), "群发敌数=2 不选");
        assertEquals("meteor_", CastBrain.select(List.of(aoe), def -> true, snap(3)).id(), "群发敌数=3 可放");
    }

    @Test
    void enemyCountGateFallsBackToNextSpell() {
        MagicDef single = spell("beam", "single_target", "hostile_nearest");
        MagicDef aoe = spell("meteor_", "aoe", "hostile_nearest");
        // 敌数=4：单发被拦(>3)，回落到群发
        assertEquals("meteor_", CastBrain.select(List.of(single, aoe), def -> true, snap(4)).id(),
                "敌数=4 单发被拦(>3)，回落到群发");
        // 敌数=2：群发被拦(<3)，回落到单发
        assertEquals("beam", CastBrain.select(List.of(aoe, single), def -> true, snap(2)).id(),
                "敌数=2 群发被拦(<3)，回落到单发");
    }

    // ── requiresTarget ──

    @Test
    void requiresTargetByMode() {
        assertTrue(CastBrain.requiresTarget(spell("a", "hostile_nearest")));
        assertTrue(CastBrain.requiresTarget(spell("b", "hostile_lowest_hp")));
        assertTrue(CastBrain.requiresTarget(spell("c", "ally_lowest_hp")));
        assertTrue(CastBrain.requiresTarget(spell("f", "dead_ally")), "复活需要死者目标");
        assertFalse(CastBrain.requiresTarget(spell("d", "self")));
        assertFalse(CastBrain.requiresTarget(spell("e", "none")));
    }

    // ── resolvePriority：预设 ──

    @Test
    void balancedPresetOrdersByCategory() {
        List<MagicDef> known = List.of(
                spell("shield", "defense", "self"),
                spell("heal", "support", "ally_lowest_hp"),
                spell("beam", "single_target", "hostile_nearest"),
                spell("explosion", "aoe", "hostile_nearest"));
        List<MagicDef> priority = CastBrain.resolvePriority(new CastStrategyComponent(), known);
        assertEquals(List.of("explosion", "beam", "heal", "shield"),
                priority.stream().map(MagicDef::id).toList(),
                "balanced: AOE > SINGLE_TARGET > SUPPORT > DEFENSE");
    }

    @Test
    void offensivePresetPrefersSingleTarget() {
        CastStrategyComponent s = new CastStrategyComponent();
        s.setPreset(CastStrategyComponent.Preset.OFFENSIVE);
        List<MagicDef> known = List.of(
                spell("shield", "defense", "self"),
                spell("explosion", "aoe", "hostile_nearest"),
                spell("beam", "single_target", "hostile_nearest"));
        List<MagicDef> priority = CastBrain.resolvePriority(s, known);
        assertEquals(List.of("beam", "explosion", "shield"),
                priority.stream().map(MagicDef::id).toList(),
                "offensive: SINGLE_TARGET > AOE > DEFENSE");
    }

    @Test
    void defensivePresetPrefersDefense() {
        CastStrategyComponent s = new CastStrategyComponent();
        s.setPreset(CastStrategyComponent.Preset.DEFENSIVE);
        List<MagicDef> known = List.of(
                spell("shield", "defense", "self"),
                spell("heal", "support", "ally_lowest_hp"),
                spell("beam", "single_target", "hostile_nearest"));
        List<MagicDef> priority = CastBrain.resolvePriority(s, known);
        assertEquals(List.of("shield", "heal", "beam"),
                priority.stream().map(MagicDef::id).toList(),
                "defensive: DEFENSE > SUPPORT > SINGLE_TARGET");
    }

    @Test
    void presetExcludesSpecialAndAltar() {
        CastStrategyComponent s = new CastStrategyComponent();
        s.setPreset(CastStrategyComponent.Preset.BALANCED);
        List<MagicDef> known = List.of(
                spell("beam", "single_target", "hostile_nearest"),
                spell("teleport", "special", "none"),
                spell("revive", "altar", "dead_ally"));
        List<MagicDef> priority = CastBrain.resolvePriority(s, known);
        assertEquals(List.of("beam"), priority.stream().map(MagicDef::id).toList(),
                "SPECIAL/ALTAR 不进策略预设表（L0/祭坛管）");
    }

    @Test
    void presetKeepsKnownOrderWithinCategory() {
        CastStrategyComponent s = new CastStrategyComponent();
        List<MagicDef> known = List.of(
                spell("fireball", "single_target", "hostile_nearest"),
                spell("beam", "single_target", "hostile_nearest"));
        List<MagicDef> priority = CastBrain.resolvePriority(s, known);
        assertEquals(List.of("fireball", "beam"),
                priority.stream().map(MagicDef::id).toList(),
                "类内按 spellbook 顺序");
    }

    // ── resolvePriority：已配置（显式列表始终生效）──

    @Test
    void configuredUsesExplicitOrder() {
        CastStrategyComponent s = new CastStrategyComponent();
        s.setConfigured(true);
        s.setCustomPriority(List.of("beam", "heal", "shield"));
        List<MagicDef> known = List.of(
                spell("shield", "defense", "self"),
                spell("heal", "support", "ally_lowest_hp"),
                spell("beam", "single_target", "hostile_nearest"));
        List<MagicDef> priority = CastBrain.resolvePriority(s, known);
        assertEquals(List.of("beam", "heal", "shield"),
                priority.stream().map(MagicDef::id).toList(), "已配置用显式 magicId 顺序");
    }

    @Test
    void configuredPresetUsesExplicitPriority() {
        CastStrategyComponent s = new CastStrategyComponent();
        s.setPreset(CastStrategyComponent.Preset.OFFENSIVE);
        s.setConfigured(true);
        s.setCustomPriority(List.of("shield", "beam"));
        List<MagicDef> known = List.of(
                spell("shield", "defense", "self"),
                spell("beam", "single_target", "hostile_nearest"));
        List<MagicDef> priority = CastBrain.resolvePriority(s, known);
        assertEquals(List.of("shield", "beam"),
                priority.stream().map(MagicDef::id).toList(),
                "非 CUSTOM 预设下显式列表仍生效（分类内手动序被保留，不被预设分类排序覆盖）");
    }

    @Test
    void configuredDropsUnknownIds() {
        CastStrategyComponent s = new CastStrategyComponent();
        s.setConfigured(true);
        s.setCustomPriority(List.of("beam", "not_a_spell"));
        List<MagicDef> known = List.of(spell("beam", "single_target", "hostile_nearest"));
        List<MagicDef> priority = CastBrain.resolvePriority(s, known);
        assertEquals(List.of("beam"), priority.stream().map(MagicDef::id).toList(),
                "不在 spellbook 的 id 丢弃");
    }

    @Test
    void configuredEmptyMeansNothingEnabled() {
        CastStrategyComponent s = new CastStrategyComponent();
        s.setConfigured(true);
        s.setCustomPriority(List.of());
        List<MagicDef> known = List.of(
                spell("shield", "defense", "self"),
                spell("beam", "single_target", "hostile_nearest"));
        List<MagicDef> priority = CastBrain.resolvePriority(s, known);
        assertEquals(List.of(), priority.stream().map(MagicDef::id).toList(),
                "玩家配置过但全关 → 空优先级（NPC 不施法，走基础攻击），不兜底回预设");
    }

    @Test
    void nullStrategyDefaultsToBalanced() {
        List<MagicDef> known = List.of(spell("beam", "single_target", "hostile_nearest"));
        List<MagicDef> priority = CastBrain.resolvePriority(null, known);
        assertEquals(List.of("beam"), priority.stream().map(MagicDef::id).toList());
    }

    @Test
    void knownSpellsFromEquippedComponentNullSafe() {
        assertTrue(CastBrain.knownSpells((com.wsteam.wandscape.core.component.EquippedMagicComponent) null).isEmpty());
        assertTrue(CastBrain.knownSpells(new com.wsteam.wandscape.core.component.EquippedMagicComponent()).isEmpty());
    }
}
