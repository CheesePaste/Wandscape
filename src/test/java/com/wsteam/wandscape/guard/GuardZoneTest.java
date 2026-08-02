package com.wsteam.wandscape.guard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GuardZoneTest {

    /** 建筑包围盒 10..20 x 5..9 x 30..40，水平外扩 10、Y 不变。 */
    private static final GuardZone ZONE = GuardZone.of(10, 5, 30, 20, 9, 40, 10);

    @Test
    void ofExpandsOnlyHorizontalXz() {
        // 水平 X/Z 各外扩 10：min-10 / max+10
        assertTrue(ZONE.minX() == 0 && ZONE.maxX() == 30);
        assertTrue(ZONE.minZ() == 20 && ZONE.maxZ() == 50);
        // Y 原样：5..9
        assertTrue(ZONE.minY() == 5 && ZONE.maxY() == 9);
    }

    @Test
    void containsInside() {
        assertTrue(ZONE.contains(15, 7, 35));
        assertTrue(ZONE.contains(5, 7, 35));   // 水平外扩区内
        assertTrue(ZONE.contains(25, 7, 45));  // 水平外扩区内
    }

    @Test
    void containsBoundaryInclusive() {
        assertTrue(ZONE.contains(0, 5, 20));    // X/Z/Y 均下界
        assertTrue(ZONE.contains(30, 9, 50));   // X/Z/Y 均上界
        assertTrue(ZONE.contains(15, 9, 35));   // Y 上界
    }

    @Test
    void excludesOutsideHorizontal() {
        assertFalse(ZONE.contains(-1, 7, 35));   // X 过左
        assertFalse(ZONE.contains(31, 7, 35));   // X 过右
        assertFalse(ZONE.contains(15, 7, 19));   // Z 过前
        assertFalse(ZONE.contains(15, 7, 51));   // Z 过后
    }

    @Test
    void excludesUnderground() {
        // Y 低于建筑包围盒底（地下洞穴怪物）：不锁
        assertFalse(ZONE.contains(15, 4, 35));
        assertFalse(ZONE.contains(15, -10, 35));
    }

    @Test
    void excludesAboveRoof() {
        assertFalse(ZONE.contains(15, 10, 35));
        assertFalse(ZONE.contains(15, 100, 35));
    }

    @Test
    void zeroExpandKeepsBounds() {
        GuardZone tight = GuardZone.of(10, 5, 30, 20, 9, 40, 0);
        assertTrue(tight.minX() == 10 && tight.maxX() == 20);
        assertTrue(tight.minY() == 5 && tight.maxY() == 9);
        assertTrue(tight.minZ() == 30 && tight.maxZ() == 40);
    }
}
