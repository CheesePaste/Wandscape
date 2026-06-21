package com.wsteam.wandscape.engine.road;

import com.wsteam.wandscape.Config;

/**
 * Configuration holder for the road system.
 * Reads TOML values from {@link Config}.
 */
public final class RoadConfig {

    private static final RoadConfig INSTANCE = new RoadConfig();

    // Default block per tier (hardcoded until JSON loading is ready)
    private static final String DIRT_SURFACE = "minecraft:dirt_path";

    private RoadConfig() {}

    public static RoadConfig getInstance() {
        return INSTANCE;
    }

    // ---- TOML config ----

    public int getBuildingThreshold() {
        return Config.ROAD_BUILDING_THRESHOLD.get();
    }

    public int getSegmentMaxLength() {
        return Config.ROAD_SEGMENT_MAX_LENGTH.get();
    }

    public int getDefaultWidth() {
        return Config.ROAD_DEFAULT_WIDTH.get();
    }

    // ---- Tier defaults ----

    /**
     * Get the fallback surface block for a tier.
     * Used by {@link RoadBuilder#applyVariation} as the base block
     * before vanilla-style rules are applied.
     */
    public String getDefaultBlock(String tier) {
        if ("dirt".equals(tier)) return DIRT_SURFACE;
        return DIRT_SURFACE;
    }
}
