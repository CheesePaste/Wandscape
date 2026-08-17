package com.wsteam.wandscape.tourist.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 夜晚「无空闲旅店」闩锁（当晚旅店全满/过远传送失败后不再搜索）纯逻辑单测。 */
class HotelRouteBackoffTest {

    /** 初始未闩：可正常尝试找旅店。 */
    @Test
    void freshIsNotActive() {
        assertFalse(new HotelRouteBackoff().isActive());
    }

    /** 闩上后 isActive 为真（当晚不再搜索）。 */
    @Test
    void enterActivates() {
        HotelRouteBackoff b = new HotelRouteBackoff();
        b.enter();
        assertTrue(b.isActive());
    }

    /** 天亮/成功路由后 clear → 恢复可搜索（下一晚重新尝试）。 */
    @Test
    void clearDeactivates() {
        HotelRouteBackoff b = new HotelRouteBackoff();
        b.enter();
        assertTrue(b.isActive());
        b.clear();
        assertFalse(b.isActive());
    }

    /** 未闩时 clear 是幂等空操作。 */
    @Test
    void clearOnFreshIsNoop() {
        HotelRouteBackoff b = new HotelRouteBackoff();
        b.clear();
        assertFalse(b.isActive());
    }

    /** enter→clear→enter 可跨晚循环（每天夜晚重新尝试一次）。 */
    @Test
    void canRelatchNextNight() {
        HotelRouteBackoff b = new HotelRouteBackoff();
        b.enter();
        b.clear();
        assertFalse(b.isActive());
        b.enter();
        assertTrue(b.isActive());
    }
}