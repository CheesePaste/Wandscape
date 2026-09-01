package com.wsteam.wandscape.content.npc.types;

/**
 * 跟随战斗目标有效性决策表（纯逻辑，零 MC 依赖，可单测）。
 *
 * <p>跟随模式的 NPC 会攻击其跟随者玩家攻击的目标（原版狼 OwnerHurtTarget 行为）：
 * 跟随开、非休息、未过期、目标存活、非友军、可伤害、在追击范围内才为 true。
 * 由 {@code WandscapeNpc#getFollowAttackTarget} 消费——目标解析的纯逻辑来源，避免
 * 在 MC 耦合实体上写裸 JVM 不可测的分支。
 */
public final class FollowAttackDecision {

    private FollowAttackDecision() {}

    public static boolean isActive(long gameTime, long expiryTick, boolean following, boolean resting,
                                   boolean targetAlive, double distSq, double rangeSq,
                                   boolean attackable, boolean friendly) {
        if (!following || resting) return false;
        if (gameTime > expiryTick) return false;
        if (!targetAlive) return false;
        if (friendly) return false;
        if (!attackable) return false;
        return distSq <= rangeSq;
    }
}
