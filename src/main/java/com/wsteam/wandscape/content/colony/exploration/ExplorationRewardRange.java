package com.wsteam.wandscape.content.colony.exploration;

import com.wsteam.wandscape.content.element.data.ElementType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Immutable reward range definition for a specific exploration region or chest tier.
 * Zero Minecraft imports: pure arithmetic data structure.
 */
public record ExplorationRewardRange(
        String regionName,
        int minExp,
        int maxExp,
        Map<ElementType, Long> minElements,
        Map<ElementType, Long> maxElements,
        boolean degenerate
) {
    /**
     * Fraction of the rolled element total that keeps the loot-table distribution.
     * The complementary share is spread randomly over all seven elements: chest loot is
     * heavily metal-based, so a pure loot-derived split starves the other six elements.
     */
    private static final double LOOT_SHARE = 0.5;

    /** Weight jitter of the random share: every element draws a weight in [MIN, MIN + SPAN). */
    private static final double RANDOM_WEIGHT_MIN = 0.5;
    private static final double RANDOM_WEIGHT_SPAN = 1.0;

    public ExplorationRewardRange {
        minElements = Collections.unmodifiableMap(new LinkedHashMap<>(minElements));
        maxElements = Collections.unmodifiableMap(new LinkedHashMap<>(maxElements));
    }

    /** Roll a random experience value within [minExp, maxExp]. */
    public int rollExp(Random random) {
        if (maxExp <= minExp) return minExp;
        return minExp + random.nextInt(maxExp - minExp + 1);
    }

    /**
     * Roll random amounts for all expected elements.
     * Half of the total follows the loot-table distribution, the other half is split
     * randomly across all seven elements, so no chest is a single-element payout.
     */
    public Map<ElementType, Long> rollElements(Random random) {
        Map<ElementType, Long> lootRolled = new EnumMap<>(ElementType.class);
        for (Map.Entry<ElementType, Long> entry : maxElements.entrySet()) {
            ElementType type = entry.getKey();
            long max = entry.getValue();
            long min = minElements.getOrDefault(type, 0L);
            if (max <= 0) continue;
            long val;
            if (max <= min) {
                val = max;
            } else {
                val = min + (long) (random.nextDouble() * (max - min + 1));
            }
            if (val > 0) {
                lootRolled.put(type, val);
            }
        }

        long total = 0;
        for (long val : lootRolled.values()) total += val;
        if (total <= 0) return Collections.emptyMap();

        // Keep the loot-derived share, pool the rest for the random split. The pool is
        // derived from the total so rounding never invents or loses element value.
        Map<ElementType, Long> kept = new EnumMap<>(ElementType.class);
        long randomPool = total;
        for (Map.Entry<ElementType, Long> entry : lootRolled.entrySet()) {
            long amount = Math.min(Math.round(entry.getValue() * LOOT_SHARE), randomPool);
            if (amount > 0) {
                kept.put(entry.getKey(), amount);
                randomPool -= amount;
            }
        }
        Map<ElementType, Long> randomShare = splitRandomly(randomPool, random);

        Map<ElementType, Long> result = new LinkedHashMap<>();
        for (ElementType type : ElementType.values()) {
            long amount = kept.getOrDefault(type, 0L) + randomShare.getOrDefault(type, 0L);
            if (amount > 0) result.put(type, amount);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Split a pool across the seven element types by independent random weights, so every
     * element has the same expected value but amounts differ from chest to chest.
     * A largest-remainder pass makes the result sum to exactly {@code pool}.
     */
    private static Map<ElementType, Long> splitRandomly(long pool, Random random) {
        Map<ElementType, Long> share = new EnumMap<>(ElementType.class);
        if (pool <= 0) return share;

        ElementType[] types = ElementType.values();
        double[] weights = new double[types.length];
        double totalWeight = 0;
        for (int i = 0; i < types.length; i++) {
            weights[i] = RANDOM_WEIGHT_MIN + random.nextDouble() * RANDOM_WEIGHT_SPAN;
            totalWeight += weights[i];
        }

        double[] remainders = new double[types.length];
        long assigned = 0;
        for (int i = 0; i < types.length; i++) {
            double exact = pool * weights[i] / totalWeight;
            long whole = (long) exact;
            share.put(types[i], whole);
            remainders[i] = exact - whole;
            assigned += whole;
        }

        // Fewer leftover points than element types, and each type is topped up at most once.
        for (long left = pool - assigned; left > 0; left--) {
            int best = 0;
            for (int i = 1; i < types.length; i++) {
                if (remainders[i] > remainders[best]) best = i;
            }
            share.merge(types[best], 1L, Long::sum);
            remainders[best] = -1.0;
        }
        return share;
    }
}
