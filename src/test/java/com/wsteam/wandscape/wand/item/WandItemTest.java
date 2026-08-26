package com.wsteam.wandscape.wand.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 法杖 tooltip 数值格式化（带符号 / 整数去小数 / 小数去尾零）。 */
class WandItemTest {

    @Test
    void formatAmount_integer_omitsDecimal() {
        assertEquals("+40", WandItem.formatAmount(40f));
        assertEquals("+2", WandItem.formatAmount(2f));
        assertEquals("-40", WandItem.formatAmount(-40f));
        assertEquals("-5", WandItem.formatAmount(-5f));
    }

    @Test
    void formatAmount_fraction_trimsTrailingZeros() {
        assertEquals("+0.5", WandItem.formatAmount(0.5f));
        assertEquals("+0.4", WandItem.formatAmount(0.4f));
        assertEquals("+1.6", WandItem.formatAmount(1.6f));
        assertEquals("-0.18", WandItem.formatAmount(-0.18f));
    }
}
