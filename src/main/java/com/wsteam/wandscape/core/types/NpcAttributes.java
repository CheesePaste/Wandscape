package com.wsteam.wandscape.core.types;

/**
 * Base attribute values for an NPC, carried from recruitment through the ECS.
 * All equipment grants are additive on top of these.
 */
public record NpcAttributes(
        float maxHp,
        float moveSpeed,
        float spellPower,
        float workSpeed,
        float spellSpeed,
        float armorValue,
        float maxMana
) {
    /** Defaults matching current NPC behavior (no equipment). */
    public static NpcAttributes defaults() {
        return new NpcAttributes(30f, 0.3f, 1f, 1f, 1f, 6f, 200f);
    }
}
