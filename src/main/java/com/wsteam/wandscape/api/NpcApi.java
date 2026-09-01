package com.wsteam.wandscape.api;

import com.wsteam.wandscape.content.npc.data.NpcData;

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

    NpcData getNpc(UUID npcId);
    boolean assignHouse(UUID npcId, UUID houseId);

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
}
