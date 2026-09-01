package com.wsteam.wandscape.guard.executor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.wsteam.wandscape.content.npc.guard.executor.GuardCombat;
import org.junit.jupiter.api.Test;

class GuardCombatTest {

    /** 回归（76ce825c 引入）：EvilMageCastGoal 传 world=null（敌对法师走原版导航），
     *  engage 的站定导航取消步骤必须跳过而非 NPE。 */
    @Test
    void cancelNpcNavigationToleratesNullWorld() {
        assertDoesNotThrow(() -> GuardCombat.cancelNpcNavigation(null, -1L, null));
    }
}
