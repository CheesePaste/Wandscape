package com.wsteam.wandscape.core.types;

import javax.annotation.Nullable;

/**
 * Behaviour tags that define what a wand can do.
 * Used as keys in WandCarrier capability maps and task requirements.
 */
public enum BehaviourTag {
    BUILDING,
    FARMING,
    MINING,
    LOGGING,
    CRAFTING,
    GATHERING,
    RITUAL,
    ENTITY_INTERACTION;

    /** Map JSON key back to enum constant. Returns null for unknown keys. */
    @Nullable
    public static BehaviourTag fromKey(String key) {
        return switch (key) {
            case "building"           -> BUILDING;
            case "farming"            -> FARMING;
            case "mining"             -> MINING;
            case "logging"            -> LOGGING;
            case "crafting"           -> CRAFTING;
            case "gathering"          -> GATHERING;
            case "ritual"             -> RITUAL;
            case "entity_interaction" -> ENTITY_INTERACTION;
            default                   -> null;
        };
    }
}
