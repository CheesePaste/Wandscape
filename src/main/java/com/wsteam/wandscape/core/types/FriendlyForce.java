package com.wsteam.wandscape.core.types;
import com.wsteam.wandscape.content.tourist.entity.ColonyVisitor;

import java.util.UUID;

/**
 * 殖民地友军名单（派生判定）：某类实体是否属于一个殖民地的友军。
 *
 * <p>友军名单**派生**而非存储：同 {@code colonyId} 的 NPC + 所有玩家。友军不记仇、不受该殖民地
 * NPC 任何攻击伤害——仇恨记录（{@code SelfDefenseHandler}）与伤害边界（{@code WandscapeNpc#canBeamHurt}/
 * 伤害入口 {@code NpcSpellPowerHandler}）统一走此判定，边界不散落。零 MC 依赖，纯 JUnit 可测。
 */
public final class FriendlyForce {

    /** Stage 2 占位殖民地：NPC 未归属任何真实殖民地时用它兜底（null 也按此处理）。 */
    public static final UUID PLACEHOLDER_COLONY =
            UUID.fromString("00000000-0000-0000-0000-000000000000");

    /** 目标类别（由调用方按 instanceof 判定后传入）。 */
    public enum AllyKind {
        /** 玩家：所有玩家恒为友军——NPC 永不伤害/记仇任何玩家（含殖民地拥有者与非拥有者）。 */
        PLAYER,
        /** 本模组 NPC：同殖民地才算友军（不同殖民地 NPC 互不视为友军）。 */
        WANDSCAPE_NPC,
        /** 铁魔法召唤物（{@code IMagicSummon}）：召唤者为同殖民地 NPC → 友军（施法不误伤自己/同殖民地召唤的亡灵随从）。 */
        MAGIC_SUMMON,
        /** 游客（{@code ColonyVisitor}）：同殖民地游客 → 友军（避免战斗溅射误伤短居访客）。 */
        TOURIST,
        /** 其它（中立生物/村民/敌对生物等）：默认不是友军。 */
        OTHER
    }

    private FriendlyForce() {}

    /** 目标是否属于该殖民地的友军名单。 */
    public static boolean isAlly(UUID selfColony, UUID otherColony, AllyKind kind) {
        return switch (kind) {
            case PLAYER -> true;
            case WANDSCAPE_NPC, MAGIC_SUMMON, TOURIST -> sameColony(selfColony, otherColony);
            case OTHER -> false;
        };
    }

    /** 两殖民地是否同一殖民地（null 按占位殖民地处理）。 */
    public static boolean sameColony(UUID a, UUID b) {
        UUID aa = a != null ? a : PLACEHOLDER_COLONY;
        UUID bb = b != null ? b : PLACEHOLDER_COLONY;
        return aa.equals(bb);
    }
}
