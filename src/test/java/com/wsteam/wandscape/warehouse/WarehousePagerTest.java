package com.wsteam.wandscape.warehouse;

import java.util.Comparator;
import java.util.List;

import com.wsteam.wandscape.content.warehouse.WarehousePager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarehousePagerTest {

    private static final WarehousePager PAGER = new WarehousePager(54);
    private static final Comparator<String> BY_NAME = Comparator.naturalOrder();

    private static final java.util.function.Predicate<String> MATCH_IRON =
            s -> s.contains("iron");

    // ── Paging ──

    @Test
    void singlePage() {
        var page = PAGER.page(List.of("a", "b", "c"), s -> true, BY_NAME, 0);
        assertEquals(List.of("a", "b", "c"), page.entries());
        assertEquals(1, page.totalPages());
        assertFalse(page.hasPrev());
        assertFalse(page.hasNext());
    }

    @Test
    void pagesSliceInOrder() {
        List<String> all = java.util.stream.IntStream.range(0, 120)
                .mapToObj(i -> "item%03d".formatted(i))
                .toList();
        var page0 = PAGER.page(all, s -> true, BY_NAME, 0);
        assertEquals(54, page0.entries().size());
        assertEquals("item000", page0.entries().getFirst());
        assertEquals(3, page0.totalPages());
        assertFalse(page0.hasPrev());
        assertTrue(page0.hasNext());

        var page1 = PAGER.page(all, s -> true, BY_NAME, 1);
        assertEquals("item054", page1.entries().getFirst());

        var page2 = PAGER.page(all, s -> true, BY_NAME, 2);
        assertEquals(12, page2.entries().size());
        assertEquals("item108", page2.entries().getFirst());
        assertTrue(page2.hasPrev());
        assertFalse(page2.hasNext());
    }

    @Test
    void outOfRangePageClamps() {
        List<String> all = java.util.stream.IntStream.range(0, 60)
                .mapToObj(i -> "item%03d".formatted(i))
                .toList();
        var negative = PAGER.page(all, s -> true, BY_NAME, -5);
        assertEquals(0, negative.page());
        var overflow = PAGER.page(all, s -> true, BY_NAME, 99);
        assertEquals(1, overflow.page());
        assertEquals("item054", overflow.entries().getFirst());
    }

    @Test
    void emptyInputYieldsOneEmptyPage() {
        var page = PAGER.page(List.of(), s -> true, BY_NAME, 0);
        assertTrue(page.entries().isEmpty());
        assertEquals(1, page.totalPages());
        assertFalse(page.hasPrev());
        assertFalse(page.hasNext());
    }

    @Test
    void filterAppliedBeforePaging() {
        List<String> all = List.of("iron_ingot", "cobblestone", "iron_block", "dirt", "iron_nugget");
        var page = PAGER.page(all, MATCH_IRON, BY_NAME, 0);
        assertEquals(List.of("iron_block", "iron_ingot", "iron_nugget"), page.entries());
        assertEquals(1, page.totalPages());
    }

    // ── formatCount ──

    @Test
    void formatCountSmallNumbers() {
        assertEquals("0", WarehousePager.formatCount(0));
        assertEquals("1", WarehousePager.formatCount(1));
        assertEquals("999", WarehousePager.formatCount(999));
    }

    @Test
    void formatCountAbbreviates() {
        assertEquals("1.0K", WarehousePager.formatCount(1000));
        assertEquals("1.2K", WarehousePager.formatCount(1234));
        assertEquals("64.0K", WarehousePager.formatCount(64000));
        assertEquals("1.5M", WarehousePager.formatCount(1_500_000));
        assertEquals("2.1B", WarehousePager.formatCount(2_100_000_000L));
    }
}
