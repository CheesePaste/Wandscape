package com.wsteam.wandscape.element.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.shared.data.ElementType;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;

public class ElementValueGenerator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final double CRAFTING_EFFICIENCY = 1.0;
    private static final double SMELTING_EFFICIENCY = 1.0;
    private static final double STONECUTTING_EFFICIENCY = 1.0;
    private static final double SMITHING_EFFICIENCY = 1.0;
    private static final int MAX_ITERATIONS = 50;

    private final Level level;
    private final boolean dryRun;
    private final boolean force;
    private final Path outputDir;

    /** seed item_id → element values */
    private final Map<String, Map<ElementType, Long>> seedValues = new LinkedHashMap<>();
    /** computed item_id → element values */
    private final Map<String, Map<ElementType, Long>> knownValues = new LinkedHashMap<>();
    /** item_id → recipe nodes that produce this item */
    private final Map<String, List<RecipeNode>> recipeIndex = new LinkedHashMap<>();
    /** items that already have manual mappings (skip unless --force) */
    private final Set<String> manualItemIds = new HashSet<>();
    private final Set<String> manualBlockIds = new HashSet<>();

    private int recipesProcessed;
    private int iterationsRequired;
    private int filesWritten;
    private int filesSkipped;

    record RecipeNode(
        String outputId,
        int outputCount,
        RecipeType<?> recipeType,
        List<List<String>> ingredientOptions
    ) {}

    public record GenerationReport(
        int seedsLoaded,
        int recipesProcessed,
        int iterationsRequired,
        int itemsResolved,
        int itemsUnresolved,
        int filesWritten,
        int filesSkipped,
        List<String> unresolvedSample,
        Map<String, List<String>> rootCauses
    ) {}

    public ElementValueGenerator(Level level, boolean dryRun, boolean force, Path outputDir) {
        this.level = level;
        this.dryRun = dryRun;
        this.force = force;
        this.outputDir = outputDir;
    }

    // ── Phase 0: Load seeds ──

    void loadSeeds(JsonObject root) {
        for (JsonElement elem : root.getAsJsonArray("seeds")) {
            JsonObject obj = elem.getAsJsonObject();
            String itemId = obj.get("item").getAsString();
            Map<ElementType, Long> values = parseElementMap(obj, "values");
            if (!values.isEmpty()) {
                seedValues.put(itemId, values);
            }
        }
    }

    // ── Phase 1: Collect recipes ──

    @SuppressWarnings({ "unchecked", "rawtypes" })
    void collectRecipes() {
        RecipeManager rm = level.getRecipeManager();
        HolderLookup.Provider registries = level.registryAccess();

        List<RecipeType> types = List.of(
            RecipeType.CRAFTING,
            RecipeType.SMELTING,
            RecipeType.BLASTING,
            RecipeType.SMOKING,
            RecipeType.CAMPFIRE_COOKING,
            RecipeType.STONECUTTING,
            RecipeType.SMITHING
        );

        for (RecipeType type : types) {
            Collection<?> holders = rm.getAllRecipesFor((RecipeType) type);
            for (Object obj : holders) {
                RecipeHolder<?> holder = (RecipeHolder<?>) obj;
                Recipe<?> recipe = holder.value();
                if (recipe.isSpecial()) continue;

                ItemStack result = recipe.getResultItem(registries);
                if (result.isEmpty()) continue;

                String outputId = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
                int outputCount = result.getCount();

                NonNullList<Ingredient> ingredients = recipe.getIngredients();
                if (ingredients.isEmpty()) continue;

                List<List<String>> ingredientOptions = new ArrayList<>();
                boolean allKnown = true;
                for (Ingredient ing : ingredients) {
                    if (ing == Ingredient.EMPTY) continue;
                    ItemStack[] items = ing.getItems();
                    if (items.length == 0) { allKnown = false; break; }
                    List<String> opts = new ArrayList<>();
                    for (ItemStack is : items) {
                        opts.add(BuiltInRegistries.ITEM.getKey(is.getItem()).toString());
                    }
                    ingredientOptions.add(opts);
                }
                if (!allKnown || ingredientOptions.isEmpty()) continue;

                RecipeNode node = new RecipeNode(outputId, outputCount, type, ingredientOptions);
                recipeIndex.computeIfAbsent(outputId, k -> new ArrayList<>()).add(node);
                recipesProcessed++;
            }
        }
    }

    // ── Phase 2: Detect manual files ──

    void scanManualFiles(Path manualDir) throws IOException {
        if (!Files.isDirectory(manualDir)) return;

        try (var stream = Files.list(manualDir)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".json")).toList()) {
                String raw = Files.readString(file);
                JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
                if (obj.has("block")) {
                    manualBlockIds.add(obj.get("block").getAsString());
                }
                if (obj.has("item")) {
                    manualItemIds.add(obj.get("item").getAsString());
                }
            }
        }
    }

    // ── Phase 3: Iterate ──

    void iterate() {
        knownValues.putAll(seedValues);

        boolean changed = true;
        iterationsRequired = 0;

        while (changed && iterationsRequired < MAX_ITERATIONS) {
            changed = false;
            iterationsRequired++;

            for (var entry : recipeIndex.entrySet()) {
                String outputId = entry.getKey();
                if (knownValues.containsKey(outputId)) continue;

                for (RecipeNode node : entry.getValue()) {
                    Map<ElementType, Long> computed = computeFromNode(node);
                    if (computed != null && !computed.isEmpty()) {
                        knownValues.put(outputId, computed);
                        changed = true;
                        break;
                    }
                }
            }
        }
    }

    private Map<ElementType, Long> computeFromNode(RecipeNode node) {
        Map<ElementType, Long> total = new HashMap<>();

        for (List<String> opts : node.ingredientOptions) {
            Map<ElementType, Long> best = null;
            for (String itemId : opts) {
                Map<ElementType, Long> val = knownValues.get(itemId);
                if (val != null) {
                    if (best == null || totalValue(val) < totalValue(best)) {
                        best = val;
                    }
                }
            }
            if (best == null) return null; // not all ingredients resolved yet
            addTo(total, best);
        }

        if (total.isEmpty()) return null;

        double efficiency = getEfficiency(node.recipeType);
        Map<ElementType, Long> result = new HashMap<>();
        for (var entry : total.entrySet()) {
            long scaled = (long) (entry.getValue() * efficiency / node.outputCount);
            // floor at 1 for elements present in ingredients — prevents
            // low-value items like sticks from zeroing out due to truncation
            if (scaled <= 0 && entry.getValue() > 0) {
                scaled = 1;
            }
            if (scaled > 0) {
                result.put(entry.getKey(), scaled);
            }
        }
        return result;
    }

    private static long totalValue(Map<ElementType, Long> values) {
        long sum = 0;
        for (long v : values.values()) sum += v;
        return sum;
    }

    private static void addTo(Map<ElementType, Long> target, Map<ElementType, Long> source) {
        for (var entry : source.entrySet()) {
            target.merge(entry.getKey(), entry.getValue(), Long::sum);
        }
    }

    private static double getEfficiency(RecipeType<?> type) {
        if (type == RecipeType.STONECUTTING) return STONECUTTING_EFFICIENCY;
        if (type == RecipeType.SMELTING || type == RecipeType.BLASTING
            || type == RecipeType.SMOKING || type == RecipeType.CAMPFIRE_COOKING)
            return SMELTING_EFFICIENCY;
        if (type == RecipeType.SMITHING) return SMITHING_EFFICIENCY;
        return CRAFTING_EFFICIENCY;
    }

    // ── Phase 4: Find matching blocks ──

    record ItemWithValues(String itemId, Map<ElementType, Long> values, boolean isBlock) {}

    List<ItemWithValues> resolveOutputs() {
        Map<String, Map<ElementType, Long>> finalValues = new LinkedHashMap<>();
        // Prefer seed values over computed
        finalValues.putAll(knownValues);

        List<ItemWithValues> results = new ArrayList<>();
        for (var entry : finalValues.entrySet()) {
            String itemId = entry.getKey();
            ResourceLocation rl = ResourceLocation.tryParse(itemId);
            if (rl == null) continue;
            Item item = BuiltInRegistries.ITEM.get(rl);
            boolean isBlock = BuiltInRegistries.BLOCK.containsKey(rl);
            results.add(new ItemWithValues(itemId, entry.getValue(), isBlock));
        }
        return results;
    }

    // ── Phase 5: Write output ──

    int writeOutput(List<ItemWithValues> items) throws IOException {
        int written = 0;
        int skipped = 0;

        for (ItemWithValues iwv : items) {
            boolean isManual = manualItemIds.contains(iwv.itemId);
            if (iwv.isBlock && manualBlockIds.contains(iwv.itemId)) {
                isManual = true;
            }
            if (isManual && !force) {
                skipped++;
                continue;
            }

            if (!dryRun) {
                writeJson(iwv);
            }
            written++;
        }

        this.filesWritten = written;
        this.filesSkipped = skipped;
        return written;
    }

    private void writeJson(ItemWithValues iwv) throws IOException {
        JsonObject obj = new JsonObject();
        if (iwv.isBlock) {
            obj.addProperty("block", iwv.itemId);
        } else {
            obj.addProperty("item", iwv.itemId);
        }

        JsonObject cost = new JsonObject();
        for (var entry : iwv.values.entrySet()) {
            cost.addProperty(entry.getKey().getId(), entry.getValue());
        }
        obj.add("build_cost", cost);
        obj.add("decompose_yield", new JsonObject());
        obj.add("decomposable", new JsonPrimitive(false));
        obj.add("synthesize", new JsonObject());
        obj.addProperty("source", "auto_generated");

        String safeName = iwv.itemId.replace(':', '_') + ".json";
        Path outFile = outputDir.resolve(safeName);
        Files.createDirectories(outputDir);

        String json = GSON.toJson(obj);
        Files.writeString(outFile, json);
    }

    // ── Phase 6: Trace root causes ──

    /**
     * For each unresolved item (in recipeIndex but not resolved), trace
     * backwards through its recipe ingredients to find the "root" items that
     * have neither a seed value nor a crafting recipe of their own.
     *
     * @return rootCause itemId → list of unresolved items blocked by it
     */
    Map<String, List<String>> traceRootCauses() {
        Set<String> unresolved = new LinkedHashSet<>(recipeIndex.keySet());
        unresolved.removeAll(knownValues.keySet());

        Map<String, List<String>> rootCauses = new LinkedHashMap<>();

        for (String itemId : unresolved) {
            Set<String> roots = new LinkedHashSet<>();
            traceBackwards(itemId, roots, new HashSet<>());
            for (String root : roots) {
                rootCauses.computeIfAbsent(root, k -> new ArrayList<>()).add(itemId);
            }
        }
        return rootCauses;
    }

    private void traceBackwards(String itemId, Set<String> roots, Set<String> visited) {
        if (!visited.add(itemId)) return;
        if (visited.size() > 200) return;

        List<RecipeNode> nodes = recipeIndex.get(itemId);
        if (nodes == null) {
            // No recipe — this is a root cause (need seed value)
            if (!knownValues.containsKey(itemId)) {
                roots.add(itemId);
            }
            return;
        }

        // This item has a recipe — trace into its unresolved ingredients
        for (RecipeNode node : nodes) {
            for (List<String> opts : node.ingredientOptions) {
                // Skip if any option in this slot already has a known value
                if (opts.stream().anyMatch(knownValues::containsKey)) continue;
                for (String ingId : opts) {
                    if (!knownValues.containsKey(ingId)) {
                        traceBackwards(ingId, roots, visited);
                    }
                }
            }
        }
    }

    void writeRootCauses(Map<String, List<String>> rootCauses, Path filePath) throws IOException {
        if (rootCauses.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        sb.append("# Missing seed items — add these to element_seeds.json\n");
        sb.append("# Format: item_id  ←  number of items blocked\n");
        sb.append("# The blocked items are listed below each root cause.\n\n");

        // Sort by number of blocked items descending
        List<Map.Entry<String, List<String>>> sorted = new ArrayList<>(rootCauses.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

        for (var entry : sorted) {
            sb.append(entry.getKey())
              .append("  ← blocks ").append(entry.getValue().size()).append(" item(s)\n");
            for (String blocked : entry.getValue()) {
                sb.append("    ").append(blocked).append("\n");
            }
            sb.append("\n");
        }

        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, sb.toString());
        LOGGER.info("Root causes written to {} ({} missing seeds)", filePath, sorted.size());
    }

    // ── Orchestrator ──

    public GenerationReport run(Path manualDir, String seedJson) throws IOException {
        // Load seeds
        JsonObject seedRoot = JsonParser.parseString(seedJson).getAsJsonObject();
        loadSeeds(seedRoot);

        // Scan existing manual files
        scanManualFiles(manualDir);

        // Phase 1: collect
        collectRecipes();

        // Phase 3: iterate
        iterate();

        // Phase 4: resolve
        List<ItemWithValues> outputs = resolveOutputs();

        // Phase 5: write
        int written = writeOutput(outputs);

        // Phase 6: trace root causes
        Map<String, List<String>> rootCauses = traceRootCauses();
        Path rootCausesFile = outputDir.getParent().getParent().resolve("missing_seeds.txt");
        if (!dryRun) {
            writeRootCauses(rootCauses, rootCausesFile);
        }

        // Compute stats
        int resolved = knownValues.size() - seedValues.size();
        int unresolved = (int) recipeIndex.keySet().stream()
                .filter(id -> !knownValues.containsKey(id)).count();
        List<String> unresolvedSample = new ArrayList<>();
        for (var entry : recipeIndex.entrySet()) {
            if (!knownValues.containsKey(entry.getKey())) {
                if (unresolvedSample.size() < 20) {
                    unresolvedSample.add(entry.getKey());
                }
            }
        }

        return new GenerationReport(
            seedValues.size(),
            recipesProcessed,
            iterationsRequired,
            resolved,
            unresolved,
            written,
            filesSkipped,
            unresolvedSample,
            rootCauses
        );
    }

    private static Map<ElementType, Long> parseElementMap(JsonObject obj, String key) {
        Map<ElementType, Long> map = new LinkedHashMap<>();
        if (!obj.has(key)) return map;
        JsonObject costObj = obj.getAsJsonObject(key);
        for (var entry : costObj.entrySet()) {
            ElementType type = ElementType.valueOf(entry.getKey().toUpperCase());
            map.put(type, entry.getValue().getAsLong());
        }
        return map;
    }
}
