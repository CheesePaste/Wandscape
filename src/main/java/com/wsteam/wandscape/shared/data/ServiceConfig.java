package com.wsteam.wandscape.shared.data;

import com.google.gson.annotations.SerializedName;

import java.util.Map;
/**
 * Service building configuration.
 * maxOccupancy of 0 means unlimited (only relevant for inn-type services).
 */
public record ServiceConfig(
        @SerializedName("energy_per_use") int energyPerUse,
        @SerializedName("element_output") Map<String, Integer> elementOutput,
        @SerializedName("max_occupancy") int maxOccupancy,
        @SerializedName("interaction_duration_ticks") int interactionDurationTicks
) {
    public static final ServiceConfig NONE = new ServiceConfig(0, Map.of(), 0, 0);

    public ServiceConfig {
        if (elementOutput == null) elementOutput = Map.of();
        if (energyPerUse < 0) energyPerUse = 0;
        if (maxOccupancy < 0) maxOccupancy = 0;
        if (interactionDurationTicks < 0) interactionDurationTicks = 0;
    }
}
