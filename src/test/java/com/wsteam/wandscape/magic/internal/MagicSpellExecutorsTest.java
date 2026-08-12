package com.wsteam.wandscape.magic.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MagicSpellExecutorsTest {

    // ── distributeMeteors：保底 3 颗陨石按目标数分配 ──

    @Test
    void totalMeteorsIsAlwaysThreeWhenTargetsExist() {
        assertEquals(3, MagicSpellExecutors.METEOR_TOTAL);
        assertArrayEquals(new int[]{3}, MagicSpellExecutors.distributeMeteors(1));
        assertArrayEquals(new int[]{2, 1}, MagicSpellExecutors.distributeMeteors(2));
        assertArrayEquals(new int[]{1, 1, 1}, MagicSpellExecutors.distributeMeteors(3));
    }

    @Test
    void singleEnemyGetsAllThree() {
        assertArrayEquals(new int[]{3}, MagicSpellExecutors.distributeMeteors(1), "1 敌独占 3 颗");
    }

    @Test
    void twoEnemiesStackOnNearest() {
        assertArrayEquals(new int[]{2, 1}, MagicSpellExecutors.distributeMeteors(2), "2 敌最近 2 颗、次近 1 颗");
    }

    @Test
    void threeOrMoreEachGetOne() {
        assertArrayEquals(new int[]{1, 1, 1}, MagicSpellExecutors.distributeMeteors(3));
        assertArrayEquals(new int[]{1, 1, 1}, MagicSpellExecutors.distributeMeteors(4), "超过 3 个只砸最近 3 个");
        assertArrayEquals(new int[]{1, 1, 1}, MagicSpellExecutors.distributeMeteors(100));
    }

    @Test
    void noTargetsYieldsEmpty() {
        assertArrayEquals(new int[]{}, MagicSpellExecutors.distributeMeteors(0));
        assertArrayEquals(new int[]{}, MagicSpellExecutors.distributeMeteors(-5), "负数按 0 处理");
    }
}
