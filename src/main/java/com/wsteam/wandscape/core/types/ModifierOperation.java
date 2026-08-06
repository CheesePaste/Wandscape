package com.wsteam.wandscape.core.types;

/**
 * Operation type for attribute modifiers.
 * <p>
 * Only {@code ADDITION} is supported: {@code v = base + sum(add)}.
 * All equipment grants are additive for simplicity and readability.
 */
public enum ModifierOperation {
    ADDITION
}
