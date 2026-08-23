package com.wsteam.wandscape.shared.ui.component;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchBoxTest {

    @Test
    void normalizeTrimsAndLowercases() {
        assertEquals("stone", SearchBox.normalize("  Stone  "));
        assertEquals("", SearchBox.normalize(null));
        assertEquals("", SearchBox.normalize("   "));
    }

    @Test
    void emptyQueryMatchesEverything() {
        assertTrue(SearchBox.matches("anything", ""));
        assertTrue(SearchBox.matches("anything", null));
        assertTrue(SearchBox.matches("anything", "  "));
    }

    @Test
    void matchesIsCaseInsensitiveContains() {
        assertTrue(SearchBox.matches("Stone minecraft:stone", "stone"));
        assertTrue(SearchBox.matches("Stone minecraft:stone", "STONE"));
        assertTrue(SearchBox.matches("石头 minecraft:stone", "石头"));
        assertFalse(SearchBox.matches("Stone minecraft:stone", "dirt"));
    }

    @Test
    void filterMatchesLocalizedNameOrId() {
        record Item(String id, String name) {}
        List<Item> all = List.of(
                new Item("minecraft:stone", "石头"),
                new Item("minecraft:dirt", "泥土"),
                new Item("minecraft:oak_log", "橡木原木"));
        // Unified search text: localized name + raw id (the Workstation approach).
        Function<Item, String> text = i -> i.name() + " " + i.id();

        assertEquals(all, SearchBox.filter(all, "", text));          // empty → all
        assertEquals(1, SearchBox.filter(all, "石头", text).size()); // Chinese localized name
        assertEquals(1, SearchBox.filter(all, "oak", text).size());  // raw id
        assertEquals(0, SearchBox.filter(all, "钻石", text).size()); // no match
    }
}
