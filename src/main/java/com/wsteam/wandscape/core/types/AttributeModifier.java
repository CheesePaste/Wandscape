package com.wsteam.wandscape.core.types;

/**
 * A single modifier that adjusts an {@link AttributeType} by a given amount using a specific operation.
 */
public record AttributeModifier(
        AttributeType type,
        float amount,
        ModifierOperation operation
) {
}
