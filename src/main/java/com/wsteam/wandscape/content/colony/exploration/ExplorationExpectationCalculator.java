package com.wsteam.wandscape.content.colony.exploration;

import com.wsteam.wandscape.content.element.data.ElementType;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an element value vector into a reward range. Pure arithmetic logic with zero
 * Minecraft dependencies.
 *
 * <p>The value vector — how much of each element a chest is worth — is what differs per
 * {@link ExplorationRewardSpec.Mode}: sampled from the loot table, declared in JSON, or
 * both. Everything after that (the EXP conversion, the danger multiplier, the variance
 * spread) is identical for every mode.
 */
public final class ExplorationExpectationCalculator {

    private ExplorationExpectationCalculator() {}

    /** Per-element sum of two value vectors; the additive mode's "sampled + declared". */
    public static Map<ElementType, Long> add(Map<ElementType, Long> a, Map<ElementType, Long> b) {
        if (b == null || b.isEmpty()) return a == null ? Map.of() : a;
        if (a == null || a.isEmpty()) return b;

        Map<ElementType, Long> merged = new LinkedHashMap<>(a);
        for (Map.Entry<ElementType, Long> entry : b.entrySet()) {
            merged.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
        return merged;
    }

    /** True when the vector carries no value at all, i.e. every item was unpriced. */
    public static boolean isDegenerate(Map<ElementType, Long> value) {
        if (value == null || value.isEmpty()) return true;
        for (long v : value.values()) {
            if (v > 0) return false;
        }
        return true;
    }

    /**
     * Build the reward range from a value vector.
     *
     * <p>EXP is the vector's total divided by {@code expRatio} and scaled by
     * {@code dangerMultiplier}; each element keeps its own share of the vector, spread by
     * {@code variance}. A vector with no value falls back to {@link #createFallback} and
     * is reported as degenerate by {@link #isDegenerate} — callers that persist the result
     * must mark it.
     *
     * @param regionName display name of the region
     * @param value      element value vector (expected worth of one chest)
     * @param spec       payout rule supplying ratio, danger and variance
     */
    public static ExplorationRewardRange fromValue(
            String regionName, Map<ElementType, Long> value, ExplorationRewardSpec spec) {

        if (isDegenerate(value)) {
            return createFallback(regionName, spec);
        }

        long totalElementValue = 0;
        for (long v : value.values()) totalElementValue += v;

        double safeRatio = spec.expRatio() <= 0 ? ExplorationRewardSpec.DEFAULT_EXP_RATIO : spec.expRatio();
        double safeDanger = Math.max(0.1, spec.dangerMultiplier());
        double safeVariance = Math.max(0.0, Math.min(0.9, spec.variance()));

        double baseExp = (totalElementValue / safeRatio) * safeDanger;
        int minExp = (int) Math.max(15, Math.round(baseExp * (1.0 - safeVariance)));
        int maxExp = (int) Math.max(minExp, Math.round(baseExp * (1.0 + safeVariance)));

        Map<ElementType, Long> minElements = new LinkedHashMap<>();
        Map<ElementType, Long> maxElements = new LinkedHashMap<>();
        for (Map.Entry<ElementType, Long> entry : value.entrySet()) {
            if (entry.getValue() <= 0) continue;
            long min = Math.max(1, Math.round(entry.getValue() * (1.0 - safeVariance)));
            long max = Math.max(min, Math.round(entry.getValue() * (1.0 + safeVariance)));
            minElements.put(entry.getKey(), min);
            maxElements.put(entry.getKey(), max);
        }

        return new ExplorationRewardRange(regionName, minExp, maxExp, minElements, maxElements, false, spec.lootShare());
    }

    /**
     * What a chest pays when nothing in its loot table could be priced — typically another
     * mod's items with no {@code element_mappings} entry.
     *
     * <p>Nothing. A flat consolation payout (it used to be {@code 50 × danger} EXP and a
     * handful of earth) only made an unpriced chest look like a cheap one, which hides the
     * real problem: the loot is invisible to the element economy. Paying zero is the honest
     * answer, and {@code degenerate} marks it so the caller can stay quiet instead of
     * celebrating a payout of nothing.
     */
    private static ExplorationRewardRange createFallback(String regionName, ExplorationRewardSpec spec) {
        return new ExplorationRewardRange(regionName, 0, 0, Map.of(), Map.of(), true, spec.lootShare());
    }
}
