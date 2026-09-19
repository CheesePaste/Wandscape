package com.wsteam.wandscape.content.colony.exploration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.loot.LootTable;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes a region JSON for every chest loot table that nothing declares.
 *
 * <p>Another mod's structure arrives with no region, so its chests would fall back to a flat
 * payout forever. Rather than price the loot table on every chest opening, the server prices
 * each uncovered table <b>once at startup</b> — reading its weights, not rolling it, see
 * {@link ExplorationLootEstimator} — and writes the result down as an ordinary region file,
 * the same format a human writes, so a datapack author can read it, edit it, or replace it by
 * declaring that region properly.
 *
 * <p>Files land in {@code <world>/wandscape/generated_regions/}. World-scoped on purpose:
 * what a loot table is worth depends on the pack set the world runs with, not on the
 * machine. The declared tier always wins over these (see {@link ExplorationRegionLoader}),
 * so declaring a region by hand takes over from a generated one automatically.
 */
public final class ExplorationRegionGenerator {

    private static final String TAG = "ExplorationRegionGenerator";

    /** World-relative directory the generated region files live in. */
    public static final String GENERATED_SUBDIR = "wandscape/generated_regions";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ExplorationRegionGenerator() {}

    /** The world-scoped directory generated regions are written to. */
    public static Path generatedDir(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve(GENERATED_SUBDIR);
    }

    /**
     * Write a region file for every chest loot table no region declares and that has any
     * element value to speak of.
     *
     * <p>Runs once when the server starts, after the loot tables and the declared regions are
     * both loaded. Never throws: a table that fails to price is logged and skipped.
     *
     * <p>Tables where nothing could be priced get no file. They pay nothing by design (see
     * {@code ExplorationExpectationCalculator}), so writing one down would only duplicate the
     * default — and a modpack with a hundred unmapped chest tables would leave a hundred
     * identical, useless files behind.
     *
     * @return how many region files were written
     */
    public static int generateMissing(ServerLevel level) {
        if (level == null) return 0;

        Collection<ResourceLocation> lootTableIds =
                level.getServer().reloadableRegistries().getKeys(Registries.LOOT_TABLE);

        ExplorationRegionLoader loader = Wandscape.EXPLORATION_REGION_LOADER;
        List<ResourceLocation> missing = new ArrayList<>();
        for (ResourceLocation id : lootTableIds) {
            if (!isContainerTable(id)) continue;
            if (loader != null && loader.findMatchingRegion(id.toString()) != null) continue;
            missing.add(id);
        }

        if (missing.isEmpty()) {
            Log.info(TAG, "Every container loot table already has a region — nothing to generate");
            return 0;
        }

        Path dir = generatedDir(level);
        List<String> unpriced = new ArrayList<>();
        int written = 0;
        for (ResourceLocation id : missing) {
            Map<ElementType, Long> value = ExplorationLootEstimator.estimate(level, keyOf(id));
            if (ExplorationExpectationCalculator.isDegenerate(value)) {
                unpriced.add(id.toString());
                continue;
            }
            if (writeRegion(dir, id.toString(), value) != null) written++;
        }

        Log.info(TAG, "Priced {} chest loot table(s) into {}", written, dir);
        if (!unpriced.isEmpty()) {
            // One line, not one per table: a modpack with a hundred unmapped chest tables would
            // otherwise print a hundred lines on every startup.
            Log.info(TAG, "{} chest loot table(s) pay nothing: no item they can drop has an "
                            + "element_mappings entry, so there is no value to write down. Map their items, "
                            + "or declare a region with an explicit reward.value, if they should pay out: {}",
                    unpriced.size(), unpriced);
        }
        return written;
    }

    /**
     * Write (or rewrite) the generated region file for one loot table.
     *
     * @return the path written to, or null when it could not be written
     */
    public static Path writeRegion(Path dir, String lootTableId, Map<ElementType, Long> value) {
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(fileNameFor(lootTableId));
            Files.writeString(file, renderRegion(lootTableId, null, value), StandardCharsets.UTF_8);
            return file;
        } catch (Exception e) {
            Log.warn(TAG, "Failed to write generated region for {}: {}", lootTableId, e.getMessage());
            return null;
        }
    }

    /**
     * Render the region JSON for one loot table from an element value.
     *
     * @param declared the region already covering this loot table, whose name and tuning the
     *                 output keeps; null for an uncovered table, which gets a name derived from
     *                 the id and the default tuning
     */
    public static String renderRegion(String lootTableId, @Nullable ExplorationRegionConfig declared,
                                      Map<ElementType, Long> value) {
        JsonArray patterns = new JsonArray();
        // Quoted so an id containing regex metacharacters still matches only itself.
        patterns.add(Pattern.quote(lootTableId));

        ExplorationRewardSpec spec = declared != null ? declared.reward() : ExplorationRewardSpec.DEFAULT;
        JsonObject reward = new JsonObject();
        reward.addProperty("mode", "fixed");
        reward.add("value", renderValue(value));
        reward.addProperty("exp_ratio", spec.expRatio());
        reward.addProperty("variance", spec.variance());
        reward.addProperty("loot_share", spec.lootShare());

        JsonObject root = new JsonObject();
        root.addProperty("name", declared != null ? declared.name()
                : ExplorationRegionConfig.deriveDisplayName(lootTableId));
        root.add("loot_table_patterns", patterns);
        root.add("reward", reward);
        return GSON.toJson(root) + System.lineSeparator();
    }

    private static JsonObject renderValue(Map<ElementType, Long> value) {
        JsonObject obj = new JsonObject();
        if (value == null) return obj;
        for (ElementType type : ElementType.values()) {
            long amount = value.getOrDefault(type, 0L);
            if (amount > 0) obj.addProperty(type.getId(), amount);
        }
        return obj;
    }

    /** A loot table counts as a chest when one of its path segments says so. */
    private static boolean isContainerTable(ResourceLocation id) {
        for (String segment : id.getPath().split("/")) {
            if (segment.equals("chest") || segment.equals("chests")) return true;
        }
        return false;
    }

    /** Stable, filesystem-safe region file name, e.g. {@code minecraft_chests_ancient_city.json}. */
    public static String fileNameFor(String lootTableId) {
        return lootTableId.replace(':', '_').replace('/', '_')
                .replaceAll("[^a-z0-9_.-]", "_") + ".json";
    }

    private static ResourceKey<LootTable> keyOf(ResourceLocation id) {
        return ResourceKey.create(Registries.LOOT_TABLE, id);
    }
}
