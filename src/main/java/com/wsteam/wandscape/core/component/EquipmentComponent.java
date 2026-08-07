package com.wsteam.wandscape.core.component;

import com.wsteam.wandscape.core.types.AttributeModifier;
import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.core.types.EquipmentSlot;
import com.wsteam.wandscape.core.types.ModifierOperation;
import com.wsteam.wandscape.core.types.NpcAttributes;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Equipment component for an NPC.
 * <p>
 * Manages equipped items across multiple {@link EquipmentSlot}s and computes
 * effective attribute values from base values plus equipment modifiers.
 * <p>
 * Currently only {@link EquipmentSlot#WAND} is populated; adding new slot types
 * requires no changes to this class beyond callers supplying new presets.
 */
public class EquipmentComponent {

    /** NPC base attribute values (no equipment). */
    private static final EnumMap<AttributeType, Float> BASE_VALUES = new EnumMap<>(AttributeType.class);
    static {
        BASE_VALUES.put(AttributeType.MAX_HP, 40f);
        BASE_VALUES.put(AttributeType.MOVE_SPEED, 0.3f);
        BASE_VALUES.put(AttributeType.SPELL_POWER, 1f);
        BASE_VALUES.put(AttributeType.WORK_SPEED, 1f);
        BASE_VALUES.put(AttributeType.SPELL_SPEED, 1f);
        BASE_VALUES.put(AttributeType.ARMOR_VALUE, 0f);
        BASE_VALUES.put(AttributeType.MAX_MANA, 200f);
    }

    /** Default wand modifiers (neutral — no change from base). */
    private static final List<AttributeModifier> DEFAULT_WAND_MODIFIERS = List.of(
            new AttributeModifier(AttributeType.SPELL_POWER, 0f, ModifierOperation.ADDITION)
    );

    /** Per-NPC base value overrides (from recruitment); falls back to {@link #BASE_VALUES}. */
    private final EnumMap<AttributeType, Float> baseOverrides = new EnumMap<>(AttributeType.class);

    /** Slot → equipped preset ID. */
    private final Map<EquipmentSlot, String> equipped = new EnumMap<>(EquipmentSlot.class);

    /** Slot → modifiers currently active. */
    private final Map<EquipmentSlot, List<AttributeModifier>> equippedModifiers = new EnumMap<>(EquipmentSlot.class);

    /** Cached effective attribute values. Recalculated on equip/unequip. */
    private final EnumMap<AttributeType, Float> effectiveAttributes = new EnumMap<>(AttributeType.class);

    /** Whether the default wand has been initialized. */
    private boolean hasDefaultWand;

    public EquipmentComponent() {
        recalculateAll();
    }

    /**
     * Seed per-NPC base attribute values (from recruitment). Overrides the static
     * defaults; re-computes effective values immediately.
     */
    public void seedBaseValues(NpcAttributes attrs) {
        baseOverrides.put(AttributeType.MAX_HP, attrs.maxHp());
        baseOverrides.put(AttributeType.MOVE_SPEED, attrs.moveSpeed());
        baseOverrides.put(AttributeType.SPELL_POWER, attrs.spellPower());
        baseOverrides.put(AttributeType.WORK_SPEED, attrs.workSpeed());
        baseOverrides.put(AttributeType.SPELL_SPEED, attrs.spellSpeed());
        baseOverrides.put(AttributeType.ARMOR_VALUE, attrs.armorValue());
        baseOverrides.put(AttributeType.MAX_MANA, attrs.maxMana());
        recalculateAll();
    }

    // ── Equipment operations ──

    /**
     * Equip an item in the given slot.
     *
     * @param slot      target slot
     * @param presetId  preset identifier (e.g. "basic_wand")
     * @param modifiers attribute modifiers granted by this equipment
     */
    public void equip(EquipmentSlot slot, String presetId, List<AttributeModifier> modifiers) {
        equipped.put(slot, presetId);
        equippedModifiers.put(slot, List.copyOf(modifiers));
        recalculateAll();
    }

    /**
     * Remove equipment from the given slot.
     *
     * @param slot slot to unequip
     */
    public void unequip(EquipmentSlot slot) {
        equipped.remove(slot);
        equippedModifiers.remove(slot);
        recalculateAll();
    }

    /**
     * Equip the default wand (no special bonuses).
     * Called during NPC initialization.
     */
    public void equipDefaultWand() {
        if (hasDefaultWand) return;
        equip(EquipmentSlot.WAND, "basic_wand", DEFAULT_WAND_MODIFIERS);
        hasDefaultWand = true;
    }

    // ── Queries ──

    /**
     * Whether the NPC has equipment in the given slot.
     */
    public boolean hasEquipment(EquipmentSlot slot) {
        return equipped.containsKey(slot);
    }

    /**
     * Get the preset ID equipped in the given slot, or {@code null} if empty.
     */
    public String getEquippedPreset(EquipmentSlot slot) {
        return equipped.get(slot);
    }

    /**
     * Get the effective value of an attribute (base + all equipment modifiers).
     */
    public float getAttribute(AttributeType type) {
        return effectiveAttributes.getOrDefault(type, 0f);
    }

    /**
     * Returns an unmodifiable view of the slot → presetId map.
     */
    public Map<EquipmentSlot, String> getAllEquipped() {
        return Collections.unmodifiableMap(equipped);
    }

    /**
     * Returns an unmodifiable view of the slot → modifiers map.
     */
    public Map<EquipmentSlot, List<AttributeModifier>> getAllModifiers() {
        return Collections.unmodifiableMap(equippedModifiers);
    }

    // ── Internal ──

    /**
     * Recalculate all effective attributes from scratch.
     * <p>
     * All equipment modifiers are additive: {@code effective = base + sum(modifiers)}.
     */
    private void recalculateAll() {
        effectiveAttributes.clear();

        EnumMap<AttributeType, Float> sumAdd = new EnumMap<>(AttributeType.class);
        for (List<AttributeModifier> mods : equippedModifiers.values()) {
            for (AttributeModifier mod : mods) {
                sumAdd.merge(mod.type(), mod.amount(), Float::sum);
            }
        }

        for (AttributeType type : AttributeType.values()) {
            float base = baseOverrides.getOrDefault(type, BASE_VALUES.getOrDefault(type, 0f));
            effectiveAttributes.put(type, base + sumAdd.getOrDefault(type, 0f));
        }
    }
}
