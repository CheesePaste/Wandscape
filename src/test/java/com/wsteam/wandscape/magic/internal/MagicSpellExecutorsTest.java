package com.wsteam.wandscape.magic.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MagicSpellExecutorsTest {

    // ── meteorIntervalTicks：6 颗按 1/6 持续时长均匀间隔 ──

    @Test
    void splitsDurationIntoSixEqualIntervals() {
        assertEquals(20, MagicSpellExecutors.meteorIntervalTicks(120), "120t 持续时长 → 每 20t 落 1 颗");
        assertEquals(10, MagicSpellExecutors.meteorIntervalTicks(60));
        assertEquals(1, MagicSpellExecutors.meteorIntervalTicks(6), "恰好 6t → 每 1t 落 1 颗");
    }

    @Test
    void floorsToAtLeastOneTick() {
        assertEquals(1, MagicSpellExecutors.meteorIntervalTicks(5), "不足 6t → 至少 1t 间隔");
        assertEquals(1, MagicSpellExecutors.meteorIntervalTicks(1));
        assertEquals(1, MagicSpellExecutors.meteorIntervalTicks(0), "0 持续时长兜底 1t");
    }
}
