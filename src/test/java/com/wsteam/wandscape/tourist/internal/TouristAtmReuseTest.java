package com.wsteam.wandscape.tourist.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link TouristSimulation#atmReusable} 纯函数测试：决定游客能否豁免 visited 再去 ATM 取现。
 * 三个条件缺一不可——池子有余额（防取 0）、钱包低于初始 1/4（只在缺钱时去）、取现冷却已过（分批节奏）。
 */
class TouristAtmReuseTest {

    private static final int INITIAL = 500;   // 1 级游客随身现金（base 200 + level×300 的近似）
    private static final int COOLDOWN = 2400; // 默认冷却 2400 tick

    private boolean reusable(int travelFund, int wallet, int lastWithdraw, int timeBase) {
        return TouristSimulation.atmReusable(travelFund, wallet, INITIAL, lastWithdraw, timeBase, COOLDOWN);
    }

    /** 从未取现（last==0）+ 池子有钱 + 钱包低 → 恒可去（首次取现必经）。 */
    @Test
    void neverWithdrawnAlwaysReusable() {
        assertTrue(reusable(1500, 0, 0, 100));
        assertTrue(reusable(1500, 100, 0, 100));
    }

    /** 池子空（travelFund=0）→ 不可去，钱包再低也不去——否则白跑取 0。 */
    @Test
    void emptyPoolNotReusable() {
        assertFalse(reusable(0, 0, 0, 100));
        assertFalse(reusable(0, 50, 0, 100));
    }

    /** 钱包 ≥ 初始 1/4 → 不缺钱，不去 ATM（冷却逻辑无需介入）。 */
    @Test
    void walletHighNotReusable() {
        assertFalse(reusable(1500, 125, 0, 100)); // 恰好 = initial/4
        assertFalse(reusable(1500, 500, 0, 100));
    }

    /** 钱包略低于 initial/4 → 可去。 */
    @Test
    void walletJustBelowThresholdReusable() {
        assertTrue(reusable(1500, 124, 0, 100));
    }

    /** 冷却已过（间隔 == cooldown，含边界）→ 可再取现。 */
    @Test
    void cooldownElapsedReusable() {
        assertTrue(reusable(1500, 0, 100, 2500));
        assertTrue(reusable(1500, 0, 100, 100 + COOLDOWN)); // 恰好到点
    }

    /** 冷却未过 → 暂不可去（分批节奏，防连跑 ATM 清空池子）。 */
    @Test
    void withinCooldownNotReusable() {
        assertFalse(reusable(1500, 0, 100, 2400));
        assertFalse(reusable(1500, 0, 100, 100));
    }
}
