package com.wsteam.wandscape.core.component;

import com.wsteam.wandscape.core.types.AttributeModifier;
import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.core.types.EquipmentSlot;
import com.wsteam.wandscape.core.types.ModifierOperation;

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
        BASE_VALUES.put(AttributeType.RANGE, 1f);
        BASE_VALUES.put(AttributeType.MANA_COST_MULTIPLIER, 1f);
        BASE_VALUES.put(AttributeType.MAX_MANA, 100f);
        BASE_VALUES.put(AttributeType.MANA_REGEN, 5f);
        BASE_VALUES.put(AttributeType.MAX_HP, 20f);
        BASE_VALUES.put(AttributeType.MOVE_SPEED, 0.1f);
    }

    /** Default wand modifiers (range=1, mana_cost_mult=1.0 → no change from base). */
    private static final List<AttributeModifier> DEFAULT_WAND_MODIFIERS = List.of(
            new AttributeModifier(AttributeType.RANGE, 0f, ModifierOperation.ADDITION),
            new AttributeModifier(AttributeType.MANA_COST_MULTIPLIER, 0f, ModifierOperation.ADDITION)
    );

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
     * Compute the effective mana value used by the scheduler.
     * <p>
     * {@code effectiveMana = currentMana / manaCostMultiplier}
     * <p>
     * A lower multiplier means the NPC gets more effective mana from the same pool.
     */
    public float getEffectiveMana(float currentMana) {
        float mult = getAttribute(AttributeType.MANA_COST_MULTIPLIER);
        return mult > 0f ? currentMana / mult : currentMana;
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
     * Order of operations (aligned with Minecraft's AttributeModifier):
     * <ol>
     *   <li>Start with BASE_VALUES
     *   <li>Apply MULTIPLY_BASE: {@code base * (1 + sum(multBase))}
     *   <li>Apply ADDITION: {@code result + sum(add)}
     *   <li>Apply MULTIPLY_TOTAL: {@code result * (1 + sum(multTotal))}
     * </ol>
     */
    private void recalculateAll() {
        // Reset to base values
        effectiveAttributes.clear();
        effectiveAttributes.putAll(BASE_VALUES);

        // Accumulate modifiers by operation type
        EnumMap<AttributeType, Float> sumAdd = new EnumMap<>(AttributeType.class);
        EnumMap<AttributeType, Float> sumMultBase = new EnumMap<>(AttributeType.class);
        EnumMap<AttributeType, Float> sumMultTotal = new EnumMap<>(AttributeType.class);

        for (List<AttributeModifier> mods : equippedModifiers.values()) {
            for (AttributeModifier mod : mods) {
                switch (mod.operation()) {
                    case ADDITION -> sumAdd.merge(mod.type(), mod.amount(), Float::sum);
                    case MULTIPLY_BASE -> sumMultBase.merge(mod.type(), mod.amount(), Float::sum);
                    case MULTIPLY_TOTAL -> sumMultTotal.merge(mod.type(), mod.amount(), Float::sum);
                }
            }
        }

        // Apply modifiers in order: MULTIPLY_BASE → ADDITION → MULTIPLY_TOTAL
        for (AttributeType type : AttributeType.values()) {
            float base = BASE_VALUES.getOrDefault(type, 0f);
            float value = base;

            float multBase = sumMultBase.getOrDefault(type, 0f);
            value = base * (1f + multBase);

            float add = sumAdd.getOrDefault(type, 0f);
            value = value + add;

            float multTotal = sumMultTotal.getOrDefault(type, 0f);
            value = value * (1f + multTotal);

            effectiveAttributes.put(type, value);
        }
    }
}
