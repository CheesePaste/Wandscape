package com.wsteam.wandscape.core.types;

/**
 * Operation type for attribute modifiers, aligned with Minecraft's {@code AttributeModifier.Operation}.
 *
 * <ul>
 *   <li>{@code ADDITION}: {@code v = base + sum(add)}
 *   <li>{@code MULTIPLY_BASE}: {@code v = base * (1 + sum(multBase))}
 *   <li>{@code MULTIPLY_TOTAL}: {@code v = total * (1 + sum(multTotal))}
 * </ul>
 */
public enum ModifierOperation {
    ADDITION,
    MULTIPLY_BASE,
    MULTIPLY_TOTAL
}
