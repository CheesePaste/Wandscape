package com.wsteam.wandscape.shared.data;

import java.util.List;
public record RecruitmentCandidate(
    int level,
    int maxHealth,
    int maxMana,
    int spellPower,
    int manaRegen,
    List<String> starterWandIds
) {}
