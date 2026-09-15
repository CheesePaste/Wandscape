package com.wsteam.wandscape.content.colony.exploration;

import com.wsteam.wandscape.foundation.registry.WandscapeDataRegistry;
import com.wsteam.wandscape.foundation.registry.dataconfig.internal.WandscapeDataLoader;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;

/**
 * Data loader and registry for exploration region configs.
 * Loaded from data/<namespace>/exploration_regions/*.json via {@link WandscapeDataLoader}.
 */
public class ExplorationRegionLoader {
    public static final String CATEGORY = "exploration_regions";

    private final WandscapeDataRegistry<ExplorationRegionConfig> registry;

    public ExplorationRegionLoader(WandscapeDataLoader dataLoader) {
        this.registry = dataLoader.register(CATEGORY, ExplorationRegionConfig::fromJson);
    }

    @Nullable
    public ExplorationRegionConfig getRegion(String id) {
        return registry.get(id);
    }

    public Map<String, ExplorationRegionConfig> getAllRegions() {
        return registry.getAll();
    }

    /**
     * Find the first matching exploration region configuration for a given loot table ID.
     *
     * @param lootTableId full resource location string of the loot table (e.g. "minecraft:chests/abandoned_mineshaft")
     * @return the matching {@link ExplorationRegionConfig} or null if no pattern matches
     */
    @Nullable
    public ExplorationRegionConfig findMatchingRegion(String lootTableId) {
        if (lootTableId == null) return null;
        for (ExplorationRegionConfig config : registry.getAll().values()) {
            if (config.matches(lootTableId)) {
                return config;
            }
        }
        return null;
    }
}
