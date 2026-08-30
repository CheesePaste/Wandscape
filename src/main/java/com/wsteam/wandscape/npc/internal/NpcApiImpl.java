package com.wsteam.wandscape.npc.internal;

import com.wsteam.wandscape.core.component.ColonyMember;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.npc.data.NpcDataImpl;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.api.NpcApi;
import com.wsteam.wandscape.shared.data.NpcData;

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
        World world = WandscapeEngine.getWorld();
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
}
