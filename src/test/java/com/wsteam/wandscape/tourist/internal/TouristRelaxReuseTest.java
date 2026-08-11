package com.wsteam.wandscape.tourist.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link TouristSimulation#relaxReusable} 纯函数测试：决定游客能否豁免 visited 再去 relax 歇脚回精力。
 * 精力低于恢复阈值（默认 0.25 → energy < 25）时可重复逛；精力 0 恒可去（精力耗尽必须能自救）。
 */
class TouristRelaxReuseTest {

    private static final int MAX_ENERGY = 100;
    private static final double THRESHOLD = 0.25;

    private boolean reusable(int energy) {
        return TouristSimulation.relaxReusable(energy, MAX_ENERGY, THRESHOLD);
    }

    /** 精力 0（耗尽）：恒可去 relax，不受阈值影响——否则逛过一次就卡死在精力 0。 */
    @Test
    void emptyEnergyAlwaysReusable() {
        assertTrue(reusable(0));
    }

    /** 精力低于恢复阈值 → 可重复去歇脚。 */
    @Test
    void belowThresholdReusable() {
        assertTrue(reusable(10));
        assertTrue(reusable(24));
    }

    /** 精力 ≥ 阈值 → 不缺精力，按 visited 门（一栋只逛一次）。 */
    @Test
    void atOrAboveThresholdNotReusable() {
        assertFalse(reusable(25));  // 恰好 = threshold × maxEnergy
        assertFalse(reusable(50));
        assertFalse(reusable(100));
    }

    /** 自定义阈值：threshold 越大 → 重复歇脚的窗口越宽。 */
    @Test
    void customThreshold() {
        assertTrue(TouristSimulation.relaxReusable(40, 100, 0.5));
        assertFalse(TouristSimulation.relaxReusable(50, 100, 0.5));
        assertTrue(TouristSimulation.relaxReusable(99, 100, 1.0));
    }

    /** 阈值被设为 0（关闭精力偏好）：精力 0 仍可去 relax（硬兜底），精力 >0 按 visited 门。 */
    @Test
    void zeroThresholdStillFixesExhaustion() {
        assertTrue(TouristSimulation.relaxReusable(0, 100, 0.0));
        assertFalse(TouristSimulation.relaxReusable(1, 100, 0.0));
    }
}
