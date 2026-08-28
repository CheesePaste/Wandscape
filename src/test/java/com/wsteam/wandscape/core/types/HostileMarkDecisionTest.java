package com.wsteam.wandscape.core.types;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HostileMarkDecision} — 强制仇恨优先级决策表。
 * 范围用自选字面量（128² 等可调平衡值不在此钉死，只验边界语义）。
 */
class HostileMarkDecisionTest {

    private static final UUID MARKED = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    @Test
    void markedTargetInRangePrioritized() {
        assertTrue(HostileMarkDecision.shouldPrioritize(MARKED, MARKED, 100.0, 128.0 * 128.0));
    }

    @Test
    void noMarkNeverPrioritizes() {
        assertFalse(HostileMarkDecision.shouldPrioritize(null, MARKED, 10.0, 128.0 * 128.0));
    }

    @Test
    void otherTargetIsNotPrioritized_whileMarkActive() {
        // 标记存在但候选是另一生物 → 不被强制吸引（才能被其它目标正常索敌）
        assertFalse(HostileMarkDecision.shouldPrioritize(MARKED, OTHER, 10.0, 128.0 * 128.0));
    }

    @Test
    void outOfRangeFallsBack() {
        double rangeSq = 128.0 * 128.0;
        assertFalse(HostileMarkDecision.shouldPrioritize(MARKED, MARKED, rangeSq + 1.0, rangeSq));
        assertTrue(HostileMarkDecision.shouldPrioritize(MARKED, MARKED, rangeSq, rangeSq)); // 边界含
    }
}