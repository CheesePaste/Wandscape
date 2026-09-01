package com.wsteam.wandscape.content.npc.types;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 敌对权杖强制仇恨目标决策表（纯逻辑，零 MC 依赖，可单测）。
 *
 * <p>本殖民地当前标记了强制仇恨目标 {@code forcedUuid} 时，该殖民地 128 格范围内的法师必须
 * 优先攻击它、期间不被其它生物吸引：目标必须是当前标记物、存活（由调用方解析实体保证）、且在
 * 作用范围内。不满足任一条件回落正常索敌（自防御/守卫的既有逻辑）。由
 * {@code SelfDefenseExecutor.resolveTarget} / {@code GuardAttackExecutor} 消费——与
 * {@link FollowAttackDecision} 同款纯逻辑来源。
 */
public final class HostileMarkDecision {

    private HostileMarkDecision() {}

    /**
     * @param forcedUuid 本殖民地当前的强制仇恨目标 UUID（无标记则为 null）
     * @param targetUuid 候选目标的 UUID
     * @param distSq     候选目标到法师的距离平方
     * @param rangeSq    强制范围平方（默认 {@code 128²}，Config 可调）
     */
    public static boolean shouldPrioritize(@Nullable UUID forcedUuid, UUID targetUuid,
                                           double distSq, double rangeSq) {
        if (forcedUuid == null) return false;
        if (!forcedUuid.equals(targetUuid)) return false;
        return distSq <= rangeSq;
    }
}