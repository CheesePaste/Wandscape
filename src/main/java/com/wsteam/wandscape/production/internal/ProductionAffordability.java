package com.wsteam.wandscape.production.internal;

import com.wsteam.wandscape.shared.data.ElementType;

import java.util.Map;

/**
 * Computes how many units of a recipe can be afforded with the colony's current
 * element stock.
 *
 * <p>There is no artificial per-operation cap: the true affordability is the bound,
 * so the client quantity stepper can go beyond one stack (64) when the stock allows.
 * Returns 0 when nothing is affordable (the caller uses 0 to mark a recipe locked by
 * "elements").
 */
public final class ProductionAffordability {

    private ProductionAffordability() {
    }

    /** Maximum multiples of {@code costPerUnit} affordable with {@code elements}. */
    public static int computeMaxAffordable(Map<ElementType, Long> costPerUnit, Map<ElementType, Long> elements) {
        int max = Integer.MAX_VALUE;
        for (var entry : costPerUnit.entrySet()) {
            long cost = entry.getValue();
            if (cost <= 0) continue;
            long available = elements.getOrDefault(entry.getKey(), 0L);
            long canAfford = available / cost;
            if (canAfford < max) max = (int) Math.min(canAfford, Integer.MAX_VALUE);
        }
        return Math.max(0, max);
    }
}
