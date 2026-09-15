package com.wsteam.wandscape.content.colony.exploration;

import com.wsteam.wandscape.content.element.data.ElementType;

import java.util.Collections;
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
        Map<ElementType, Long> maxElements
) {
    public ExplorationRewardRange {
        minElements = Collections.unmodifiableMap(new LinkedHashMap<>(minElements));
        maxElements = Collections.unmodifiableMap(new LinkedHashMap<>(maxElements));
    }

    /** Roll a random experience value within [minExp, maxExp]. */
    public int rollExp(Random random) {
        if (maxExp <= minExp) return minExp;
        return minExp + random.nextInt(maxExp - minExp + 1);
    }

    /** Roll random amounts for all expected elements within their respective ranges. */
    public Map<ElementType, Long> rollElements(Random random) {
        Map<ElementType, Long> result = new LinkedHashMap<>();
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
                result.put(type, val);
            }
        }
        return Collections.unmodifiableMap(result);
    }
}
