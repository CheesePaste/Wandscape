package com.wsteam.wandscape.api;
import com.wsteam.wandscape.content.building.data.BuildingData;
import com.wsteam.wandscape.content.colony.roster.ColonyRole;
import com.wsteam.wandscape.foundation.util.NameStyle;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
public interface ColonyApi {
    /** Register a new colony at the given origin. Returns its UUID. */
    default UUID createColony(BlockPos origin) {
        return createColony(origin, null);
    }

    /** Register a new colony at the given origin, recording the founding player. */
    UUID createColony(BlockPos origin, @Nullable UUID founder);

    /** The founding player UUID of a colony, or null if unknown (legacy/console-created). */
    @Nullable
    UUID getFounder(UUID colonyId);

    // [已移除] getColonyByFounder(playerUuid) —— 「按玩家反查他创始的那座镇」。
    // 一人可拥有多座镇之后它**必然歧义**（旧实现是首次匹配的线性扫描），而且会把「作为成员
    // 参与别人的镇」误判成「没有镇」——本次多殖民地改造的根因之一。不要再把它加回来，改用：
    //   我在哪座镇        → ColonyOwnership.activeColony(player) / getActiveColony(playerUuid)
    //   谁拥有这座镇      → getRole(colonyId, player) == ColonyRole.OWNER
    //   这人拥有哪些镇    → getColoniesOf(player) 过滤 OWNER
    // 上面那些方法同为**服务端专用**（客户端恒 null / 空），注意事项同 getRole 的注释。

    /** Find the nearest colony UUID within 256 blocks of pos, or null. */
    UUID getColonyId(BlockPos pos);

    /** Remove a colony and clear its building associations. */
    void deleteColony(UUID colonyId);

    /** True if pos is a registered colony origin. */
    boolean isColonyOrigin(BlockPos pos);

    /** Returns all registered colony UUIDs. Empty if no colonies exist. */
    Collection<UUID> getAllColonyIds();

    /** Character naming rule for future tourist/NPC names (default FANTASY). */
    com.wsteam.wandscape.foundation.util.NameStyle getNamingStyle(UUID colonyId);

    /** Change the colony's character naming rule (only affects future names). */
    void setNamingStyle(UUID colonyId, com.wsteam.wandscape.foundation.util.NameStyle style);

    /** Current colony level (1..max), or 0 when no such colony exists. */
    int getColonyLevel(UUID colonyId);

    /** Current colony experience, or 0 when no such colony exists. */
    int getColonyExp(UUID colonyId);

    /** Programmatically grant experience to a colony (respects max level, may trigger level-up). */
    void grantExperience(UUID colonyId, int amount);

    // ── 名字 / 上限 / 下一级经验 / 激活 / 等级设置（实现方：ColonyApiImpl）──

    /** 殖民地显示名（未知殖民地返回空串）。 */
    String getColonyName(UUID colonyId);

    /** 设置殖民地显示名。 */
    void setColonyName(UUID colonyId, String name);

    /** 殖民地等级上限（全局配置）。 */
    int getMaxLevel();

    /** 升至下一级所需经验（未知殖民地返回 0）。 */
    int getExpToNext(UUID colonyId);

    /** 殖民地当前是否激活（创始人在线且殖民地未冻结；含 per-colony 强制覆盖）。 */
    boolean isActive(UUID colonyId);

    /** 强制冻结/解冻殖民地（覆盖默认派生规则；仅 JVM 生命周期内驻留，重启后回到派生判定）。 */
    void setActive(UUID colonyId, boolean active);

    /**
     * 自由设置殖民地等级（1..{@link #getMaxLevel()}），**升级/降级统一入口**：
     * 可把高等级殖民地直接降回低等级（如 1 级），经验同步按实现方策略重置/缩放。
     *
     * @return 设置成功返回 true；殖民地不存在或越界返回 false
     */
    boolean setColonyLevel(UUID colonyId, int level);

    // ── 花名册（玩家 → 档位）──────────────────────────────────────────
    // 以下全部与 getColonyByFounder 同为**服务端专用**（实现读殖民地 SavedData，
    // 专用服务器的客户端恒返回 null / 空集）。客户端只能用同步下来的数据。

    /** 玩家在指定小镇的档位；不在花名册返回 null（= 非成员，无任何权限）。 */
    @Nullable
    ColonyRole getRole(UUID colonyId, UUID playerId);

    /** 该镇花名册（playerUuid → 档位）只读快照。 */
    Map<UUID, ColonyRole> getRoster(UUID colonyId);

    /** 玩家参与的所有小镇（colonyId → 档位）。一人可在多座镇各有档位，故返回多值。 */
    Map<UUID, ColonyRole> getColoniesOf(UUID playerId);

    /**
     * 设置档位（新增成员或改档）。**不接受 OWNER** —— 所有权变更走 {@link #transferOwner}，
     * 以免破坏「一座镇恒有且仅有一个 OWNER」这个不变量。
     *
     * @return 是否实际发生变更
     */
    boolean setRole(UUID colonyId, UUID playerId, ColonyRole role);

    /** 移出花名册。OWNER 不可被移出（要换人请走 {@link #transferOwner}）。 */
    boolean removeMember(UUID colonyId, UUID playerId);

    /** 转让所有权：新人置 OWNER，前任降为 MANAGER。@return 是否成功。 */
    boolean transferOwner(UUID colonyId, UUID newOwnerId);

    // ── 当前操作的小镇（v3 起，跨重连持久化）──────────────────────────
    // 与 getRole 同为**服务端专用**（读殖民地 SavedData，专用服务器的客户端恒 null）。

    /**
     * 玩家上次选择的小镇；没选过返回 null。
     *
     * <p>只做存储读写，**不判权限**：「档位 ≥ MEMBER 才可切」与默认解析唯一真源在
     * {@code ActiveColonyTracker}。
     */
    @Nullable
    UUID getActiveColony(UUID playerId);

    /** 记录/清除玩家的当前镇（{@code colonyId == null} 表示清除）。 */
    void setActiveColony(UUID playerId, @Nullable UUID colonyId);
}
