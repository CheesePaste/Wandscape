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

    // ── desperationEnhanceAmplifier：<10 甲无奖励，10+ 按 A²/100，最高强化 X ──

    @Test
    void noEnhanceBelowTenArmor() {
        assertEquals(0, MagicSpellExecutors.desperationEnhanceAmplifier(0.0f));
        assertEquals(0, MagicSpellExecutors.desperationEnhanceAmplifier(5.0f), "<10 甲无奖励");
        assertEquals(0, MagicSpellExecutors.desperationEnhanceAmplifier(9.9f));
    }

    @Test
    void enhanceGrowsQuadraticallyFromTen() {
        assertEquals(1, MagicSpellExecutors.desperationEnhanceAmplifier(10.0f), "100/100 → amplifier 1");
        assertEquals(2, MagicSpellExecutors.desperationEnhanceAmplifier(15.0f), "225/100 → amplifier 2");
        assertEquals(4, MagicSpellExecutors.desperationEnhanceAmplifier(20.0f));
        assertEquals(9, MagicSpellExecutors.desperationEnhanceAmplifier(31.0f));
    }

    @Test
    void enhanceCapsAtTen() {
        assertEquals(10, MagicSpellExecutors.desperationEnhanceAmplifier(32.0f), "1024/100 → 封顶 amplifier 10");
        assertEquals(10, MagicSpellExecutors.desperationEnhanceAmplifier(40.0f));
    }

    // ── magicEnhanceMultiplier：独立乘区，1 级 +20%，每级 +20% ──

    @Test
    void magicEnhanceLevelOneIsTwentyPercent() {
        assertEquals(1.2f, MagicSpellExecutors.magicEnhanceMultiplier(0), 0.0001f, "I 级（amplifier 0）→ +20%");
    }

    @Test
    void magicEnhanceScalesTwentyPercentPerLevel() {
        assertEquals(1.4f, MagicSpellExecutors.magicEnhanceMultiplier(1), 0.0001f, "II 级 → +40%");
        assertEquals(2.0f, MagicSpellExecutors.magicEnhanceMultiplier(4), 0.0001f, "V 级 → +100%");
        assertEquals(3.0f, MagicSpellExecutors.magicEnhanceMultiplier(9), 0.0001f, "X 级 → +200%");
        assertEquals(3.2f, MagicSpellExecutors.magicEnhanceMultiplier(10), 0.0001f, "XI 级 → +220%");
    }
}
