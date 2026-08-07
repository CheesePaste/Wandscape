package com.wsteam.wandscape.shared.api;

import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.shared.data.MageResume;
import com.wsteam.wandscape.shared.data.RecruitmentCandidate;
public interface TavernApi {
    List<RecruitmentCandidate> getCandidates(UUID tavernId);
    boolean refreshCandidates(UUID tavernId);
    boolean recruitCandidate(UUID tavernId, int index);

    /**
     * Called when a mage tourist departs at 100% satisfaction.
     * Stores their rolled attributes (resume) in the colony's tavern recruitment pool.
     */
    void receiveMageResume(UUID colonyId, String touristName, int level,
                           float maxHp, float moveSpeed, float spellPower,
                           float workSpeed, float spellSpeed, float armorValue,
                           float maxMana, int skinVariant);

    /** Returns mage resumes available at a tavern, newest first. */
    List<MageResume> getMageResumes(UUID colonyId);

    /** Recruit a mage by resume index. Returns the resume data or null if invalid. */
    MageResume recruitMage(UUID tavernId, UUID colonyId, int index);
}
