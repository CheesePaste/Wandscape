package com.wsteam.wandscape.content.colony.exploration;

import com.wsteam.wandscape.content.element.data.ElementType;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calculates mathematical expectation and reward range from Monte Carlo loot samples.
 * Pure arithmetic logic with zero Minecraft dependencies.
 */
public final class ExplorationExpectationCalculator {

    private ExplorationExpectationCalculator() {}

    /**
     * Compute the exploration reward range from a list of sample draws.
     *
     * @param regionName user-friendly name of the region
     * @param sampleDraws list of element totals from simulated loot draws
     * @param dangerMultiplier hazard factor of the region (e.g. 1.0 for dungeon, 2.5 for ancient city)
     * @param variance spread ratio for min/max ranges (e.g. 0.25 for ±25%)
     * @param elementToExpRatio how many element points convert to 1 base colony EXP (e.g. 15.0)
     * @return an immutable {@link ExplorationRewardRange}
     */
    public static ExplorationRewardRange calculate(
            String regionName,
            List<Map<ElementType, Long>> sampleDraws,
            double dangerMultiplier,
            double variance,
            double elementToExpRatio) {

        if (sampleDraws == null || sampleDraws.isEmpty()) {
            return createFallback(regionName, dangerMultiplier, variance);
        }

        int sampleCount = sampleDraws.size();
        Map<ElementType, Long> elementSums = new EnumMap<>(ElementType.class);

        for (Map<ElementType, Long> draw : sampleDraws) {
            if (draw == null) continue;
            for (Map.Entry<ElementType, Long> entry : draw.entrySet()) {
                elementSums.merge(entry.getKey(), entry.getValue(), Long::sum);
            }
        }

        Map<ElementType, Long> avgElements = new LinkedHashMap<>();
        long totalElementValue = 0;

        for (ElementType type : ElementType.values()) {
            long sum = elementSums.getOrDefault(type, 0L);
            long avg = Math.round((double) sum / sampleCount);
            if (avg > 0) {
                avgElements.put(type, avg);
                totalElementValue += avg;
            }
        }

        if (totalElementValue <= 0) {
            return createFallback(regionName, dangerMultiplier, variance);
        }

        double safeRatio = elementToExpRatio <= 0 ? 15.0 : elementToExpRatio;
        double safeDanger = Math.max(0.1, dangerMultiplier);
        double safeVariance = Math.max(0.0, Math.min(0.9, variance));

        double baseExp = (totalElementValue / safeRatio) * safeDanger;
        int minExp = (int) Math.max(15, Math.round(baseExp * (1.0 - safeVariance)));
        int maxExp = (int) Math.max(minExp, Math.round(baseExp * (1.0 + safeVariance)));

        Map<ElementType, Long> minElements = new LinkedHashMap<>();
        Map<ElementType, Long> maxElements = new LinkedHashMap<>();

        for (Map.Entry<ElementType, Long> entry : avgElements.entrySet()) {
            ElementType type = entry.getKey();
            long avg = entry.getValue();
            long min = Math.max(1, Math.round(avg * (1.0 - safeVariance)));
            long max = Math.max(min, Math.round(avg * (1.0 + safeVariance)));
            minElements.put(type, min);
            maxElements.put(type, max);
        }

        return new ExplorationRewardRange(regionName, minExp, maxExp, minElements, maxElements);
    }

    private static ExplorationRewardRange createFallback(String regionName, double dangerMultiplier, double variance) {
        double safeDanger = Math.max(0.5, dangerMultiplier);
        double safeVariance = Math.max(0.0, Math.min(0.9, variance));

        int baseExp = (int) Math.round(50 * safeDanger);
        int minExp = (int) Math.max(15, Math.round(baseExp * (1.0 - safeVariance)));
        int maxExp = (int) Math.max(minExp, Math.round(baseExp * (1.0 + safeVariance)));

        Map<ElementType, Long> minElements = Map.of(ElementType.EARTH, 10L);
        Map<ElementType, Long> maxElements = Map.of(ElementType.EARTH, 30L);

        return new ExplorationRewardRange(regionName, minExp, maxExp, minElements, maxElements);
    }
}
