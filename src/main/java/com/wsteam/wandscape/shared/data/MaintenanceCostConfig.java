package com.wsteam.wandscape.shared.data;

import java.util.Map;

import com.google.gson.annotations.SerializedName;
/**
 * Per-building maintenance cost configuration. Costs are keyed by {@link ElementType}.
 * Interval is in ticks.
 */
public record MaintenanceCostConfig(
        @SerializedName("interval_ticks") int intervalTicks,
        Map<ElementType, Integer> costs
) {
    public static final int DEFAULT_INTERVAL_TICKS = 12000;

    public static final MaintenanceCostConfig NONE =
            new MaintenanceCostConfig(DEFAULT_INTERVAL_TICKS, Map.of());

    public MaintenanceCostConfig {
        if (costs == null) costs = Map.of();
        if (intervalTicks <= 0) intervalTicks = DEFAULT_INTERVAL_TICKS;
    }
}
