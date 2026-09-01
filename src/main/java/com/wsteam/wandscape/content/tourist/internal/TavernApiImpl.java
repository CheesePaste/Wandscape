package com.wsteam.wandscape.content.tourist.internal;

import com.wsteam.wandscape.api.TavernApi;
import com.wsteam.wandscape.api.WarehouseApi;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.content.npc.data.MageResume;
import com.wsteam.wandscape.content.npc.data.RecruitmentCandidate;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.foundation.registry.WandscapeConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Implementation of {@link TavernApi}.
 * Delegates mage resume storage to {@link TavernRecruitStorage} (SavedData).
 */
public class TavernApiImpl implements TavernApi {
    private static final String TAG = "TavernApiImpl";

    @Nullable
    private TavernRecruitStorage storage;

    public void setStorage(TavernRecruitStorage storage) {
        this.storage = storage;
    }

    private TavernRecruitStorage getStorage() {
        if (storage != null) return storage;
        ServerLevel level = getServerLevel();
        if (level == null) return null;
        storage = TavernRecruitStorage.getOrCreate(level);
        return storage;
    }

    @Override
    public List<RecruitmentCandidate> getCandidates(UUID tavernId) {
        // Generic NPC recruitment (not mage) — placeholder for future expansion
        return List.of();
    }

    @Override
    public boolean refreshCandidates(UUID tavernId) {
        return false;
    }

    @Override
    public boolean recruitCandidate(UUID tavernId, int index) {
        return false;
    }

    @Override
    public void receiveMageResume(UUID colonyId, String touristName, int level,
                                   float maxHp, float moveSpeed, float spellPower,
                                   float workSpeed, float spellSpeed, float armorValue,
                                   float maxMana, int skinVariant) {
        TavernRecruitStorage s = getStorage();
        if (s == null) return;

        MageResume resume = new MageResume(touristName, level, maxHp, moveSpeed, spellPower,
                workSpeed, spellSpeed, armorValue, maxMana, skinVariant, System.currentTimeMillis());
        s.addResume(colonyId, resume);
        Log.info(TAG, "[Tourist] Received mage resume: {} (Lv.{}) for colony {}",
                touristName, level, colonyId.toString().substring(0, 8));
    }

    @Override
    public List<MageResume> getMageResumes(UUID colonyId) {
        TavernRecruitStorage s = getStorage();
        if (s == null) return List.of();
        List<MageResume> resumes = new ArrayList<>(s.getResumes(colonyId));
        // Reverse so newest is first
        java.util.Collections.reverse(resumes);
        return resumes;
    }

    @Override
    public MageResume recruitMage(UUID tavernId, UUID colonyId, int index) {
        TavernRecruitStorage s = getStorage();
        if (s == null) return null;
        List<MageResume> resumes = new ArrayList<>(s.getResumes(colonyId));
        java.util.Collections.reverse(resumes);
        if (index < 0 || index >= resumes.size()) return null;
        MageResume resume = s.takeResume(colonyId, resumes.size() - 1 - index);
        Log.info(TAG, "[Tourist] Recruited mage {} from colony {}",
                resume != null ? resume.touristName() : "null",
                colonyId.toString().substring(0, 8));
        return resume;
    }

    @Override
    public MageResume rejectMage(UUID colonyId, int index) {
        TavernRecruitStorage s = getStorage();
        if (s == null) return null;
        List<MageResume> resumes = new ArrayList<>(s.getResumes(colonyId));
        java.util.Collections.reverse(resumes);
        if (index < 0 || index >= resumes.size()) return null;
        MageResume resume = s.takeResume(colonyId, resumes.size() - 1 - index);
        if (resume != null) {
            Log.info(TAG, "[Tourist] Rejected mage resume {} for colony {}",
                    resume.touristName(), colonyId.toString().substring(0, 8));
        }
        return resume;
    }

    @Override
    public int getRecruitCount(UUID colonyId) {
        TavernRecruitStorage s = getStorage();
        return s != null ? s.getRecruitCount(colonyId) : 0;
    }

    @Override
    public boolean canAffordRecruit(UUID colonyId) {
        TavernRecruitStorage s = getStorage();
        if (s == null) return false;
        if (s.getRecruitCount(colonyId) == 0) return true; // 首次免费
        WarehouseApi wh = WandscapeApis.getWarehouseApiSilently();
        return wh != null && ElementType.allEnough(wh.getAllElements(colonyId),
                WandscapeConstants.TAVERN_RECRUIT_COST_PER_ELEMENT);
    }

    @Override
    public boolean chargeRecruit(UUID colonyId) {
        TavernRecruitStorage s = getStorage();
        if (s == null) return false;
        long cost = WandscapeConstants.TAVERN_RECRUIT_COST_PER_ELEMENT;
        if (s.getRecruitCount(colonyId) == 0) {
            s.incrementRecruitCount(colonyId); // 首次免费
            return true;
        }
        WarehouseApi wh = WandscapeApis.getWarehouseApiSilently();
        if (wh == null) return false;
        if (!ElementType.allEnough(wh.getAllElements(colonyId), cost)) return false;
        for (ElementType t : ElementType.values()) {
            wh.consumeElement(colonyId, t, cost);
        }
        s.incrementRecruitCount(colonyId);
        return true;
    }

    @Nullable
    private static ServerLevel getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }
}
