package com.wsteam.wandscape.core.types;

import java.util.List;

/**
 * Equipment preset data, loaded from JSON by the engine layer.
 *
 * @param id          Unique identifier (e.g. "basic_wand")
 * @param displayName Human-readable name
 * @param slot        Which equipment slot this preset occupies
 * @param modifiers   Attribute modifiers granted when equipped
 * @param color       Visual color (hex string, e.g. "#FFD700")
 */
public record EquipmentPreset(
        String id,
        String displayName,
        EquipmentSlot slot,
        List<AttributeModifier> modifiers,
        String color
) {
}
