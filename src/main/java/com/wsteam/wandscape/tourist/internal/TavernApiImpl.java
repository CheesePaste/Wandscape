package com.wsteam.wandscape.tourist.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.shared.api.TavernApi;
import com.wsteam.wandscape.shared.data.MageResume;
import com.wsteam.wandscape.shared.data.RecruitmentCandidate;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import com.wsteam.wandscape.shared.log.Log;

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
                                   int maxMana, int manaRegenRate, int spellPower, int skinVariant) {
        TavernRecruitStorage s = getStorage();
        if (s == null) return;

        MageResume resume = new MageResume(touristName, level, maxMana,
                manaRegenRate, spellPower, skinVariant, System.currentTimeMillis());
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

    @Nullable
    private static ServerLevel getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }
}
