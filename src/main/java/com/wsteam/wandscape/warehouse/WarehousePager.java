package com.wsteam.wandscape.warehouse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Pure client-side paging logic for the warehouse slot grid: filter → sort → page.
 * Zero Minecraft dependencies so it is unit-testable.
 */
public final class WarehousePager {

    private final int pageSize;

    public WarehousePager(int pageSize) {
        this.pageSize = pageSize;
    }

    public record Page<T>(List<T> entries, int page, int totalPages) {

        public boolean hasPrev() {
            return page > 0;
        }

        public boolean hasNext() {
            return page < totalPages - 1;
        }
    }

    /** Filter, sort, then slice {@code all} into the requested page (clamped to valid range). */
    public <T> Page<T> page(List<T> all, Predicate<T> filter, Comparator<T> order, int page) {
        List<T> filtered = new ArrayList<>(all.size());
        for (T entry : all) {
            if (filter.test(entry)) {
                filtered.add(entry);
            }
        }
        filtered.sort(order);

        int totalPages = Math.max(1, (filtered.size() + pageSize - 1) / pageSize);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        int from = safePage * pageSize;
        int to = Math.min(from + pageSize, filtered.size());
        return new Page<>(new ArrayList<>(filtered.subList(from, to)), safePage, totalPages);
    }

    /** Compact count text for slot badges: 999 → "999", 1234 → "1.2K", 1_500_000 → "1.5M". */
    public static String formatCount(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
        if (n < 1_000_000_000) return String.format("%.1fM", n / 1_000_000.0);
        return String.format("%.1fB", n / 1_000_000_000.0);
    }
}
