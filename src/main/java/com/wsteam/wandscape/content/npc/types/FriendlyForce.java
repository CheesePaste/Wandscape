package com.wsteam.wandscape.content.npc.types;

import com.wsteam.wandscape.content.colony.roster.ColonyRole;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

/**
 * 殖民地友军名单（派生判定）：某类实体是否属于一个殖民地的友军。
 *
 * <p>友军名单**派生**而非存储。自「多殖民地花名册」起按**花名册**派生，不再是「Owner 身份制」：
 * <ul>
 *   <li>玩家侧实体（{@code PLAYER}/{@code PLAYER_SUMMON}/{@code PET}）的归属 = **该玩家在哪些镇的花名册上**
 *       （{@code ColonyApi.getColoniesOf}）。一人可同时在多座镇，故为**集合**而非单值。</li>
 *   <li>镇与镇的关系由 {@link #linked} 判定，且**对称**：本镇成员若拥有另一座镇，两镇互为友军
 *       （成员自镇也是友军）。两侧共用同一关系，避免「A 不还手、C 下死手」的单向漏洞。</li>
 * </ul>
 *
 * <p>**档位（{@code ColonyRole}）在友军判定上一视同仁**：OWNER / MANAGER / MEMBER / ALLY 只要在该镇花名册上，
 * 即属该镇友军白名单——不被该镇法师攻击、不记仇、不误伤。档位只决定操作权限（建造/仓库/任务调度等），
 * 与友军白名单无关。
 *
 * <p>零 MC 依赖：本类不 import 任何 Minecraft / NeoForge 类型；花名册数据经纯函数接口
 * {@link ColonyRosterLookup} 由 MC 侧（{@code WandscapeNpc}）注入。纯判定，可单测。
 */
public final class FriendlyForce {

    /** Stage 2 占位殖民地：NPC 未归属任何真实殖民地时用它兜底（null 也按此处理）。 */
    public static final UUID PLACEHOLDER_COLONY =
            UUID.fromString("00000000-0000-0000-0000-000000000000");

    /** 占位殖民地单元素集合（把 null / 空集合归一成旧 {@code sameColony(null, x)} 语义）。 */
    private static final Set<UUID> PLACEHOLDER_SET = Set.of(PLACEHOLDER_COLONY);

    /** 目标类别（由调用方按 instanceof 判定后传入）。 */
    public enum AllyKind {
        /** 玩家：默认恒为友军——NPC 永不伤害/记仇任何玩家（含殖民地成员与非成员）。
         *  PVP 开启（{@code Wandscape.Config.PVP}）时仅「与本殖民地互为友军」的玩家为友军，
         *  花名册四档（OWNER/MANAGER/MEMBER/ALLY）一视同仁。 */
        PLAYER,
        /** 本模组 NPC：与本殖民地互为友军（{@link #linked}）才算友军（不同殖民地 NPC 互不视为友军）。 */
        WANDSCAPE_NPC,
        /** 殖民地 NPC 召唤的第三方召唤物（铁魔法 {@code IMagicSummon} / 诡厄 {@code IOwned}）：召唤者为同殖民地 NPC → 友军（施法不误伤自己/同殖民地召唤的随从）。 */
        MAGIC_SUMMON,
        /** 玩家召唤的第三方召唤物（铁魔法 / 诡厄）：默认恒为友军（玩家随从不误伤殖民地单位）；
         *  PVP 开启时仅召唤者与本殖民地互为友军的随从为友军。 */
        PLAYER_SUMMON,
        /** 玩家训养的宠物（{@code OwnableEntity} 持有 owner、主人为玩家且非 {@code Enemy}，如狼/猫/鹦鹉/马/骆驼/羊驼）：默认恒为友军；
         *  PVP 开启时仅主人与本殖民地互为友军的宠物为友军。诡厄等第三方 {@code Owned} 召唤虽也实现 {@code OwnableEntity}，但归属按召唤者解析走 {@code PLAYER_SUMMON}/{@code MAGIC_SUMMON}，不会落入此类。 */
        PET,
        /** 玩家搭建的原版守护（玩家创建的铁傀儡 / 雪傀儡）：恒为友军（避免战斗溅射误伤守护单位）。村庄自然生成的铁傀儡归为 OTHER。 */
        GOLEM,
        /** 游客（{@code ColonyVisitor}）：与本殖民地互为友军 → 友军（避免战斗溅射误伤短居访客）。 */
        TOURIST,
        /** 经 {@code FriendlyForceApi#registerAlly} 由其它模组注册的友军实体（其召唤物/宠物等）：恒为友军。 */
        EXTERNAL_ALLY,
        /** 其它（中立生物/村民/敌对生物等）：默认不是友军。 */
        OTHER
    }

