package com.wsteam.wandscape.api;

import com.wsteam.wandscape.content.npc.data.NpcData;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
public interface NpcApi {
    List<NpcData> getColonyNpcs(UUID colonyId);
    List<NpcData> getIdleNpcs(UUID colonyId);

    /** Number of NPCs in a colony (convenience, avoids fetching full list). */
    default int getNpcCount(UUID colonyId) {
        return getColonyNpcs(colonyId).size();
    }

    /** Number of idle NPCs in a colony (convenience). */
    default int getIdleNpcCount(UUID colonyId) {
        return getIdleNpcs(colonyId).size();
    }

    /**
     * 查询一名 NPC 所属殖民地的 UUID（反查，与 {@link #getColonyNpcs} 互补）。
     * 未归属任何殖民地（独立/敌对法师等非殖民地实体）返回 null。
     */
    @Nullable
    UUID getNpcColony(UUID npcId);

    NpcData getNpc(UUID npcId);

    /**
     * 该 npcId 是否指向一名在世法师（实体存在、未移除、isAlive）。
     * 与 {@link NpcData#isDead()}（实体级）不同——这是按 uuid 直接查存活，常用于"这个法师（可能已死/待复活）还在吗"。
     */
    boolean isNpcAlive(UUID npcId);

    /**
     * 复活一名法师：需先存在其死亡记录（{@code ColonyDeathRegistry}），且当前不存活，否则返回 false。
     * 位置自动解析到其所在殖民地的市政厅门口（与全灭保底/保卫复活同定位逻辑）；复活免费，成本由调用方自理。
     *
     * @return 成功生成新实体 true；无死亡记录 / 仍存活 / 生成失败 false
     */
    boolean reviveNpc(UUID npcId);

    /** 复活到调用方指定的确切位置（其余条件同 {@link #reviveNpc(UUID)}）。 */
    boolean reviveNpc(UUID npcId, BlockPos pos);
    // ── 可调平衡值（委托 BalanceValues；运行时生效，不追溯已生成实体）──

    int getGuardRange();
    void setGuardRange(int v);
    int getGuardReleaseRange();
    void setGuardReleaseRange(int v);
    int getGuardSelfDefenseRange();
    void setGuardSelfDefenseRange(int v);
    int getGuardHateRange();
    void setGuardHateRange(int v);
    int getGuardHateDurationTicks();
    void setGuardHateDurationTicks(int v);
    int getGuardFollowAttackDurationTicks();
    void setGuardFollowAttackDurationTicks(int v);
    double getGuardKiteStartDist();
    void setGuardKiteStartDist(double v);
    double getGuardKiteStandoff();
    void setGuardKiteStandoff(double v);
    double getGuardEngageStandoff();
    void setGuardEngageStandoff(double v);
    double getGuardFleeHpThreshold();
    void setGuardFleeHpThreshold(double v);
    double getGuardFleeStartDist();
    void setGuardFleeStartDist(double v);
    double getGuardFleeStandoff();
    void setGuardFleeStandoff(double v);
    int getNpcRegenGraceTicks();
    void setNpcRegenGraceTicks(int v);
    int getNpcRegenIntervalTicks();
    void setNpcRegenIntervalTicks(int v);
    int getNpcManaRegenTicks();
    void setNpcManaRegenTicks(int v);
    double getNpcManaRegenFraction();
    void setNpcManaRegenFraction(double v);
    int getReviveNearBuildingRange();
    void setReviveNearBuildingRange(int v);

    // ── 未实现（重设计阶段声明，见 @Unimplemented）──

    /**
     * 在指定位置生成一名属于指定殖民地的法师（默认属性：按小镇等级掷点 + 默认名字/皮肤/空战斗载荷）。
     *
     * @return 新法师的 UUID；位置无效、系统未就绪或生成失败返回 null
     */
    UUID spawnNpc(UUID colonyId, BlockPos spawnPos);

    /**
     * 在指定位置生成一名法师，用 {@link NpcSpawnSpec} 覆盖维度（属性/等级/皮肤/帽色/名字/习得魔法/策略）。
     * {@code spec} 为 null 或全空时即默认生成。整合包/附属模组可借此生成自定义法师。
     *
     * @return 新法师的 UUID；位置无效、系统未就绪或生成失败返回 null
     */
    UUID spawnNpc(UUID colonyId, BlockPos spawnPos, @Nullable NpcSpawnSpec spec);

    /** 把一名 NPC 移出殖民地（掉落装备、不留死亡记录）。 */
    @Unimplemented("重设计阶段——待接入 WandscapeNpc.dismissFromColony")
    default boolean removeNpc(UUID npcId) {
        throw new UnsupportedOperationException("NpcApi.removeNpc not yet implemented");
    }


}
