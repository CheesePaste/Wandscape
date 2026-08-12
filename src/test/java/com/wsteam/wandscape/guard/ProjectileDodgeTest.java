package com.wsteam.wandscape.guard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProjectileDodgeTest {

    private static final double HIT = 1.0;
    private static final double WINDOW = 16.0;

    /** 正对命中：弹丸沿 x 轴直冲目标，3 tick 内到达。 */
    @Test
    void straightOnHit() {
        assertTrue(ProjectileDodge.willHit(-10, 0, 0, 3, 0, 0, 0, 0, 0, HIT, WINDOW));
    }

    /** 平行偏离弹道 2 格：垂直距离 > 命中半径 → 不躲。 */
    @Test
    void parallelOffsetMisses() {
        assertFalse(ProjectileDodge.willHit(-10, 0, 0, 3, 0, 0, 0, 2, 0, HIT, WINDOW));
    }

    /** 垂直距离在命中半径内（0.5 格）→ 躲。 */
    @Test
    void smallOffsetStillHit() {
        assertTrue(ProjectileDodge.willHit(-10, 0, 0, 3, 0, 0, 0, 0.5, 0, HIT, WINDOW));
    }

    /** 正在远离目标（速度朝反方向）→ 不躲。 */
    @Test
    void movingAwayNotThreat() {
        assertFalse(ProjectileDodge.willHit(-10, 0, 0, -3, 0, 0, 0, 0, 0, HIT, WINDOW));
    }

    /** 命中时间超出预判窗口 → 不躲（还早，不预判）。 */
    @Test
    void tooFarInFutureIgnored() {
        assertFalse(ProjectileDodge.willHit(-100, 0, 0, 3, 0, 0, 0, 0, 0, HIT, WINDOW));
    }

    /** 太近（1 tick 内命中）→ 躲不及，不触发，避免反复空闪。 */
    @Test
    void tooImminentIgnored() {
        assertFalse(ProjectileDodge.willHit(-3, 0, 0, 3, 0, 0, 0, 0, 0, HIT, WINDOW));
    }

    /** 几乎静止（未发射/将落地）→ 不算威胁。 */
    @Test
    void nearlyStationaryIgnored() {
        assertFalse(ProjectileDodge.willHit(-10, 0, 0, 0.1, 0, 0, 0, 0, 0, HIT, WINDOW));
    }

    /** 斜向接近但会在窗口内擦到命中半径内 → 躲。 */
    @Test
    void angledCloseApproach() {
        assertTrue(ProjectileDodge.willHit(-10, -4, 0, 3, 1, 0, 0, 0, 0, HIT, WINDOW));
    }
}
