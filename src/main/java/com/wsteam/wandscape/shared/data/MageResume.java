package com.wsteam.wandscape.shared.data;

import com.google.gson.annotations.SerializedName;

/**
 * A mage tourist who reached 100% satisfaction and left their resume at the tavern.
 * Stored in {@code TavernRecruitStorage} (SavedData), max 5 per colony.
 */
public record MageResume(
        @SerializedName("tourist_name") String touristName,
        int level,
        @SerializedName("max_mana") int maxMana,
        @SerializedName("mana_regen") int manaRegenRate,
        @SerializedName("spell_power") int spellPower,
        @SerializedName("skin_variant") int skinVariant,
        long timestamp
) {
    public MageResume {
        if (level < 1) level = 1;
        if (maxMana < 1) maxMana = 100;
        if (manaRegenRate < 1) manaRegenRate = 2;
        if (spellPower < 1) spellPower = 1;
        if (skinVariant < 0) skinVariant = 0;
    }

    /** Convert to a RecruitmentCandidate for the tavern GUI and NPC spawning. */
    public RecruitmentCandidate toCandidate() {
        return new RecruitmentCandidate(level, 40, maxMana, spellPower, manaRegenRate, java.util.List.of());
    }
}
