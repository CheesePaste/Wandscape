package com.wsteam.wandscape.core.types;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wsteam.wandscape.core.types.FriendlyForce.AllyKind;

/**
 * 友军名单判定决策表：同殖民地 NPC + 所有玩家为友军；占位殖民地（null）互认为同殖民地。
 * 这是「NPC 不记仇、攻击不伤害友军」边界的纯逻辑来源（WandscapeNpc.isFriendlyForce/
 * isRetaliationTarget 与 NpcSpellPowerHandler 伤害入口统一走此判定）。
 */
class FriendlyForceTest {

    private static final UUID C1 = UUID.randomUUID();
    private static final UUID C2 = UUID.randomUUID();

    // ── sameColony：null 按占位殖民地处理 ──

    @Test
    void sameColonyMatchesSameId() {
        assertTrue(FriendlyForce.sameColony(C1, C1));
        assertFalse(FriendlyForce.sameColony(C1, C2));
    }

    @Test
    void nullOrPlaceholderAreSameColony() {
        assertTrue(FriendlyForce.sameColony(null, null));
        assertTrue(FriendlyForce.sameColony(null, FriendlyForce.PLACEHOLDER_COLONY));
        assertTrue(FriendlyForce.sameColony(FriendlyForce.PLACEHOLDER_COLONY, null));
    }

    @Test
    void placeholderIsNotARealColony() {
        assertFalse(FriendlyForce.sameColony(null, C1));
        assertFalse(FriendlyForce.sameColony(C1, null));
        assertFalse(FriendlyForce.sameColony(FriendlyForce.PLACEHOLDER_COLONY, C1));
    }

    // ── isAlly：玩家恒友军 / NPC 看殖民地 / 其它恒非友军 ──

    @Test
    void allPlayersAreAlwaysAllies() {
        assertTrue(FriendlyForce.isAlly(C1, null, AllyKind.PLAYER));
        assertTrue(FriendlyForce.isAlly(null, null, AllyKind.PLAYER));
    }

    @Test
    void npcIsAllyOnlyWhenSameColony() {
        assertTrue(FriendlyForce.isAlly(C1, C1, AllyKind.WANDSCAPE_NPC));
        assertFalse(FriendlyForce.isAlly(C1, C2, AllyKind.WANDSCAPE_NPC));
        // 两个未归属殖民地的 NPC（null）互认为友军
        assertTrue(FriendlyForce.isAlly(null, null, AllyKind.WANDSCAPE_NPC));
        assertFalse(FriendlyForce.isAlly(C1, null, AllyKind.WANDSCAPE_NPC));
        assertFalse(FriendlyForce.isAlly(null, C1, AllyKind.WANDSCAPE_NPC));
    }

    @Test
    void otherEntitiesAreNeverAllies() {
        assertFalse(FriendlyForce.isAlly(C1, C1, AllyKind.OTHER));
        assertFalse(FriendlyForce.isAlly(null, null, AllyKind.OTHER));
    }
}
