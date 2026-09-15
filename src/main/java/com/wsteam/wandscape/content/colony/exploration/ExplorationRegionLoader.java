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
     * Find the best matching exploration region configuration for a given loot table ID.
     *
     * <p>All matching configs are compared and the most specific pattern wins (see
     * {@link ExplorationRegionConfig#matchSpecificity}), so datapack catch-all regions
     * always lose to concrete ones no matter which order the registry iterates in.
     *
     * @param lootTableId full resource location string of the loot table (e.g. "minecraft:chests/abandoned_mineshaft")
     * @return the matching {@link ExplorationRegionConfig} or null if no pattern matches
     */
    @Nullable
    public ExplorationRegionConfig findMatchingRegion(String lootTableId) {
        if (lootTableId == null) return null;
        ExplorationRegionConfig best = null;
        int bestScore = -1;
        for (ExplorationRegionConfig config : registry.getAll().values()) {
            int score = config.matchSpecificity(lootTableId);
            if (score < 0) continue;
            // Ties broken by region id so the result is stable across reloads.
            if (score > bestScore || (score == bestScore && best != null && config.id().compareTo(best.id()) < 0)) {
                best = config;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Display name for a loot table: the matched region name, or a name derived from the
     * loot table id itself when no region config matches (e.g. another mod's structure).
     */
    public String resolveDisplayName(String lootTableId) {
        ExplorationRegionConfig config = findMatchingRegion(lootTableId);
        return config != null ? config.name() : ExplorationRegionConfig.deriveDisplayName(lootTableId);
    }
}
