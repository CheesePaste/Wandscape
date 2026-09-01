package com.wsteam.wandscape.content.npc.types;

import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;

/**
 * A single modifier that adjusts an {@link AttributeType} by a given amount using a specific operation.
 */
public record NpcAttributeModifier(
        AttributeType type,
        float amount,
        ModifierOperation operation
) {
}
