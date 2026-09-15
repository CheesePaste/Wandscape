package com.wsteam.wandscape.content.colony.exploration;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.registry.WandscapeDataRegistry;
import com.wsteam.wandscape.foundation.registry.dataconfig.internal.WandscapeDataLoader;

import javax.annotation.Nullable;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Data loader and registry for exploration region configs.
 *
 * <p>Two tiers, looked up in this order:
 * <ol>
 *   <li><b>Declared</b> — {@code data/<namespace>/exploration_regions/*.json} via
 *       {@link WandscapeDataLoader}. Hand-written intent; the mod's own regions live here
 *       and a datapack overrides them by shipping the same id at a higher pack priority.</li>
 *   <li><b>Generated</b> — {@code <world>/wandscape/generated_regions/*.json}, written once
 *       by {@link ExplorationRegionGenerator} for loot tables nothing declares. Weaker tier
 *       on purpose: adding a declared region later always takes over from a generated one.</li>
 * </ol>
 */
public class ExplorationRegionLoader {
    public static final String CATEGORY = "exploration_regions";

    private static final String TAG = "ExplorationRegionLoader";

    private final WandscapeDataRegistry<ExplorationRegionConfig> registry;

    /** Generated tier, keyed by region id; empty until a world is loaded. */
    private final Map<String, ExplorationRegionConfig> generated = new LinkedHashMap<>();

    private volatile Path generatedDir;

    public ExplorationRegionLoader(WandscapeDataLoader dataLoader) {
        this.registry = dataLoader.register(CATEGORY, ExplorationRegionConfig::fromJson);
    }

    @Nullable
    public ExplorationRegionConfig getRegion(String id) {
        ExplorationRegionConfig declared = registry.get(id);
        return declared != null ? declared : generated.get(id);
    }

    /** Declared regions only — what a datapack or this mod ships by hand. */
    public Map<String, ExplorationRegionConfig> getAllRegions() {
        return registry.getAll();
    }

    /** Generated regions only — the auto-written tier, one file per uncovered loot table. */
    public Collection<ExplorationRegionConfig> getGeneratedRegions() {
        return generated.values();
    }

    /** Point the loader at the world's generated-region directory and read what is there. */
    public void setGeneratedDir(@Nullable Path dir) {
        this.generatedDir = dir;
        reloadGenerated();
    }

    @Nullable
    public Path getGeneratedDir() {
        return generatedDir;
    }

    /** Re-read the generated tier from disk. Never throws: a broken file is skipped and warned. */
    public void reloadGenerated() {
        generated.clear();
        Path dir = generatedDir;
        if (dir == null || !Files.isDirectory(dir)) return;

        List<String> degenerateIds = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".json")) continue;
                ExplorationRegionConfig config =
                        loadGeneratedFile(file, name.substring(0, name.length() - ".json".length()));
                if (config != null && config.degenerate()) degenerateIds.add(config.id());
            }
        } catch (Exception e) {
            Log.warn(TAG, "Failed to scan generated regions in {}: {}", dir, e.getMessage());
        }

        if (!generated.isEmpty()) {
            Log.info(TAG, "Loaded {} generated exploration region(s) from {}", generated.size(), dir);
        }
        if (!degenerateIds.isEmpty()) {
            // One aggregate warning: a whole mod's worth of unmapped loot tables would otherwise
            // print one line each on every single startup.
            Log.warn(TAG, "{} generated region(s) hold a degenerate fallback payout — no item in their loot "
                            + "tables could be priced by element_mappings, so the chest pays a flat amount "
                            + "regardless of loot. Map those items, or declare the regions by hand, then delete "
                            + "the files from {} and restart. Affected: {}",
                    degenerateIds.size(), dir, degenerateIds);
        }
    }

    @Nullable
    private ExplorationRegionConfig loadGeneratedFile(Path file, String id) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement json = JsonParser.parseReader(reader);
            ExplorationRegionConfig config = ExplorationRegionConfig.fromJson(id, json);
            generated.put(id, config);
            return config;
        } catch (Exception e) {
            Log.warn(TAG, "Skipping unreadable generated region {}: {}", file, e.getMessage());
            return null;
        }
    }

    /**
     * Find the best matching exploration region configuration for a given loot table ID.
     *
     * <p>Declared regions are searched first and win outright; the generated tier is only
     * consulted when nothing declared matches, so an auto-written file can never shadow a
     * datapack's intent.
     *
     * <p>Within a tier, the most specific pattern wins (see
     * {@link ExplorationRegionConfig#matchSpecificity}), so a catch-all region added by a
     * datapack always loses to a concrete one no matter which order the registry iterates in.
     *
     * @param lootTableId full resource location string of the loot table (e.g. "minecraft:chests/abandoned_mineshaft")
     * @return the matching {@link ExplorationRegionConfig} or null if no pattern matches
     */
    @Nullable
    public ExplorationRegionConfig findMatchingRegion(String lootTableId) {
        if (lootTableId == null) return null;
        ExplorationRegionConfig declared = bestMatch(registry.getAll().values(), lootTableId);
        return declared != null ? declared : bestMatch(generated.values(), lootTableId);
    }

    @Nullable
    private static ExplorationRegionConfig bestMatch(Collection<ExplorationRegionConfig> candidates, String lootTableId) {
        ExplorationRegionConfig best = null;
        int bestScore = -1;
        for (ExplorationRegionConfig config : candidates) {
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
