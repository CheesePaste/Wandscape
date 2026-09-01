package com.wsteam.wandscape.content.npc.types;

/**
 * A single modifier that adjusts an {@link AttributeType} by a given amount using a specific operation.
 */
public record NpcAttributeModifier(
        AttributeType type,
        float amount,
        ModifierOperation operation
) {
}
