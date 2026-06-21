package com.wsteam.wandscape.engine.road;

import com.wsteam.wandscape.Config;

/**
 * Configuration holder for the road system.
 * Reads TOML values from {@link Config} and maintains
 * road tier block mappings (hardcoded defaults for V1).
 *
 * <p>TODO: Load road tier blocks from data/wandscape/road_tiers.json
 * when additional tiers are added.
 */
public final class RoadConfig {

    private static final RoadConfig INSTANCE = new RoadConfig();

    // Default block IDs per tier
    private static final String DIRT_SURFACE = "minecraft:dirt_path";
    private static final String DIRT_INTERSECTION = "minecraft:stone_bricks";

    private RoadConfig() {}

    public static RoadConfig getInstance() {
        return INSTANCE;
    }

    public int getBuildingThreshold() {
        return Config.ROAD_BUILDING_THRESHOLD.get();
    }

    public int getSegmentMaxLength() {
        return Config.ROAD_SEGMENT_MAX_LENGTH.get();
    }

    public String getSurfaceBlock(String tier) {
        if ("dirt".equals(tier)) return DIRT_SURFACE;
        return DIRT_SURFACE; // fallback
    }

    public String getIntersectionBlock(String tier) {
        if ("dirt".equals(tier)) return DIRT_INTERSECTION;
        return DIRT_INTERSECTION; // fallback
    }
}
