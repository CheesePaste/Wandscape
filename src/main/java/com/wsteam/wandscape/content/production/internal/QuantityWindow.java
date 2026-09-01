package com.wsteam.wandscape.production.internal;

/**
 * Computes the windowed quantity range shown by the production quantity slider.
 *
 * <p>The slider always displays a range of at most {@link #PAGE_SIZE} (64) consecutive
 * quantities, starting on a page boundary (1, 65, 129, ...). The +64/-64 stepper pages
 * the window up/down by one page, letting the player reach quantities beyond a single
 * stack with a precise drag. The window is always clamped to the real total
 * [1, totalMax], so the top page narrows when the remainder is smaller than a page.
 */
public final class QuantityWindow {

    /** Number of quantities per windowed page. */
    public static final int PAGE_SIZE = 64;

    /** A closed quantity range [min, max]. */
    public record Page(int min, int max) {}

    private QuantityWindow() {
    }

    /** Highest (0-based) page index reachable for a given total. Always >= 0. */
    public static int maxPage(int totalMax) {
        int max = Math.max(1, totalMax);
        return (max - 1) / PAGE_SIZE;
    }

    /** The window for a page index, clamped into [1, totalMax]. */
    public static Page page(int totalMax, int pageIndex) {
        int max = Math.max(1, totalMax);
        int p = Math.clamp(pageIndex, 0, maxPage(max));
        int min = 1 + p * PAGE_SIZE;
        if (min > max) min = Math.max(1, max);
        int high = Math.min(min + PAGE_SIZE - 1, max);
        return new Page(min, high);
    }
}
