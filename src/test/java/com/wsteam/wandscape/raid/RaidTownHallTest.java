package com.wsteam.wandscape.raid;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RaidTownHallTest {

    @Test
    void withinRangeInclusiveAtBoundary() {
        // 恰好等于 range（含边界）→ true
        assertTrue(RaidTownHall.withinHorizontalRange(0, 0, 10, 0, 10));
        assertTrue(RaidTownHall.withinHorizontalRange(0, 0, 0, 10, 10));
        assertTrue(RaidTownHall.withinHorizontalRange(0, 0, -10, 0, 10));
    }

    @Test
    void withinRangeInside() {
        assertTrue(RaidTownHall.withinHorizontalRange(0, 0, 5, -3, 10));
        assertTrue(RaidTownHall.withinHorizontalRange(3, 3, 3, 3, 0));
    }

    @Test
    void outsideByOneBlock() {
        assertFalse(RaidTownHall.withinHorizontalRange(0, 0, 11, 0, 10));
        assertFalse(RaidTownHall.withinHorizontalRange(0, 0, 0, 11, 10));
    }

    @Test
    void requiresBothAxesWithinRange() {
        // X 超界、Z 在界内 → false（切比雪夫：两轴都必须满足）
        assertFalse(RaidTownHall.withinHorizontalRange(0, 0, 20, 5, 10));
        assertFalse(RaidTownHall.withinHorizontalRange(0, 0, 5, 20, 10));
    }
}
