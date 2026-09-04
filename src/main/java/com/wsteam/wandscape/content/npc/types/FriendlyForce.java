package com.wsteam.wandscape.content.npc.types;
import com.wsteam.wandscape.content.tourist.entity.ColonyVisitor;

import java.util.UUID;

import javax.annotation.Nullable;

/**
 * 殖民地友军名单（派生判定）：某类实体是否属于一个殖民地的友军。
 *
 * <p>友军名单**派生**而非存储：同 {@code colonyId} 的 NPC + 所有玩家 + 玩家侧召唤（宠物/守护召唤/
 * 玩家随从）。友军不记仇、不受该殖民地 NPC 任何攻击伤害——仇恨记录（{@code SelfDefenseHandler}）
 * 与伤害边界（{@code WandscapeNpc#canBeamHurt} / 伤害入口 {@code NpcSpellPowerHandler}）统一走此判定，
 * 边界不散落。零 MC 依赖，纯 JUnit 可测。玩家侧实体（{@code PLAYER}/{@code PLAYER_SUMMON}/{@code PET}）
 * 的恒友军语义在 PvP 开启（{@code Wandscape.Config.PVP}）时收紧为「仅同殖民地」，见 {@link #isAlly}。
 *
 * <p>本类同时提供**两侧互不侵犯**的纯判定（{@link #areMutuallyAlly}）——用于「宠物/随从不攻击 NPC、
 * NPC 随从不攻击玩家」的双向约束（{@code WandscapeNpc#isMutuallyFriendly} / {@code FriendlyTargetingHandler}），
 * 使镜像方向都收敛到同一套规则，避免各自手写。
 */
public final class FriendlyForce {

    /** Stage 2 占位殖民地：NPC 未归属任何真实殖民地时用它兜底（null 也按此处理）。 */
    public static final UUID PLACEHOLDER_COLONY =
            UUID.fromString("00000000-0000-0000-0000-000000000000");

    /** 目标类别（由调用方按 instanceof 判定后传入）。 */
    public enum AllyKind {
        /** 玩家：默认恒为友军——NPC 永不伤害/记仇任何玩家（含殖民地拥有者与非拥有者）。
         *  PVP 开启（{@code Wandscape#Config.PVP}）时仅同一殖民地的玩家为友军。 */
        PLAYER,
        /** 本模组 NPC：同殖民地才算友军（不同殖民地 NPC 互不视为友军）。 */
        WANDSCAPE_NPC,
        /** 殖民地 NPC 召唤的第三方召唤物（铁魔法 {@code IMagicSummon} / 诡厄 {@code IOwned}）：召唤者为同殖民地 NPC → 友军（施法不误伤自己/同殖民地召唤的随从）。 */
        MAGIC_SUMMON,
        /** 玩家召唤的第三方召唤物（铁魔法 / 诡厄）：默认恒为友军（玩家随从不误伤殖民地单位）；
         *  PVP 开启时仅召唤者为同殖民地玩家的随从为友军。 */
        PLAYER_SUMMON,
        /** 玩家训养的宠物（{@code OwnableEntity} 持有 owner、主人为玩家且非 {@code Enemy}，如狼/猫/鹦鹉/马/骆驼/羊驼）：默认恒为友军；
         *  PVP 开启时仅主人为同殖民地玩家的宠物为友军。诡厄等第三方 {@code Owned} 召唤虽也实现 {@code OwnableEntity}，但归属按召唤者解析走 {@code PLAYER_SUMMON}/{@code MAGIC_SUMMON}，不会落入此类。 */
        PET,
        /** 玩家/村民召唤的原版守护（铁傀儡/雪傀儡）：恒为友军（避免战斗溅射误伤守护单位）。 */
        GOLEM,
        /** 游客（{@code ColonyVisitor}）：同殖民地游客 → 友军（避免战斗溅射误伤短居访客）。 */
        TOURIST,
        /** 经 {@code FriendlyForceApi#registerAlly} 由其它模组注册的友军实体（其召唤物/宠物等）：恒为友军。 */
        EXTERNAL_ALLY,
        /** 其它（中立生物/村民/敌对生物等）：默认不是友军。 */
        OTHER
    }

    private FriendlyForce() {}

    /**
     * 目标是否属于该殖民地的友军名单。
     *
     * <p>{@code pvp} 只影响玩家侧实体（{@code PLAYER}/{@code PLAYER_SUMMON}/{@code PET}）：
     * {@code true} 时仅同一殖民地的玩家侧实体为友军——其它殖民地/无殖民地的玩家、其宠物与召唤物
     * 均判非友军（NPC 可还手/可被敌对权杖标记，用于殖民地间 PvP）；{@code false} 时（原行为）
     * 玩家侧实体恒为友军。非玩家侧类别不受影响：{@code GOLEM}/{@code EXTERNAL_ALLY} 恒友军，
     * {@code WANDSCAPE_NPC}/{@code MAGIC_SUMMON}/{@code TOURIST} 仍按同殖民地判定。
     */
    public static boolean isAlly(UUID selfColony, UUID otherColony, AllyKind kind, boolean pvp) {
        return switch (kind) {
            case PLAYER, PLAYER_SUMMON, PET -> pvp ? sameColony(selfColony, otherColony) : true;
            case GOLEM, EXTERNAL_ALLY -> true;
            case WANDSCAPE_NPC, MAGIC_SUMMON, TOURIST -> sameColony(selfColony, otherColony);
            case OTHER -> false;
        };
    }

    /**
     * 双方是否互为友军（互不侵犯）。任一方恒友军（玩家/宠物/守护召唤/玩家随从，colony 传 null 表示
     * 通用）以另一侧殖民地为参考；两侧均属殖民地侧的须同一殖民地；两侧都无殖民地 → 不适用（false）。
     * PVP 开启时玩家侧实体的 {@code colony} 已由 {@code WandscapeNpc.classify} 解析为所属殖民地，
     * 与其它类别一样按「同殖民地」参与 {@code isAlly} 判定。
     *
     * <p>调用方须先保证至少一侧为**真实殖民地成员**（{@code WandscapeNpc#isColonySide}），否则
     * 玩家侧互不相干（如 EvilMage 与玩家）会被误判为友，使其抢不到敌对目标。
     */
    public static boolean areMutuallyAlly(@Nullable UUID colonyA, AllyKind kindA,
                                          @Nullable UUID colonyB, AllyKind kindB, boolean pvp) {
        UUID ref = colonyA != null ? colonyA : colonyB;
        if (ref == null) return false;
        return isAlly(ref, colonyA, kindA, pvp) && isAlly(ref, colonyB, kindB, pvp);
    }

    /** 实体分类结果：所属友军类别 + 其殖民地（玩家侧通用类在 PVP 关闭时为 null，开启时解析为所属殖民地）。 */
    public record Classified(AllyKind kind, @Nullable UUID colony) {}

    /** 两殖民地是否同一殖民地（null 按占位殖民地处理）。 */
    public static boolean sameColony(UUID a, UUID b) {
        UUID aa = a != null ? a : PLACEHOLDER_COLONY;
        UUID bb = b != null ? b : PLACEHOLDER_COLONY;
        return aa.equals(bb);
    }
}
