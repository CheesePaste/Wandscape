package com.wsteam.wandscape.shared.data;

import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.core.types.AttributeType;

/**
 * Pure per-attribute rules for the Mage Hut progression model.
 *
 * <p>Each of the seven mage attributes has a base range {@code [lower, upper]}
 * (the rolled value at level 1), a per-level additive bonus, and a per-train
 * increment. Training is uniform at {@link #MAX_TRAIN_STEPS} steps per attribute
 * (increment = range / 20); the cost of one training step grows exponentially by
 * step index, paid in a per-attribute pair of elements, while a level-up cost is
 * linear and paid in all seven elements evenly. A mage's effective attribute is:
 *
 * <pre>
 *   effective = base + perLevel × (level − 1) + equipBonus
 * </pre>
 *
 * where {@code base} starts as the rolled value and training raises it toward
 * {@code upper} (capped there). Level bonus and equipment bonus are always
 * additive and independent of training, so they can push the effective value
 * above {@code upper}.
 *
 * <p>This class is pure Java with zero MC runtime dependency: the MC-dependent
 * {@link AttributeType} is used only as an enum key.
 */
public final class MageHutAttributes {
    private MageHutAttributes() {}

    /** Per-attribute curve: base range, per-level bonus, per-train step. */
    public record AttrSpec(float lower, float upper, float perLevel, float trainStep) {}

    private static final float EPS = 1e-4f;

    private static final Map<AttributeType, AttrSpec> SPECS = Map.of(
            AttributeType.MAX_HP,       new AttrSpec(20f, 40f,  2f,    1f),
            AttributeType.MOVE_SPEED,   new AttrSpec(0.2f, 0.4f, 0.02f, 0.01f),
            AttributeType.SPELL_POWER,  new AttrSpec(0.5f, 1.5f, 0.05f, 0.05f),
            AttributeType.WORK_SPEED,   new AttrSpec(0.5f, 1.5f, 0.05f, 0.05f),
            AttributeType.SPELL_SPEED,  new AttrSpec(0.5f, 1.5f, 0.05f, 0.05f),
            AttributeType.ARMOR_VALUE,  new AttrSpec(0f,   10f,  0.5f,  0.5f),
            AttributeType.MAX_MANA,     new AttrSpec(150f, 250f, 15f,   5f));

    /** All seven attributes in panel display order (index 0..6). */
    public static final List<AttributeType> ORDER = List.of(
            AttributeType.MAX_HP,
            AttributeType.MOVE_SPEED,
            AttributeType.SPELL_POWER,
            AttributeType.WORK_SPEED,
            AttributeType.SPELL_SPEED,
            AttributeType.ARMOR_VALUE,
            AttributeType.MAX_MANA
    );

    /** The spec for one attribute. */
    public static AttrSpec spec(AttributeType type) {
        return SPECS.get(type);
    }

    public static float lower(AttributeType type) { return SPECS.get(type).lower(); }
    public static float upper(AttributeType type) { return SPECS.get(type).upper(); }
    public static float perLevel(AttributeType type) { return SPECS.get(type).perLevel(); }
    public static float trainStep(AttributeType type) { return SPECS.get(type).trainStep(); }

    // ── Cost model ──

    /** Training cost curve: step-1 per-element cost and exponential growth per step. */
    public static final double TRAIN_BASE = 500.0;
    public static final double TRAIN_GROWTH = 1.28;

    /** Level-up cost: per element × (level+1), all seven elements consumed evenly. */
    public static final long UPGRADE_BASE = 150;

    /** Uniform training steps per attribute (each trains 20 times from lower to upper). */
    public static final int MAX_TRAIN_STEPS = 20;

    /** Per-attribute training element set (2 distinct elements each; all 7 elements appear twice). */
    private static final Map<AttributeType, List<ElementType>> TRAIN_ELEMENTS = Map.of(
            AttributeType.MAX_HP,      List.of(ElementType.EARTH, ElementType.METAL),
            AttributeType.MOVE_SPEED,  List.of(ElementType.WOOD, ElementType.WIND),
            AttributeType.SPELL_POWER, List.of(ElementType.FIRE, ElementType.DARK),
            AttributeType.WORK_SPEED,  List.of(ElementType.EARTH, ElementType.WOOD),
            AttributeType.SPELL_SPEED, List.of(ElementType.WIND, ElementType.WATER),
            AttributeType.ARMOR_VALUE, List.of(ElementType.METAL, ElementType.DARK),
            AttributeType.MAX_MANA,    List.of(ElementType.FIRE, ElementType.WATER));

    /** Elements consumed by one training step of the given attribute. */
    public static List<ElementType> trainElements(AttributeType type) {
        return TRAIN_ELEMENTS.get(type);
    }

    /**
     * 0-based training step index for a base value, clamped to
     * {@code [0, MAX_TRAIN_STEPS-1]}. Step 0 (base at lower) is the cheapest,
     * step 19 (base near upper) the most expensive.
     */
    public static int trainStepIndex(AttributeType type, float base) {
        int idx = Math.round((base - lower(type)) / trainStep(type));
        return Math.max(0, Math.min(MAX_TRAIN_STEPS - 1, idx));
    }

    /** Per-element cost of the next training step from the given base. */
    public static long trainCostPerElement(AttributeType type, float base) {
        return Math.round(TRAIN_BASE * Math.pow(TRAIN_GROWTH, trainStepIndex(type, base)));
    }

    /** Elements consumed by a mage level-up: all seven, evenly. */
    public static List<ElementType> upgradeElements() {
        return List.of(ElementType.values());
    }

    /** Per-element cost of leveling from {@code level} to {@code level + 1}. */
    public static long upgradeCostPerElement(int level) {
        return UPGRADE_BASE * (Math.max(0, level) + 1L);
    }

    /**
     * Effective attribute = base + perLevel × (level−1) + equipBonus.
     * {@code base} is the current (possibly trained) base; it is not clamped here
     * because an older mage whose rolled value already exceeded {@code upper}
     * (historical baked level bonus) must keep its full value.
     */
    public static float computeEffective(AttributeType type, float base, int level, float equipBonus) {
        return base + perLevel(type) * Math.max(0, level - 1) + equipBonus;
    }

    /**
     * Whether the base can still be trained one step (strictly below the upper
     * bound). Uses an epsilon to avoid float-equality dead zones.
     */
    public static boolean canTrain(AttributeType type, float base) {
        return base < upper(type) - EPS;
    }

    /** The next trained base (capped at the upper bound). */
    public static float trainedValue(AttributeType type, float base) {
        return Math.min(base + trainStep(type), upper(type));
    }

    /**
     * Reconstruct the level-1 base from a flat rolled value that already embeds
     * {@code perLevel × (level − 1)}. The result is not clamped so that history
     * (baked bonus from a high-level spawn) is preserved.
     */
    public static float baseFromFlat(AttributeType type, float flat, int level) {
        return flat - perLevel(type) * Math.max(0, level - 1);
    }

    /**
     * A mage may level up until its level reaches {@code colonyLevel + 1} —
     * a level-30 colony can therefore promote mages up to level 31 (where a
     * maxed base HP hits exactly 100: 40 + 2 × 30).
     */
    public static boolean canLevelUp(int level, int colonyLevel) {
        return level <= colonyLevel;
    }
}
