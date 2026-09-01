package com.wsteam.wandscape.content.npc.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.wsteam.wandscape.core.types.FollowAttackDecision;

/**
 * 跟随战斗目标有效性决策表：跟随开、非休息、未过期、目标存活、非友军、可伤害、
 * 在追击范围内才为 true——这是「跟随 NPC 攻击玩家攻击的目标（原版狼 OwnerHurtTarget）」
 * 目标解析的纯逻辑来源（FollowAttackDecision.isActive，WandscapeNpc.getFollowAttackTarget 消费）。
 */
class FollowAttackDecisionTest {

    private static final long GAME_TIME = 1000L;
    private static final long EXPIRY = 1300L;   // 过期 = GAME_TIME + 300
    private static final double RANGE_SQ = 48.0 * 48.0; // guard.hateRange 追击范围
    private static final double DIST_SQ = 100.0;        // 10 格，范围内

    private static boolean active(long gameTime, long expiryTick, boolean following, boolean resting,
                                  boolean targetAlive, double distSq, boolean attackable, boolean friendly) {
        return FollowAttackDecision.isActive(gameTime, expiryTick, following, resting,
                targetAlive, distSq, RANGE_SQ, attackable, friendly);
    }

    private static boolean defaultActive() {
        return active(GAME_TIME, EXPIRY, true, false, true, DIST_SQ, true, false);
    }

    @Test
    void notFollowingIsInactive() {
        assertFalse(active(GAME_TIME, EXPIRY, false, false, true, DIST_SQ, true, false));
    }

    @Test
    void restingIsInactive() {
        assertFalse(active(GAME_TIME, EXPIRY, true, true, true, DIST_SQ, true, false));
    }

    @Test
    void expiredIsInactive() {
        assertFalse(active(EXPIRY + 1, EXPIRY, true, false, true, DIST_SQ, true, false));
    }

    @Test
    void deadTargetIsInactive() {
        assertFalse(active(GAME_TIME, EXPIRY, true, false, false, DIST_SQ, true, false));
    }

    @Test
    void friendlyTargetIsInactive() {
        assertFalse(active(GAME_TIME, EXPIRY, true, false, true, DIST_SQ, true, true));
    }

    @Test
    void unattackableTargetIsInactive() {
        assertFalse(active(GAME_TIME, EXPIRY, true, false, true, DIST_SQ, false, false));
    }

    @Test
    void outOfRangeIsInactive() {
        assertFalse(active(GAME_TIME, EXPIRY, true, false, true, RANGE_SQ + 1, true, false));
    }

    @Test
    void allConditionsMetIsActive() {
        assertTrue(defaultActive());
    }

    @Test
    void rangeBoundaryIsInclusive() {
        assertTrue(active(GAME_TIME, EXPIRY, true, false, true, RANGE_SQ, true, false));
    }
}
