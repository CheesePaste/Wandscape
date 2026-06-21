package com.wsteam.wandscape.engine.road;

import com.wsteam.wandscape.Config;

/**
 * Configuration holder for the road system.
 * Reads TOML values from {@link Config} and maintains
 * road tier settings.
 *
 * <p>Future: load road tier blocks from data/wandscape/road_tiers.json
 * and surface rules from data/wandscape/road_rules/ when WandscapeDataLoader
 * supports arbitrary JSON categories.
 */
public final class RoadConfig {

    private static final RoadConfig INSTANCE = new RoadConfig();

    // Default block IDs per tier (hardcoded for V3 until JSON loading is ready)
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
     * Used when no surface rule matches.
     */
    public String getDefaultBlock(String tier) {
        if ("dirt".equals(tier)) return DIRT_SURFACE;
        return DIRT_SURFACE;
    }

    // Deprecated V1 intersection block methods — to be removed

    /** @deprecated V3: crossroads use same material, no special intersection block. */
    @Deprecated
    public String getSurfaceBlock(String tier) {
        return getDefaultBlock(tier);
    }

    /** @deprecated V3: crossroads use same material, no special intersection block. */
    @Deprecated
    public String getIntersectionBlock(String tier) {
        return getDefaultBlock(tier);
    }
}
