package com.wsteam.wandscape.api;

import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import com.wsteam.wandscape.content.npc.data.NpcData;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;
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
    double getScepterHostileRange();
    void setScepterHostileRange(double v);
    int getMageHutRestTicks();
    void setMageHutRestTicks(int v);

    // ── 未实现（重设计阶段声明，见 @Unimplemented）──

    /**
     * 在指定位置生成一名属于指定殖民地的 NPC（等级/fixEcsAfterSpawn 由实现方处理）。
     *
     * @return 新 NPC 的 UUID；位置无效或生成失败返回 null
     */
    @Unimplemented("重设计阶段——待接入 WandscapeNpc.spawn + fixEcsAfterSpawn")
    default UUID spawnNpc(UUID colonyId, BlockPos spawnPos) {
        throw new UnsupportedOperationException("NpcApi.spawnNpc not yet implemented");
    }

    /** 把一名 NPC 移出殖民地（掉落装备、不留死亡记录）。 */
    @Unimplemented("重设计阶段——待接入 WandscapeNpc.dismissFromColony")
    default boolean removeNpc(UUID npcId) {
        throw new UnsupportedOperationException("NpcApi.removeNpc not yet implemented");
    }


}
