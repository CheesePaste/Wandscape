package com.wsteam.wandscape.content.building.internal;

import com.wsteam.wandscape.api.MageHutApi;
import com.wsteam.wandscape.api.NpcApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import com.wsteam.wandscape.content.npc.data.MageHutResident;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Implementation of {@link MageHutApi} over {@link BuildingSavedData} resident records.
 *
 * <p>The resident record (buildingId → MageHutResident) is the binding authority, mirroring
 * how {@code MageHutServerHandler.onAssign} binds and how {@code ReviveHandler.rebindToMageHut}
 * re-binds on revive. Colony checklist is preserved on bind to avoid cross-colony dirt.
 */
public class MageHutApiImpl implements MageHutApi {

    private static final String TAG = "MageHutApi";

    @Override
    @Nullable
    public UUID getBindingHut(UUID npcId) {
        if (npcId == null) return null;
        ServerLevel level = getServerLevel();
        if (level == null) return null;
        BuildingSavedData data = BuildingSavedData.get(level);
        for (BuildingState b : data.getAllBuildings()) {
            MageHutResident r = data.getMageHutResident(b.getBuildingId());
            if (r != null && npcId.equals(r.npcId())) {
                return b.getBuildingId();
            }
        }
        return null;
    }

    @Override
    public boolean forceBind(UUID buildingId, UUID npcId) {
        ServerLevel level = getServerLevel();
        if (level == null || buildingId == null || npcId == null) return false;
        BuildingSavedData data = BuildingSavedData.get(level);

        BuildingState state = data.getBuilding(buildingId);
        if (state == null || !"mage_hut".equals(state.getCategory())) return false;
        UUID colonyId = state.getColonyId();
        if (colonyId == null) return false;

        if (!(level.getEntity(npcId) instanceof WandscapeNpc npc)) return false;
        NpcApi npcApi = WandscapeApis.getNpcApiSilently();
        if (!npc.isColonyNpc() || npcApi == null || !colonyId.equals(npcApi.getNpcColony(npcId))) return false;

        // 已经是这间小屋的入住者 → 幂等成功（顺带补 homeHut 指针）
        MageHutResident existing = data.getMageHutResident(buildingId);
        if (existing != null && npcId.equals(existing.npcId())) {
            npc.setHomeHutId(buildingId);
            return true;
        }

        // 顶替：目标小屋已有他人 → 移除旧入住记录并解除其活体入住者
        if (existing != null) {
            clearResident(data, level, buildingId);
        }

        // NPC 已绑他屋 → 先解旧绑定
        UUID oldHut = npc.getHomeHutId();
        if (oldHut != null && !oldHut.equals(buildingId)) {
            clearResident(data, level, oldHut);
        }

        float[] base = new float[NpcAttributes.ORDER.size()];
        for (AttributeType type : NpcAttributes.ORDER) {
            base[type.ordinal()] = NpcAttributes.baseFromFlat(type, npc.getBaseAttributeValue(type), npc.getLevel());
        }
        data.setMageHutResident(buildingId,
                new MageHutResident(npc.getUUID(), colonyId, npc.getNpcName(), npc.getLevel(), base));
        npc.setHomeHutId(buildingId);

        Log.info(TAG, "forceBind 法师 {} (Lv.{}) → 小屋 {}",
                npc.getNpcName(), npc.getLevel(), buildingId.toString().substring(0, 8));
        return true;
    }

    @Override
    public boolean forceUnbind(UUID buildingId) {
        ServerLevel level = getServerLevel();
        if (level == null || buildingId == null) return false;
        BuildingSavedData data = BuildingSavedData.get(level);
        if (data.getMageHutResident(buildingId) == null) return false;
        clearResident(data, level, buildingId);
        return true;
    }

    @Override
    public boolean forceUnbindNpc(UUID npcId) {
        UUID hut = getBindingHut(npcId);
        if (hut == null) return false;
        return forceUnbind(hut);
    }

    // ── 可调平衡值（委托 BalanceValues）──

    @Override
    public int getMageHutRestTicks() {
        return com.wsteam.wandscape.foundation.util.BalanceValues.mageHutRestTicks();
    }

    @Override
    public void setMageHutRestTicks(int v) {
        com.wsteam.wandscape.foundation.util.BalanceValues.setMageHutRestTicks(v);
    }

    /** 移除小屋入住记录；若其入住者实体在世，以该小屋为准清掉其 homeHutId。 */
    private static void clearResident(BuildingSavedData data, ServerLevel level, UUID buildingId) {
        MageHutResident r = data.getMageHutResident(buildingId);
        if (r != null && r.npcId() != null
                && level.getEntity(r.npcId()) instanceof WandscapeNpc npc
                && buildingId.equals(npc.getHomeHutId())) {
            npc.setHomeHutId(null);
        }
        data.removeMageHutResident(buildingId);
    }

    @Nullable
    private static ServerLevel getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }
}