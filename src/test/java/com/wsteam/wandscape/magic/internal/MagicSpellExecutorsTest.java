package com.wsteam.wandscape.magic.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MagicSpellExecutorsTest {

    // ── distributeMeteors：保底 6 颗陨石按目标数分配 ──

    @Test
    void totalMeteorsIsAlwaysSixWhenTargetsExist() {
        assertEquals(6, MagicSpellExecutors.METEOR_TOTAL);
        assertArrayEquals(new int[]{6}, MagicSpellExecutors.distributeMeteors(1));
        assertArrayEquals(new int[]{5, 1}, MagicSpellExecutors.distributeMeteors(2));
        assertArrayEquals(new int[]{4, 1, 1}, MagicSpellExecutors.distributeMeteors(3));
    }

    @Test
    void singleEnemyGetsAllSix() {
        assertArrayEquals(new int[]{6}, MagicSpellExecutors.distributeMeteors(1), "1 敌独占 6 颗");
    }

    @Test
    void twoEnemiesStackOnNearest() {
        assertArrayEquals(new int[]{5, 1}, MagicSpellExecutors.distributeMeteors(2), "2 敌最近 5 颗、次近 1 颗");
    }

    @Test
    void moreEnemiesEachGetOne() {
        assertArrayEquals(new int[]{4, 1, 1}, MagicSpellExecutors.distributeMeteors(3));
        assertArrayEquals(new int[]{3, 1, 1, 1}, MagicSpellExecutors.distributeMeteors(4));
        assertArrayEquals(new int[]{2, 1, 1, 1, 1}, MagicSpellExecutors.distributeMeteors(5));
        assertArrayEquals(new int[]{1, 1, 1, 1, 1, 1}, MagicSpellExecutors.distributeMeteors(6));
        assertArrayEquals(new int[]{1, 1, 1, 1, 1, 1}, MagicSpellExecutors.distributeMeteors(100), "超过 6 个只砸最近 6 个");
    }

    @Test
    void noTargetsYieldsEmpty() {
        assertArrayEquals(new int[]{}, MagicSpellExecutors.distributeMeteors(0));
        assertArrayEquals(new int[]{}, MagicSpellExecutors.distributeMeteors(-5), "负数按 0 处理");
    }
}