    /**
     * 花名册查询缝（纯函数接口，实现方在 MC 侧 {@code WandscapeNpc}）：给出某镇的「成员 → 档位」。
     *
     * <p>本类是零 MC 依赖的纯判定，绝不自己查 {@code ColonyApi}（它 import {@code BlockPos}，
     * 在纯判定里调用等于把 MC 拖进来）；数据一律由这里注入。
     *
     * <p>实现方**必须兜底**：API 未装配、查不到、异常时返回空 Map，并在源头 {@code Log.warnOnce}
     * 留痕（不许静默、不许抛）。空花名册使 {@link #linked} 退化为「只有自己那座镇」（等价改造前行为）。
     */
    @FunctionalInterface
    public interface ColonyRosterLookup {
        Map<UUID, ColonyRole> rosterOf(UUID colonyId);
    }

    private FriendlyForce() {}

    /**
     * 目标是否属于该殖民地的友军名单。
     *
     * <p>{@code pvp} 只影响玩家侧实体（{@code PLAYER}/{@code PLAYER_SUMMON}/{@code PET}）：
     * {@code true} 时仅「与该殖民地互为友军」的玩家侧实体为友军（花名册四档一视同仁，不再是只认 Owner）；
     * {@code false} 时（原行为）玩家侧实体恒为友军。非玩家侧类别不受影响：{@code GOLEM}/
     * {@code EXTERNAL_ALLY} 恒友军，{@code WANDSCAPE_NPC}/{@code MAGIC_SUMMON}/{@code TOURIST}
     * 按 {@link #linked} 判定。
     */
    public static boolean isAlly(@Nullable Set<UUID> selfColonies, @Nullable Set<UUID> otherColonies,
                                 AllyKind kind, boolean pvp, @Nullable ColonyRosterLookup lookup) {
        return switch (kind) {
            case PLAYER, PLAYER_SUMMON, PET -> !pvp || linkedAny(selfColonies, otherColonies, lookup);
            case GOLEM, EXTERNAL_ALLY -> true;
            case WANDSCAPE_NPC, MAGIC_SUMMON, TOURIST -> linkedAny(selfColonies, otherColonies, lookup);
            case OTHER -> false;
        };
    }

    /**
     * 双方是否互为友军（互不侵犯）。任一方恒友军（玩家侧实体在 PVP 关闭时、守护召唤、外部注册友军）
     * 以另一侧殖民地为参考；两侧均为殖民地侧时须存在一对互为友军的镇（{@link #linked}）。
     *
     * <p>参考镇取「首个非空集合」而不是直接配对：玩家侧实体在 PVP 关闭时没有殖民地集合，
     * 此时必须以另一侧（真实殖民地）为参考，才能维持改造前「玩家随从不攻击殖民地 NPC」的双向语义。
     * 两侧集合都为空 → 不适用（false），与旧 {@code ref == null} 分支同义。
     *
     * <p>调用方须先保证至少一侧为**真实殖民地成员**（{@code WandscapeNpc#isColonySide}），否则
     * 玩家侧互不相干（如 EvilMage 与玩家）会被误判为友，使其抢不到敌对目标。
     */
    public static boolean areMutuallyAlly(@Nullable Set<UUID> coloniesA, AllyKind kindA,
                                          @Nullable Set<UUID> coloniesB, AllyKind kindB,
                                          boolean pvp, @Nullable ColonyRosterLookup lookup) {
        Set<UUID> a = safe(coloniesA);
        Set<UUID> b = safe(coloniesB);
        Set<UUID> ref = !a.isEmpty() ? a : b;
        if (ref.isEmpty()) return false;
        return isAlly(ref, a, kindA, pvp, lookup) && isAlly(ref, b, kindB, pvp, lookup);
    }

