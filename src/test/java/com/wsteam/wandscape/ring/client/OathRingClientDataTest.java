package com.wsteam.wandscape.ring.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OathRingClientData#reachable} —— 客户端 tooltip「本档位已存/容量」算法。
 * 用自选字面量掩码输入验证公式形状与档位前缀钳制。
 */
class OathRingClientDataTest {

    @AfterEach
    void resetStatic() {
        OathRingClientData.setOccupancy((byte) 0);
    }

    @Test
    void reachableCountsOnlySlotsWithinTierCapacity() {
        OathRingClientData.setOccupancy((byte) 0b1101); // 槽 0,2,3 已占
        assertEquals(1, OathRingClientData.reachable(1)); // 档位1 只见槽0
        assertEquals(1, OathRingClientData.reachable(2)); // 档位2 只见槽0,1→仅0
        assertEquals(3, OathRingClientData.reachable(4)); // 全档位 3/4
    }

    @Test
    void emptyMaskYieldsZero() {
        OathRingClientData.setOccupancy((byte) 0);
        assertEquals(0, OathRingClientData.reachable(4));
    }

    @Test
    void capacityAboveMaxSlotsClamps() {
        OathRingClientData.setOccupancy((byte) 0b1111);
        assertEquals(4, OathRingClientData.reachable(8)); // 容量>上限按上限钳制
    }
}