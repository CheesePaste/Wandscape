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
        assertFalse(e.equip("single_target", null));
        assertFalse(e.equip("single_target", "  "));
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
}