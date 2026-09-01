package com.wsteam.wandscape.content.npc.data;

import com.google.gson.annotations.SerializedName;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes;
/**
 * A mage tourist whose three bars were full and who left their resume at the tavern.
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
        @SerializedName("max_mana") float maxMana,
        @SerializedName("skin_variant") int skinVariant,
        long timestamp
) {
    public MageResume {
        if (level < 1) level = 1;
        if (maxHp < 1f) maxHp = NpcAttributes.defaultFor(NpcAttributes.AttributeType.MAX_HP);
        if (moveSpeed < 0.1f) moveSpeed = NpcAttributes.defaultFor(NpcAttributes.AttributeType.MOVE_SPEED);
        if (spellPower < 1f) spellPower = NpcAttributes.defaultFor(NpcAttributes.AttributeType.SPELL_POWER);
        if (workSpeed < 1f) workSpeed = NpcAttributes.defaultFor(NpcAttributes.AttributeType.WORK_SPEED);
        if (spellSpeed < 1f) spellSpeed = NpcAttributes.defaultFor(NpcAttributes.AttributeType.SPELL_SPEED);
        if (armorValue < 0f) armorValue = NpcAttributes.defaultFor(NpcAttributes.AttributeType.ARMOR_VALUE);
        if (maxMana < 1f) maxMana = NpcAttributes.defaultFor(NpcAttributes.AttributeType.MAX_MANA);
        if (skinVariant < 0) skinVariant = 0;
    }

    /** Convert to a RecruitmentCandidate for the tavern GUI and NPC spawning. */
    public RecruitmentCandidate toCandidate() {
        return new RecruitmentCandidate(level, maxHp, moveSpeed, spellPower,
                workSpeed, spellSpeed, armorValue, maxMana, java.util.List.of());
    }
}
