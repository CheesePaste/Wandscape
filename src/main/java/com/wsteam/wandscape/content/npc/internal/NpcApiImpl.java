package com.wsteam.wandscape.content.npc.internal;

import com.wsteam.wandscape.content.task.component.ColonyMember;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.npc.data.NpcDataImpl;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.api.NpcApi;
import com.wsteam.wandscape.foundation.util.BalanceValues;
import com.wsteam.wandscape.content.npc.data.NpcData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Implementation of {@link NpcApi} that queries the ECS World via
 * {@link EntityComponentBridge}.
 *
 * <p>Stage 2 limitations:
 * <ul>
 *   <li>{@link #assignHouse} always returns false (stage 4).</li>
 *   <li>NPC lookups go through the bridge's in-memory map (fast but not
 *       persisted across server restarts).</li>
 * </ul>
 */
public class NpcApiImpl implements NpcApi {

    private static final String TAG = "NpcApiImpl";

    @Override
    public List<NpcData> getColonyNpcs(UUID colonyId) {
        List<NpcData> result = new ArrayList<>();
        World world = com.wsteam.wandscape.content.task.ecs.World.getActive();
        if (world == null) return result;

        for (var entry : EntityComponentBridge.INSTANCE.allNpcs().entrySet()) {
            WandscapeNpc npc = entry.getValue();
            if (npc == null || npc.isRemoved()) continue;

            ColonyMember member = world.get(entry.getKey(), ColonyMember.class);
            if (member != null && colonyId.equals(member.colonyId())) {
                result.add(NpcDataImpl.from(npc));
            }
        }
        return result;
    }

    @Override
    public List<NpcData> getIdleNpcs(UUID colonyId) {
        List<NpcData> all = getColonyNpcs(colonyId);
        all.removeIf(npc -> !npc.isIdle());
        return all;
    }

    @Override
    @Nullable
    public NpcData getNpc(UUID npcId) {
        Long ecsId = EntityComponentBridge.INSTANCE.getEcsId(npcId);
        if (ecsId == null) return null;
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(ecsId);
        return npc != null ? NpcDataImpl.from(npc) : null;
    }

    @Override
    public boolean assignHouse(UUID npcId, UUID houseId) {
        // Stage 4: bind NPC to house building → ECS component update
        return false;
    }

    // ── 可调平衡值（委托 BalanceValues；运行时生效，不追溯已生成实体）──
    @Override public int getGuardRange() { return BalanceValues.guardRange(); }
    @Override public void setGuardRange(int v) { BalanceValues.setGuardRange(v); }
    @Override public int getGuardReleaseRange() { return BalanceValues.guardReleaseRange(); }
    @Override public void setGuardReleaseRange(int v) { BalanceValues.setGuardReleaseRange(v); }
    @Override public int getGuardSelfDefenseRange() { return BalanceValues.guardSelfDefenseRange(); }
    @Override public void setGuardSelfDefenseRange(int v) { BalanceValues.setGuardSelfDefenseRange(v); }
    @Override public int getGuardHateRange() { return BalanceValues.guardHateRange(); }
    @Override public void setGuardHateRange(int v) { BalanceValues.setGuardHateRange(v); }
    @Override public int getGuardHateDurationTicks() { return BalanceValues.guardHateDurationTicks(); }
    @Override public void setGuardHateDurationTicks(int v) { BalanceValues.setGuardHateDurationTicks(v); }
    @Override public int getGuardFollowAttackDurationTicks() { return BalanceValues.guardFollowAttackDurationTicks(); }
    @Override public void setGuardFollowAttackDurationTicks(int v) { BalanceValues.setGuardFollowAttackDurationTicks(v); }
    @Override public double getGuardKiteStartDist() { return BalanceValues.guardKiteStartDist(); }
    @Override public void setGuardKiteStartDist(double v) { BalanceValues.setGuardKiteStartDist(v); }
    @Override public double getGuardKiteStandoff() { return BalanceValues.guardKiteStandoff(); }
    @Override public void setGuardKiteStandoff(double v) { BalanceValues.setGuardKiteStandoff(v); }
    @Override public double getGuardEngageStandoff() { return BalanceValues.guardEngageStandoff(); }
    @Override public void setGuardEngageStandoff(double v) { BalanceValues.setGuardEngageStandoff(v); }
    @Override public double getGuardFleeHpThreshold() { return BalanceValues.guardFleeHpThreshold(); }
    @Override public void setGuardFleeHpThreshold(double v) { BalanceValues.setGuardFleeHpThreshold(v); }
    @Override public double getGuardFleeStartDist() { return BalanceValues.guardFleeStartDist(); }
    @Override public void setGuardFleeStartDist(double v) { BalanceValues.setGuardFleeStartDist(v); }
    @Override public double getGuardFleeStandoff() { return BalanceValues.guardFleeStandoff(); }
    @Override public void setGuardFleeStandoff(double v) { BalanceValues.setGuardFleeStandoff(v); }
    @Override public int getNpcRegenGraceTicks() { return BalanceValues.npcRegenGraceTicks(); }
    @Override public void setNpcRegenGraceTicks(int v) { BalanceValues.setNpcRegenGraceTicks(v); }
    @Override public int getNpcRegenIntervalTicks() { return BalanceValues.npcRegenIntervalTicks(); }
    @Override public void setNpcRegenIntervalTicks(int v) { BalanceValues.setNpcRegenIntervalTicks(v); }
    @Override public int getNpcManaRegenTicks() { return BalanceValues.npcManaRegenTicks(); }
    @Override public void setNpcManaRegenTicks(int v) { BalanceValues.setNpcManaRegenTicks(v); }
    @Override public double getNpcManaRegenFraction() { return BalanceValues.npcManaRegenFraction(); }
    @Override public void setNpcManaRegenFraction(double v) { BalanceValues.setNpcManaRegenFraction(v); }
    @Override public int getReviveNearBuildingRange() { return BalanceValues.reviveNearBuildingRange(); }
    @Override public void setReviveNearBuildingRange(int v) { BalanceValues.setReviveNearBuildingRange(v); }
    @Override public double getScepterHostileRange() { return BalanceValues.scepterHostileRange(); }
    @Override public void setScepterHostileRange(double v) { BalanceValues.setScepterHostileRange(v); }
    @Override public int getMageHutRestTicks() { return BalanceValues.mageHutRestTicks(); }
    @Override public void setMageHutRestTicks(int v) { BalanceValues.setMageHutRestTicks(v); }
}
