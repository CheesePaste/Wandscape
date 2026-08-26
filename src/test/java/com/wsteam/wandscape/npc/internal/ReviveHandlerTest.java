package com.wsteam.wandscape.npc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ReviveHandlerTest {

    // 盒 [0..10]^3

    @Test
    void distSqToAabbIsZeroInsideBox() {
        assertEquals(0L, ReviveHandler.distSqToAabb(5, 5, 5, 0, 0, 0, 10, 10, 10));
    }

    @Test
    void distSqToAabbIsZeroOnSurface() {
        assertEquals(0L, ReviveHandler.distSqToAabb(0, 5, 5, 0, 0, 0, 10, 10, 10), "贴面距离为 0");
    }

    @Test
    void distSqToAabbOutsideFace() {
        // 点 (15,5,5) 距 X 面 5 → 25
        assertEquals(25L, ReviveHandler.distSqToAabb(15, 5, 5, 0, 0, 0, 10, 10, 10));
    }

    @Test
    void distSqToAabbBelowBox() {
        // 点 (5,-3,5) 距 Y 面 3 → 9
        assertEquals(9L, ReviveHandler.distSqToAabb(5, -3, 5, 0, 0, 0, 10, 10, 10));
    }

    @Test
    void distSqToAabbIncludesYAndCorner() {
        // 点 (15,15,15) 距角 (10,10,10) 为 5,5,5 → 75
        assertEquals(75L, ReviveHandler.distSqToAabb(15, 15, 15, 0, 0, 0, 10, 10, 10));
    }

    @Test
    void distSqToAabbNegativeSide() {
        // 点 (-4,5,5) 距 X 面 4 → 16
        assertEquals(16L, ReviveHandler.distSqToAabb(-4, 5, 5, 0, 0, 0, 10, 10, 10));
    }
}
