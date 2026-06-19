package com.wsteam.wandscape.shared.api;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;

import com.wsteam.wandscape.shared.data.NpcData;
import com.wsteam.wandscape.shared.data.RecruitmentCandidate;

public interface NpcApi {
    List<NpcData> getColonyNpcs(UUID colonyId);
    List<NpcData> getIdleNpcs(UUID colonyId);
    NpcData getNpc(UUID npcId);
    boolean assignHouse(UUID npcId, UUID houseId);
    UUID spawnNpc(UUID colonyId, BlockPos pos, RecruitmentCandidate candidate);
}