    /**
     * 两座镇是否互为友军（**对称**关系，友军白名单唯一的镇际规则）：
     *
     * <pre>
     * linked(X, Y) = X == Y
     *             或 存在 P：P 在 X 的花名册上，且 P 拥有 Y
     *             或 存在 P：P 在 Y 的花名册上，且 P 拥有 X
     * </pre>
     *
     * <p>两个分句互为镜像，故关系天然对称——这正是修掉「A 不还手、C 下死手」单向漏洞的关键：
     * 只在一侧加特例必留单向漏洞，两侧必须共用本方法。
     *
     * <p>「P 拥有 Y」= P 是 Y 花名册上的 {@link ColonyRole#OWNER}（一座镇恒有且仅有一个 OWNER，
     * 转让后随之变化）。刻意不查 founder 反查表——花名册才是所有权唯一真源，反查表只用于旧路径。
     *
     * <p>降级：花名册查不到（API 未装配 / 客户端 / 查异常，实现方返回空 Map）时两个分句都不成立，
     * 结果收敛为 {@code X == Y}——即「只有自己那座镇」，等价改造前行为。
     */
    public static boolean linked(@Nullable UUID x, @Nullable UUID y, @Nullable ColonyRosterLookup lookup) {
        UUID xx = x != null ? x : PLACEHOLDER_COLONY;
        UUID yy = y != null ? y : PLACEHOLDER_COLONY;
        if (xx.equals(yy)) return true;
        if (lookup == null) return false;
        Map<UUID, ColonyRole> rx = rosterOf(lookup, xx);
        Map<UUID, ColonyRole> ry = rosterOf(lookup, yy);
        if (rx.isEmpty() || ry.isEmpty()) return false;
        for (UUID member : rx.keySet()) {
            // 分句 2：P 在 X 花名册上，且 P 拥有 Y
            if (ry.get(member) == ColonyRole.OWNER) return true;
        }
        for (UUID member : ry.keySet()) {
            // 分句 3：P 在 Y 花名册上，且 P 拥有 X
            if (rx.get(member) == ColonyRole.OWNER) return true;
        }
        return false;
    }

    /** 实体分类结果：友军类别 + 其所属殖民地**集合**（一律不可变）。 */
    public record Classified(AllyKind kind, Set<UUID> colonies) {
        public Classified {
            colonies = colonies != null ? Set.copyOf(colonies) : Set.of();
        }
    }

    /** 单元素殖民地集合（殖民地侧实体用）；null 返回空集。 */
    public static Set<UUID> singleColony(@Nullable UUID colonyId) {
        return colonyId != null ? Set.of(colonyId) : Set.of();
    }

    /**
     * 两个殖民地**集合**之间是否存在一对互为友军的镇（{@link #linked}）。
     *
     * <p>只做**直接**关系配对，刻意不做传递闭包：A 与 C 都与 B 互为友军，不等于 A 与 C 互为友军。
     * 传传递闭包会把豁免范围扩到任务规格之外。
     */
    private static boolean linkedAny(@Nullable Set<UUID> selfColonies, @Nullable Set<UUID> otherColonies,
                                     @Nullable ColonyRosterLookup lookup) {
        for (UUID x : normalize(selfColonies)) {
            for (UUID y : normalize(otherColonies)) {
                if (linked(x, y, lookup)) return true;
            }
        }
        return false;
    }

    /**
     * null / 空集合 = 未解析出殖民地，按占位殖民地归一（保持改造前 {@code sameColony(null, x)} 语义：
     * 只有两侧都未解析出殖民地时才算同一侧）。
     */
    private static Set<UUID> normalize(@Nullable Set<UUID> colonies) {
        return (colonies == null || colonies.isEmpty()) ? PLACEHOLDER_SET : colonies;
    }

    private static Set<UUID> safe(@Nullable Set<UUID> colonies) {
        return colonies != null ? colonies : Set.of();
    }

    /**
     * 取花名册。唯一实现（{@code WandscapeNpc} 的 ROSTER_LOOKUP）已在源头 try/catch + {@code Log.warnOnce}；
     * 这里只做最后一道防崩溃兜底（防非约定实现抛异常把战斗结算打崩），故不重复打日志。
     */
    private static Map<UUID, ColonyRole> rosterOf(ColonyRosterLookup lookup, UUID colonyId) {
        try {
            Map<UUID, ColonyRole> roster = lookup.rosterOf(colonyId);
            return roster != null ? roster : Map.of();
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }
}
