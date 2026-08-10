package com.wsteam.wandscape.tourist.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TouristSimulationGainTest {

    /** 侧重舒适游客（need 80/35/35）起步：均衡建筑总增益 90 > 单维夸张 Comfort 建筑 80。 */
    @Test
    void balancedBeatsLopsidedWhenGapsAreWide() {
        int[] need = {80, 35, 35};
        int[] sat = {0, 0, 0};
        double balanced = TouristSimulation.satisfactionGain(need, sat, new int[]{30, 30, 30}, 1.0);
        double lopsided = TouristSimulation.satisfactionGain(need, sat, new int[]{90, 0, 0}, 1.0);
        assertEquals(90, balanced);
        assertEquals(80, lopsided);
        assertTrue(balanced > lopsided, "均衡建筑应比单维夸张建筑总增益更高");
    }

    /** Comfort 满条 → 高 Comfort 建筑对该游客增益 0，不再被偏爱。 */
    @Test
    void fullBarGivesZeroGain() {
        int[] need = {80, 35, 35};
        int[] sat = {80, 0, 0};
        assertEquals(0, TouristSimulation.satisfactionGain(need, sat, new int[]{90, 0, 0}, 1.0));
    }

    /** 增益封顶需求缺口：只剩 5 点 Comfort 缺口时，90 值只贡献 5。 */
    @Test
    void gainCappedByGap() {
        int[] need = {80, 35, 35};
        int[] sat = {75, 0, 0};
        assertEquals(5, TouristSimulation.satisfactionGain(need, sat, new int[]{90, 0, 0}, 1.0));
    }

    /** coeff 放大每维增益，但仍封顶缺口。 */
    @Test
    void coeffScalesGainCappedByGap() {
        int[] need = {80, 35, 35};
        int[] sat = {0, 0, 0};
        assertEquals(130, TouristSimulation.satisfactionGain(need, sat, new int[]{30, 30, 30}, 2.0));
    }

    /** 该维值为 0 → 不贡献增益。 */
    @Test
    void zeroValueDimensionIgnored() {
        int[] need = {80, 35, 35};
        int[] sat = {0, 0, 0};
        assertEquals(35, TouristSimulation.satisfactionGain(need, sat, new int[]{0, 40, 0}, 1.0));
    }
}
