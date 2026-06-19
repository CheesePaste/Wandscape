package com.wsteam.wandscape.shared.api;

import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.shared.data.RecruitmentCandidate;

public interface TavernApi {
    List<RecruitmentCandidate> getCandidates(UUID tavernId);
    boolean refreshCandidates(UUID tavernId);
    boolean recruitCandidate(UUID tavernId, int index);
}
