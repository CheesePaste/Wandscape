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

    // ── desperationStrengthAmplifier：<10 甲无奖励，10+ 按 A²/100，最高力量 X ──

    @Test
    void noStrengthBelowTenArmor() {
        assertEquals(0, MagicSpellExecutors.desperationStrengthAmplifier(0.0f));
        assertEquals(0, MagicSpellExecutors.desperationStrengthAmplifier(5.0f), "<10 甲无奖励");
        assertEquals(0, MagicSpellExecutors.desperationStrengthAmplifier(9.9f));
    }

    @Test
    void strengthGrowsQuadraticallyFromTen() {
        assertEquals(1, MagicSpellExecutors.desperationStrengthAmplifier(10.0f), "100/100 → amplifier 1");
        assertEquals(2, MagicSpellExecutors.desperationStrengthAmplifier(15.0f), "225/100 → amplifier 2");
        assertEquals(4, MagicSpellExecutors.desperationStrengthAmplifier(20.0f));
        assertEquals(9, MagicSpellExecutors.desperationStrengthAmplifier(31.0f));
    }

    @Test
    void strengthCapsAtTen() {
        assertEquals(10, MagicSpellExecutors.desperationStrengthAmplifier(32.0f), "1024/100 → 封顶 amplifier 10");
        assertEquals(10, MagicSpellExecutors.desperationStrengthAmplifier(40.0f));
    }
}
