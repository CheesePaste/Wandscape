package com.wsteam.wandscape.engine.road;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.Config;

/**
 * Configuration holder for the road system.
 * Reads TOML values from {@link Config}.
 */
public final class RoadConfig {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final RoadConfig INSTANCE = new RoadConfig();

    // Cached parsed palette; cleared when TOML reloads (call invalidatePalette).
    private volatile List<WeightedBlock> cachedPalette;
    private volatile String cachedPaletteRaw;

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

    public int getMaxCutDepth() {
        return Config.ROAD_MAX_CUT_DEPTH.get();
    }

    public int getMaxFillHeight() {
        return Config.ROAD_MAX_FILL_HEIGHT.get();
    }

    // ---- Decoration ----

    /**
     * Immutable snapshot of decoration configuration.
     * Engine layer reads this once per decoration build.
     */
    public record DecorationConfig(
            boolean enabled,
            int lampSpacing,
            int benchSpacing,
            String lampPost,
            String lampLight,
            String benchBlock
    ) {}

    /** Return the current decoration configuration from TOML. */
    public DecorationConfig getDecorationConfig() {
        return new DecorationConfig(
                Config.ROAD_DECORATION_ENABLED.get(),
                Config.ROAD_DECORATION_LAMP_SPACING.get(),
                Config.ROAD_DECORATION_BENCH_SPACING.get(),
                Config.ROAD_DECORATION_LAMP_POST.get(),
                Config.ROAD_DECORATION_LAMP_LIGHT.get(),
                Config.ROAD_DECORATION_BENCH_BLOCK.get());
    }

    /** Convenience: is decoration generation enabled? */
    public boolean isDecorationEnabled() {
        return Config.ROAD_DECORATION_ENABLED.get();
    }

    // ---- Surface palette ----

    /**
     * A weighted block entry in the road surface palette.
     * {@code weight} is relative — the sum of all weights defines the probability space.
     */
    public record WeightedBlock(String blockId, int weight) {
        public WeightedBlock {
            if (weight <= 0) throw new IllegalArgumentException("weight must be > 0: " + blockId);
        }
    }

    /**
     * Get the weighted surface palette for the default tier.
     * Parsed from TOML {@code road.surfacePalette}.
     *
     * <p>Format: {@code "modid:block=weight,modid:block=weight,..."}
     * Example: {@code "minecraft:stone_bricks=50,minecraft:andesite=25,minecraft:stone=25"}
     */
    public List<WeightedBlock> getSurfacePalette() {
        String raw = Config.ROAD_SURFACE_PALETTE.get();
        if (raw.equals(cachedPaletteRaw) && cachedPalette != null) {
            return cachedPalette;
        }
        cachedPaletteRaw = raw;
        cachedPalette = parsePalette(raw);
        return cachedPalette;
    }

    /** Drop cached palette so next {@link #getSurfacePalette()} re-parses from TOML. */
    public void invalidatePalette() {
        cachedPaletteRaw = null;
        cachedPalette = null;
    }

    /**
     * Get a representative surface block for a tier.
     * Returns the most common (first) block from the palette.
     */
    public String getDefaultBlock(String tier) {
        List<WeightedBlock> palette = getSurfacePalette();
        return palette.isEmpty() ? "minecraft:dirt_path" : palette.get(0).blockId();
    }

    private static List<WeightedBlock> parsePalette(String raw) {
        if (raw == null || raw.isBlank()) {
            LOGGER.warn("[Road] surfacePalette is empty — using dirt_path fallback");
            return List.of(new WeightedBlock("minecraft:dirt_path", 1));
        }

        List<WeightedBlock> result = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;

            int eq = trimmed.lastIndexOf('=');
            if (eq < 1 || eq == trimmed.length() - 1) {
                LOGGER.warn("[Road] invalid palette entry '{}' — expected block=weight", trimmed);
                continue;
            }

            String blockId = trimmed.substring(0, eq).trim();
            int weight;
            try {
                weight = Integer.parseInt(trimmed.substring(eq + 1).trim());
            } catch (NumberFormatException e) {
                LOGGER.warn("[Road] invalid weight in '{}'", trimmed);
                continue;
            }

            if (weight <= 0) {
                LOGGER.warn("[Road] non-positive weight {} in '{}' — skipped", weight, trimmed);
                continue;
            }

            result.add(new WeightedBlock(blockId, weight));
        }

        if (result.isEmpty()) {
            LOGGER.warn("[Road] no valid palette entries — using dirt_path fallback");
            return List.of(new WeightedBlock("minecraft:dirt_path", 1));
        }

        return Collections.unmodifiableList(result);
    }
}
