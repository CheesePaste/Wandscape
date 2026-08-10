package com.wsteam.wandscape.core.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SpellbookComponentTest {

    @Test
    void defaultsToEmptyThenSeedsBeam() {
        SpellbookComponent sb = new SpellbookComponent();
        assertTrue(sb.isEmpty());
        sb.set(SpellbookComponent.DEFAULT_SPELLS);
        assertFalse(sb.isEmpty());
        assertEquals(List.of("beam"), sb.ids());
        assertTrue(sb.knows("beam"));
        assertFalse(sb.knows("fireball"));
    }

    @Test
    void addIsIdempotentAndOrdered() {
        SpellbookComponent sb = new SpellbookComponent();
        sb.add("beam");
        sb.add("fireball");
        sb.add("beam");
        assertEquals(List.of("beam", "fireball"), sb.ids(), "重复添加去重");
    }

    @Test
    void setReplacesAll() {
        SpellbookComponent sb = new SpellbookComponent(List.of("beam"));
        sb.set(List.of("a", "b", "b"));
        assertEquals(List.of("a", "b"), sb.ids());
    }

    @Test
    void removeDropsSpell() {
        SpellbookComponent sb = new SpellbookComponent(List.of("beam", "heal"));
        sb.remove("beam");
        assertEquals(List.of("heal"), sb.ids());
    }

    @Test
    void defensiveCopyOnIds() {
        SpellbookComponent sb = new SpellbookComponent(List.of("beam"));
        List<String> view = sb.ids();
        sb.add("heal");
        assertEquals(List.of("beam"), view, "返回的是副本，不受后续修改影响");
    }
}
