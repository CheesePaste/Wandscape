package com.wsteam.wandscape.core.types;

/**
 * Operation type for attribute modifiers.
 * <p>
 * Effective value follows vanilla attribute order:
 * {@code effective = (base + Σ ADDITION) × (1 + Σ MULTIPLY_BASE)}.
 * Most equipment grants are additive ({@link #ADDITION}); percentage grants
 * (e.g. Iron's Spells +25% movement speed) use {@link #MULTIPLY_BASE} so the
 * boost stays correct when the base value is re-seeded (mage-hut training).
 */
public enum ModifierOperation {
    ADDITION,
    MULTIPLY_BASE
}
