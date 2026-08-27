package com.wsteam.wandscape.core.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class EquippedMagicComponentTest {

    private static final Function<String, String> CATEGORY_OF =
            Map.of("beam", "single_target", "fireball", "single_target", "heal", "special",
                    "meteor", "aoe", "petrification", "defense", "teleport", "special",
                    "revive", "altar")::get;

    // ── 桶上限 ──

    @Test
    void capsAtThreePerCategory() {
        EquippedMagicComponent e = new EquippedMagicComponent();
        assertTrue(e.equip("single_target", "a"));
        assertTrue(e.equip("single_target", "b"));
        assertTrue(e.equip("single_target", "c"));
        assertFalse(e.equip("single_target", "d"), "每类最多 3 个");
        assertEquals(List.of("a", "b", "c"), e.list("single_target"));
    }

    @Test
    void unknownCategoryRejected() {
        EquippedMagicComponent e = new EquippedMagicComponent();
        assertFalse(e.equip("special", "teleport"), "SPECIAL 非可装备分类");
        assertFalse(e.equip("altar", "revive"), "ALTAR 非可装备分类");
        assertFalse(e.equip("bogus", "beam"), "未知分类拒绝");
        assertTrue(e.isEmpty());
    }

    @Test
    void nullOrBlankIdRejected() {
        EquippedMagicComponent e = new EquippedMagicComponent();
        assertFalse(e.equip("single_target", (String) null));
        assertFalse(e.equip("single_target", "  "));
        assertFalse(e.equip("single_target", (EquippedMagicComponent.SpellEntry) null));
        assertTrue(e.isEmpty());
    }

    // ── 去重 ──

    @Test
    void dedupeAcrossBuckets() {
        EquippedMagicComponent e = new EquippedMagicComponent();
        assertTrue(e.equip("single_target", "beam"));
        assertFalse(e.equip("support", "beam"), "同一魔法跨桶也去重");
        assertEquals(List.of("beam"), e.flattened());
    }

    // ── 默认 ──

    @Test
    void defaultsAreBeamAndMeteor() {
        assertEquals(List.of("beam", "meteor"), EquippedMagicComponent.DEFAULT_EQUIP);
        EquippedMagicComponent e = new EquippedMagicComponent();
        assertTrue(e.isEmpty());
    }

    // ── shouldSeedDefaults：起始法术只给初始殖民地法师，不给刷怪蛋殖民地法师 ──

    @Test
    void seedsForInitialColonyMage() {
        // 初始 3 法师（ColonyCommand 以 COMMAND 生成）：未配置 + 空装备 + 非刷怪蛋 + 殖民地 → 种默认
        assertTrue(EquippedMagicComponent.shouldSeedDefaults(false, false, false, true));
    }

    @Test
    void doesNotSeedForSpawnEggColonyMage() {
        // 刷怪蛋生成的殖民地法师：不给起始战斗魔法（法术靠策略页/卷轴）
        assertFalse(EquippedMagicComponent.shouldSeedDefaults(false, false, true, true));
    }

    @Test
    void seedsForSpawnEggHostileMage() {
        // 敌对测试法师（EvilMage，colonyNpc=false）：刷怪蛋生成也保留默认装备（实战测试目标）
        assertTrue(EquippedMagicComponent.shouldSeedDefaults(false, false, true, false));
    }

    @Test
    void seedsForOldSaveMigration() {
        // 旧存档无 spellbookEquip 字段（spawnType 未恢复为 SPAWN_EGG）：保留迁移种子
        assertTrue(EquippedMagicComponent.shouldSeedDefaults(false, false, false, true));
    }

    @Test
    void neverSeedsWhenSpellbookLoadedOrEquipped() {
        // 已加载过施法配置（含空负载）或已有装备：不覆盖玩家配置
        assertFalse(EquippedMagicComponent.shouldSeedDefaults(true, false, false, true));
        assertFalse(EquippedMagicComponent.shouldSeedDefaults(false, true, false, true));
    }

    @Test
    void emptyByDefaultThenEquip() {
        EquippedMagicComponent e = new EquippedMagicComponent();
        assertTrue(e.isEmpty());
        e.equip("single_target", "beam");
        assertFalse(e.isEmpty());
        assertTrue(e.knows("beam"));
        assertFalse(e.knows("fireball"));
    }

    // ── 顺序与展平 ──

    @Test
    void equipAppendsAndFlattenFollowsCategoryOrder() {
        EquippedMagicComponent e = new EquippedMagicComponent();
        e.equip("support", "heal");
        e.equip("single_target", "beam");
        assertEquals(List.of("beam", "heal"), e.flattened(), "展平按分类固定序：single_target 在 support 前，桶内保序");
        assertEquals(List.of("beam"), e.list("single_target"));
        assertEquals(List.of("heal"), e.list("support"));
    }

    @Test
    void moveUpDownReordersWithinCategory() {
        EquippedMagicComponent e = new EquippedMagicComponent();
        e.equip("single_target", "a");
        e.equip("single_target", "b");
        assertEquals(List.of("a", "b"), e.list("single_target"));
        assertTrue(e.moveDown("single_target", "a"));
        assertEquals(List.of("b", "a"), e.list("single_target"));
        assertTrue(e.moveUp("single_target", "a"));
        assertEquals(List.of("a", "b"), e.list("single_target"));
        assertFalse(e.moveUp("single_target", "a"), "首位不可再上移");
        assertFalse(e.moveDown("single_target", "b"), "末位不可再下移");
    }

    // ── 卸载 ──

    @Test
    void unequipRemovesAndKeepsOrder() {
        EquippedMagicComponent e = new EquippedMagicComponent();
        e.equip("single_target", "a");
        e.equip("single_target", "b");
        e.equip("single_target", "c");
        assertTrue(e.unequip("single_target", "b"));
        assertEquals(List.of("a", "c"), e.list("single_target"), "卸载后剩余保持相对顺序（无空位）");
        assertFalse(e.unequip("single_target", "nope"));
    }

    // ── 持久（replaceWith）──

    @Test
    void replaceWithCopiesSlotOrder() {
        EquippedMagicComponent src = EquippedMagicComponent.fromFlat(
                List.of("heal", "beam", "meteor", "petrification"), CATEGORY_OF);
        EquippedMagicComponent dst = new EquippedMagicComponent();
        dst.replaceWith(src);
        assertEquals(List.of("beam", "meteor", "petrification"), dst.flattened(),
                "新分类容器 = single_target:[beam] aoe:[meteor] defense:[petrification]（heal 特殊丢弃）");
    }

    // ── fromFlat（服务端校验核心：未知丢 / 非战斗丢 / ≤3 / 去重）──

    @Test
    void fromFlatDropsUnknownAndNonEquippable() {
        EquippedMagicComponent e = EquippedMagicComponent.fromFlat(
                List.of("beam", "not_a_spell", "teleport", "revive", "heal"), CATEGORY_OF);
        assertEquals(List.of("beam"), e.flattened(), "未知 id 与 SPECIAL/ALTAR 魔法丢弃");
        assertFalse(e.knows("teleport"));
        assertFalse(e.knows("heal"));
    }

    @Test
    void fromFlatCapsAndDedupes() {
        EquippedMagicComponent e = EquippedMagicComponent.fromFlat(
                List.of("a", "b", "c", "d", "a", "beam", "b"), id -> "single_target");
        assertEquals(List.of("a", "b", "c"), e.list("single_target"), "每类≤3 且去重");
    }

    @Test
    void fromFlatPreservesWithinCategoryOrder() {
        EquippedMagicComponent e = EquippedMagicComponent.fromFlat(
                List.of("beam", "meteor", "heal", "fireball"), CATEGORY_OF);
        assertEquals(List.of("beam", "fireball"), e.list("single_target"),
                "同分类按扁平序保序：beam 先、fireball 后");
        assertEquals(List.of("beam", "fireball", "meteor"), e.flattened());
    }

    @Test
    void fromFlatNullSafe() {
        assertTrue(EquippedMagicComponent.fromFlat(null, CATEGORY_OF).isEmpty());
        assertTrue(EquippedMagicComponent.fromFlat(List.of("beam"), null).isEmpty());
    }

    // ── SpellEntry 等级与解析 ──

    @Test
    void spellEntryParsesFlatFormat() {
        EquippedMagicComponent.SpellEntry e1 = EquippedMagicComponent.SpellEntry.parse("beam");
        assertEquals("beam", e1.id());
        assertEquals(1, e1.level());
        assertEquals("beam", e1.toFlatString());

        EquippedMagicComponent.SpellEntry e2 = EquippedMagicComponent.SpellEntry.parse("irons_spellbooks:firebolt@5");
        assertEquals("irons_spellbooks:firebolt", e2.id());
        assertEquals(5, e2.level());
        assertEquals("irons_spellbooks:firebolt@5", e2.toFlatString());
    }

    @Test
    void leveledEquipPreservesEntry() {
        EquippedMagicComponent e = new EquippedMagicComponent();
        assertTrue(e.equip("single_target", "irons_spellbooks:firebolt", 5));
        assertTrue(e.knows("irons_spellbooks:firebolt"));

        EquippedMagicComponent.SpellEntry entry = e.getEntry("irons_spellbooks:firebolt");
        assertEquals("irons_spellbooks:firebolt", entry.id());
        assertEquals(5, entry.level());

        List<EquippedMagicComponent.SpellEntry> entries = e.listEntries("single_target");
        assertEquals(1, entries.size());
        assertEquals(5, entries.get(0).level());
    }

    @Test
    void fromFlatParsesLevelsAndCaps() {
        EquippedMagicComponent e = EquippedMagicComponent.fromFlat(
                List.of("beam@1", "irons_spellbooks:firebolt@3", "heal@2"),
                id -> id.equals("heal") ? "support" : "single_target");

        assertEquals(List.of("beam", "irons_spellbooks:firebolt"), e.list("single_target"));
        assertEquals(1, e.getEntry("beam").level());
        assertEquals(3, e.getEntry("irons_spellbooks:firebolt").level());
        assertEquals(2, e.getEntry("heal").level());
    }
}