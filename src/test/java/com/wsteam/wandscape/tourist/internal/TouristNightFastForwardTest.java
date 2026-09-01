package com.wsteam.wandscape.tourist.internal;

import static org.junit.jupiter.api.Assertions.*;

import com.wsteam.wandscape.content.tourist.internal.TouristSimSystem;
import org.junit.jupiter.api.Test;

import com.wsteam.wandscape.content.tourist.internal.TouristSimSystem.NightOutcome;

/** 夜间快进判定（玩家睡觉跳过夜晚时游客的夜间结果）纯逻辑单测。 */
class TouristNightFastForwardTest {

    /** 到点（departureDeadline）→ 无论住店/满条/有无旅店都离场。 */
    @Test
    void deadlineAlwaysDeparts() {
        assertEquals(NightOutcome.DEPART_DEADLINE,
                TouristSimSystem.nightOutcome(true, false, false, false));
        assertEquals(NightOutcome.DEPART_DEADLINE,
                TouristSimSystem.nightOutcome(true, true, true, true));
    }

    /** 满条 → 当晚离场（含住店客；优先于住店/无旅店）。 */
    @Test
    void fullDepartsEvenWhenCheckedIn() {
        assertEquals(NightOutcome.DEPART_FULL,
                TouristSimSystem.nightOutcome(false, true, false, false));
        assertEquals(NightOutcome.DEPART_FULL,
                TouristSimSystem.nightOutcome(false, true, true, false));
    }

    /** 住店客（未满条、未到点）→ 晨起（不重新找旅店，保留登记）。 */
    @Test
    void checkedInWakesUp() {
        assertEquals(NightOutcome.WAKE,
                TouristSimSystem.nightOutcome(false, false, true, false));
        // 已有旅店的住店客即使全殖民地还能找到其它旅店，也是晨起而非再入住
        assertEquals(NightOutcome.WAKE,
                TouristSimSystem.nightOutcome(false, false, true, true));
    }

    /** 无旅店未满条 + 找到旅店 → 入住后晨起。 */
    @Test
    void noHotelWithVacancyChecksInAndWakes() {
        assertEquals(NightOutcome.CHECKIN_WAKE,
                TouristSimSystem.nightOutcome(false, false, false, true));
    }

    /** 无旅店未满条 + 找不到旅店 → 离场（「夜晚必须找旅馆否则消失」）。 */
    @Test
    void noHotelNoVacancyDeparts() {
        assertEquals(NightOutcome.DEPART_NO_HOTEL,
                TouristSimSystem.nightOutcome(false, false, false, false));
    }

    /** 优先级：deadline > 满条 > 住店 > 找旅店。 */
    @Test
    void precedenceOrder() {
        assertEquals(NightOutcome.DEPART_DEADLINE, TouristSimSystem.nightOutcome(true, true, true, true));
        assertEquals(NightOutcome.DEPART_FULL, TouristSimSystem.nightOutcome(false, true, true, true));
        assertEquals(NightOutcome.WAKE, TouristSimSystem.nightOutcome(false, false, true, true));
        assertEquals(NightOutcome.CHECKIN_WAKE, TouristSimSystem.nightOutcome(false, false, false, true));
    }
}
