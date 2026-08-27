package com.wsteam.wandscape.engine.colony;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * 离线收益折减的纯逻辑单测（scaleIncome/scaleProfit 不依赖 MC 运行时）。
 */
class ColonyActivationTest {

    @Test
    void scaleIncome_boundaryReturns() {
        assertEquals(700L, ColonyActivation.scaleIncome(700, 1.0));   // 满收益原样
        assertEquals(700L, ColonyActivation.scaleIncome(700, 5.0));   // 系数 >1 不放大
        assertEquals(0L, ColonyActivation.scaleIncome(700, 0.0));     // 0 = 冻结（无收益）
        assertEquals(0L, ColonyActivation.scaleIncome(700, -1.0));    // 负数按 0 处理
        assertEquals(0L, ColonyActivation.scaleIncome(0, 0.2));       // 原值 0
        assertEquals(0L, ColonyActivation.scaleIncome(-5, 0.2));      // 原值非正
    }

    @Test
    void scaleIncome_defaultMultiplierRoundsToNearest() {
        assertEquals(140L, ColonyActivation.scaleIncome(700, 0.2));   // 700×0.2 = 140
        assertEquals(280L, ColonyActivation.scaleIncome(1400, 0.2));  // 1400×0.2 = 280
        assertEquals(1L, ColonyActivation.scaleIncome(5, 0.2));       // 1.0 → 1
        assertEquals(1L, ColonyActivation.scaleIncome(4, 0.2));       // 0.8 → round 1
        assertEquals(1L, ColonyActivation.scaleIncome(3, 0.2));       // 0.6 → round 1
        assertEquals(0L, ColonyActivation.scaleIncome(2, 0.2));       // 0.4 → round 0
        assertEquals(2L, ColonyActivation.scaleIncome(8, 0.2));       // 1.6 → round 2
    }

    @Test
    void scaleIncome_neverExceedsOriginalOrGoesNegative() {
        long[] values = {1, 3, 7, 100, 1000, 4096};
        double[] multipliers = {0.0, 0.05, 0.2, 0.5, 0.9, 0.999, 1.0};
        for (long v : values) {
            for (double m : multipliers) {
                long scaled = ColonyActivation.scaleIncome(v, m);
                assertTrue(scaled <= v, "scaleIncome(" + v + ", " + m + ") = " + scaled + " > " + v);
                assertTrue(scaled >= 0, "scaleIncome(" + v + ", " + m + ") = " + scaled + " < 0");
            }
        }
    }

    @Test
    void scaleProfit_keepsCostAndScalesProfit() {
        // cost=3, fullRevenue=4（利润率 1/3）→ profit=1
        long cost = 3, profit = 1;
        assertEquals(4L, ColonyActivation.scaleProfit(cost, profit, 1.0)); // 满收益 = 原售价
        assertEquals(3L, ColonyActivation.scaleProfit(cost, profit, 0.2)); // 3 + round(0.2)=0 → 成本价
        assertEquals(3L, ColonyActivation.scaleProfit(cost, profit, 0.0)); // 冻结 → 成本价

        // cost=10, profit=5 → 0.2: 10 + round(1.0) = 11
        assertEquals(11L, ColonyActivation.scaleProfit(10, 5, 0.2));
        assertEquals(15L, ColonyActivation.scaleProfit(10, 5, 1.0));
    }

    @Test
    void scaleProfit_neverSellsBelowCost() {
        long[] costs = {1, 3, 10, 100};
        long[] profits = {1, 5, 100};
        double[] multipliers = {0.0, 0.1, 0.2, 0.5, 0.999, 1.0};
        for (long c : costs) {
            for (long p : profits) {
                for (double m : multipliers) {
                    long revenue = ColonyActivation.scaleProfit(c, p, m);
                    assertTrue(revenue >= c, "scaleProfit(" + c + ", " + p + ", " + m + ") = "
                            + revenue + " < cost " + c);
                }
            }
        }
    }
}
