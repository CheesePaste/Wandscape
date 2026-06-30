package com.wsteam.wandscape.shared.data;

import java.util.Map;

/**
 * Per-building daily maintenance cost configuration.
 * Costs are keyed by {@link ElementType}. The daily settlement system
 * deducts these costs once per Minecraft day (~20 min real time).
 */
public record MaintenanceCostConfig(
        Map<ElementType, Integer> costs
) {
    public static final MaintenanceCostConfig NONE =
            new MaintenanceCostConfig(Map.of());

    public MaintenanceCostConfig {
        if (costs == null) costs = Map.of();
    }
}
