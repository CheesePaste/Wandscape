package com.wsteam.wandscape.shared.data;

import com.google.gson.annotations.SerializedName;
/**
 * A mage tourist who reached 100% satisfaction and left their resume at the tavern.
 * Stored in {@code TavernRecruitStorage} (SavedData), max 5 per colony.
 * Carries the rolled attribute set (maxHp/moveSpeed/spellPower/workSpeed/spellSpeed/armorValue).
 */
public record MageResume(
        @SerializedName("tourist_name") String touristName,
        int level,
        @SerializedName("max_hp") float maxHp,
        @SerializedName("move_speed") float moveSpeed,
        @SerializedName("spell_power") float spellPower,
        @SerializedName("work_speed") float workSpeed,
        @SerializedName("spell_speed") float spellSpeed,
        @SerializedName("armor_value") float armorValue,
        @SerializedName("skin_variant") int skinVariant,
        long timestamp
) {
    public MageResume {
        if (level < 1) level = 1;
        if (maxHp < 1f) maxHp = 40f;
        if (moveSpeed < 0.1f) moveSpeed = 0.3f;
        if (spellPower < 1f) spellPower = 1f;
        if (workSpeed < 1f) workSpeed = 1f;
        if (spellSpeed < 1f) spellSpeed = 1f;
        if (armorValue < 0f) armorValue = 0f;
        if (skinVariant < 0) skinVariant = 0;
    }

    /** Convert to a RecruitmentCandidate for the tavern GUI and NPC spawning. */
    public RecruitmentCandidate toCandidate() {
        return new RecruitmentCandidate(level, maxHp, moveSpeed, spellPower,
                workSpeed, spellSpeed, armorValue, java.util.List.of());
    }
}
