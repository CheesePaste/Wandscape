package com.wsteam.wandscape.production.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the windowed quantity range shown by the production slider: the slider always
 * spans a full 64-wide page from a page boundary (1, 65, ...), the +64/-64 buttons page
 * up/down a full page, and the top page narrows at the real total cap.
 */
class QuantityWindowTest {

    @Test
    void firstPageSpansOneToSixtyFour() {
        assertEquals(new QuantityWindow.Page(1, 64), QuantityWindow.page(100, 0));
    }

    @Test
    void pagingUpAdvancesBothBoundsBySixtyFour() {
        assertEquals(new QuantityWindow.Page(65, 128), QuantityWindow.page(200, 1));
        assertEquals(new QuantityWindow.Page(129, 192), QuantityWindow.page(200, 2));
    }

    @Test
    void topPageNarrowsToRealTotal() {
        // total 100 → page 1 is 65..100, not a full 65..128
        assertEquals(new QuantityWindow.Page(65, 100), QuantityWindow.page(100, 1));
        // exactly 128 → page 1 is a full 65..128
        assertEquals(new QuantityWindow.Page(65, 128), QuantityWindow.page(128, 1));
    }

    @Test
    void singlePageWhenTotalWithinOneStack() {
        assertEquals(0, QuantityWindow.maxPage(64));
        assertEquals(new QuantityWindow.Page(1, 64), QuantityWindow.page(64, 0));
    }

    @Test
    void pageIndexClampedToValidRange() {
        assertEquals(new QuantityWindow.Page(1, 64), QuantityWindow.page(100, -1));
        assertEquals(new QuantityWindow.Page(65, 100), QuantityWindow.page(100, 99));
    }

    @Test
    void minTotalIsOne() {
        assertEquals(0, QuantityWindow.maxPage(0));
        assertEquals(new QuantityWindow.Page(1, 1), QuantityWindow.page(0, 0));
    }
}
