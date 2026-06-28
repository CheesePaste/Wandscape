package com.wsteam.wandscape.core.component;

import com.wsteam.wandscape.core.types.BehaviourLevel;
import com.wsteam.wandscape.core.types.BehaviourTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
/**
 * Pre-computed capability union of all wands an NPC carries.
 * Recalculated when wands are equipped/unequipped.
 * Stores the unioned capabilities and the list of equipped wand IDs.
 *
 * <p>Mutable — call {@link #equip} / {@link #unequip} to update
 * and replace the ECS component via {@code world.addComponent()}.
 */
public class WandCarrier {

    private final Map<BehaviourTag, BehaviourLevel> capabilities;
    private float bestManaEfficiency;
    private int maxRange;
    private final List<String> equippedWandIds;

    /**
     * Backward-compatible 3-arg constructor — creates a carrier with no
     * tracked wand IDs. Use {@link #equip} to add capabilities later.
     */
    public WandCarrier(Map<BehaviourTag, BehaviourLevel> capabilities,
                       float bestManaEfficiency, int maxRange) {
        this(capabilities, bestManaEfficiency, maxRange, List.of());
    }

    /** Full constructor with explicit capabilities and equipped wand list. */
    public WandCarrier(Map<BehaviourTag, BehaviourLevel> capabilities,
                       float bestManaEfficiency, int maxRange,
                       List<String> equippedWandIds) {
        this.capabilities = new HashMap<>(capabilities);
        this.bestManaEfficiency = bestManaEfficiency;
        this.maxRange = maxRange;
        this.equippedWandIds = new ArrayList<>(equippedWandIds);
    }

    /** Empty carrier — NPC has no wand. */
    public WandCarrier() {
        this.capabilities = new HashMap<>();
        this.bestManaEfficiency = 1.0f;
        this.maxRange = 0;
        this.equippedWandIds = new ArrayList<>();
    }

    /** Copy constructor. */
    public WandCarrier(WandCarrier other) {
        this.capabilities = new HashMap<>(other.capabilities);
        this.bestManaEfficiency = other.bestManaEfficiency;
        this.maxRange = other.maxRange;
        this.equippedWandIds = new ArrayList<>(other.equippedWandIds);
    }

    // ── Accessors ──

    public Map<BehaviourTag, BehaviourLevel> capabilities() {
        return Collections.unmodifiableMap(capabilities);
    }

    public float bestManaEfficiency() { return bestManaEfficiency; }

    public int maxRange() { return maxRange; }

    /** IDs of currently equipped wands (e.g. "wandscape:gatherer_wand"). */
    public List<String> equippedWandIds() {
        return Collections.unmodifiableList(equippedWandIds);
    }

    public boolean isEmpty() { return equippedWandIds.isEmpty(); }

    // ── Mutation ──

    /**
     * Equip a wand — merge its capabilities and track the wand ID.
     * Higher capability levels win; lower mana efficiency wins; larger range wins.
     */
    public void equip(String wandId, Map<BehaviourTag, BehaviourLevel> wandCaps,
                      float manaEfficiency, int range) {
        equippedWandIds.add(wandId);
        mergeCapabilities(wandCaps);
        if (manaEfficiency < this.bestManaEfficiency) {
            this.bestManaEfficiency = manaEfficiency;
        }
        if (range > this.maxRange) {
            this.maxRange = range;
        }
    }

    /**
     * Unequip a wand by ID. Recalculates all capabilities from scratch.
     * The caller must provide the remaining wands' preset data.
     */
    public void unequip(String wandId, Map<String, WandCapProvider> knownWands) {
        equippedWandIds.remove(wandId);
        recalculateFull(knownWands);
    }

    /**
     * Recalculate capabilities, efficiency, and range from the current
     * {@code equippedWandIds} list using the given provider map.
     * If knownWands is empty/doesn't contain an equipped ID, that wand's
     * contribution is silently dropped (orphaned wand cleanup).
     */
    public void recalculateFull(Map<String, WandCapProvider> knownWands) {
        capabilities.clear();
        bestManaEfficiency = 1.0f;
        maxRange = 0;

        for (String id : equippedWandIds) {
            WandCapProvider provider = knownWands.get(id);
            if (provider != null) {
                mergeCapabilities(provider.capabilities());
                if (provider.manaEfficiency() < bestManaEfficiency) {
                    bestManaEfficiency = provider.manaEfficiency();
                }
                if (provider.range() > maxRange) {
                    maxRange = provider.range();
                }
            }
        }
    }

    private void mergeCapabilities(Map<BehaviourTag, BehaviourLevel> wandCaps) {
        for (var entry : wandCaps.entrySet()) {
            BehaviourLevel existing = capabilities.get(entry.getKey());
            if (existing == null || entry.getValue().value() > existing.value()) {
                capabilities.put(entry.getKey(), entry.getValue());
            }
        }
    }

    // ── Capability query ──

    /** Get the highest level for a given behaviour tag, or 0 if absent. */
    public int level(BehaviourTag tag) {
        BehaviourLevel lv = capabilities.get(tag);
        return lv != null ? lv.value() : 0;
    }

    /** Check whether this carrier satisfies a set of requirements. */
    public boolean satisfies(Map<BehaviourTag, BehaviourLevel> requirements) {
        for (var entry : requirements.entrySet()) {
            if (level(entry.getKey()) < entry.getValue().value()) {
                return false;
            }
        }
        return true;
    }

    // ── Provider type ──

    /** Lightweight capability snapshot for recalculating after unequip. */
    public record WandCapProvider(
            Map<BehaviourTag, BehaviourLevel> capabilities,
            float manaEfficiency,
            int range
    ) {}

    // ── Constants ──

    /** Empty carrier — used for NPCs with no wand. */
    public static final WandCarrier EMPTY = new WandCarrier();
}
