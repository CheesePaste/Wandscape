package com.wsteam.wandscape.content.npc.data;

import java.util.List;
/**
 * A recruit-able mage profile. Carries the attribute set that will seed the
 * NPC's ECS base values on spawn.
 */
public record RecruitmentCandidate(
    int level,
    float maxHp,
    float moveSpeed,
    float spellPower,
    float workSpeed,
    float spellSpeed,
    float armorValue,
    float maxMana,
    List<String> starterWandIds
) {}
