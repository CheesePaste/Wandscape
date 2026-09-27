package com.wsteam.wandscape.content.element.internal;
import com.wsteam.wandscape.content.task.ecs.World;

import com.google.gson.*;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ElementValueGenerator {
    private static final String TAG = "ElementValueGenerator";
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
    /** ingredient item_id → items whose recipes consume it (reverse of recipeIndex) */
    private final Map<String, Set<String>> consumerIndex = new LinkedHashMap<>();
    /** items that already have manual mappings (skip unless --force) */
    private final Set<String> manualItemIds = new HashSet<>();
    private final Set<String> manualBlockIds = new HashSet<>();

    private int recipesProcessed;
    private int iterationsRequired;
    private int filesSkipped;
    private final List<String> changeSummaries = new ArrayList<>();

    record IngredientSlot(
        List<String> itemOptions,
        List<String> remainingOptions
    ) {
        IngredientSlot {
            if (itemOptions.size() != remainingOptions.size())
                throw new IllegalArgumentException("itemOptions/remainingOptions size mismatch");
        }
    }

    /** Recipe kinds used in value resolution; keeps RecipeNode free of MC types. */
    enum RecipeKind {
        CRAFTING(CRAFTING_EFFICIENCY),
        SMELTING(SMELTING_EFFICIENCY),
        STONECUTTING(STONECUTTING_EFFICIENCY),
        SMITHING(SMITHING_EFFICIENCY);

        final double efficiency;

        RecipeKind(double efficiency) {
            this.efficiency = efficiency;
        }
    }

    record RecipeNode(
        String outputId,
        int outputCount,
        RecipeKind kind,
        List<IngredientSlot> slots
    ) {}

    record Resolution(Map<String, Map<ElementType, Long>> values, int iterations) {}

    public record GenerationReport(
        int seedsLoaded,
        int recipesProcessed,
        int iterationsRequired,
        int itemsResolved,
        int itemsUnresolved,
        int filesWritten,
        int filesSkipped,
        List<String> unresolvedSample,
        Map<String, List<String>> rootCauses,
        /** null for a full run; the seed id when only its derivation subtree was written */
        String rootId,
        /** size of the rooted subtree (0 for a full run) */
        int subtreeSize,
        /** "id  old → new" lines for every file whose values actually changed */
        List<String> changeSummaries
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
            Map<ElementType, Long> values = ElementMaps.parse(obj, "values");
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

                List<IngredientSlot> slots = new ArrayList<>();
                boolean allKnown = true;
                for (Ingredient ing : ingredients) {
                    if (ing == Ingredient.EMPTY) continue;
                    ItemStack[] items = ing.getItems();
                    if (items.length == 0) { allKnown = false; break; }
                    List<String> itemOpts = new ArrayList<>();
                    List<String> remainOpts = new ArrayList<>();
                    for (ItemStack is : items) {
                        Item item = is.getItem();
                        itemOpts.add(BuiltInRegistries.ITEM.getKey(item).toString());
                        Item remaining = item.getCraftingRemainingItem();
                        if (remaining != null) {
                            remainOpts.add(BuiltInRegistries.ITEM.getKey(remaining).toString());
                        } else {
                            remainOpts.add("");
                        }
                    }
                    slots.add(new IngredientSlot(itemOpts, remainOpts));
                }
                if (!allKnown || slots.isEmpty()) continue;

                RecipeNode node = new RecipeNode(outputId, outputCount, toKind(type), slots);
                recipeIndex.computeIfAbsent(outputId, k -> new ArrayList<>()).add(node);
                recipesProcessed++;
            }
        }

        buildConsumerIndex();
    }

    /** Reverse of {@link #recipeIndex}: which outputs are built from a given ingredient. */
    private void buildConsumerIndex() {
        for (var entry : recipeIndex.entrySet()) {
            for (RecipeNode node : entry.getValue()) {
                for (IngredientSlot slot : node.slots) {
                    for (String option : slot.itemOptions()) {
                        consumerIndex.computeIfAbsent(option, k -> new LinkedHashSet<>())
                                .add(entry.getKey());
                    }
                }
            }
        }
    }

    /**
     * Every item whose value is derived — directly or transitively — from
     * {@code rootId} through the vanilla recipe graph, the root included.
     *
     * <p>Conservative: a tag ingredient contributes every one of its options as a
     * dependency, so the set can list items the fixed point actually resolved via a
     * different option. Over-inclusion only costs a redundant file rewrite; the
     * written value is still whatever {@link #resolve} computed.
     */
    public Set<String> downstreamOf(String rootId) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        seen.add(rootId);
        queue.add(rootId);
        while (!queue.isEmpty()) {
            for (String outputId : consumerIndex.getOrDefault(queue.poll(), Set.of())) {
                if (seen.add(outputId)) {
                    queue.add(outputId);
                }
            }
        }
        return seen;
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
        Resolution res = resolve(seedValues, recipeIndex, MAX_ITERATIONS);
        knownValues.clear();
        knownValues.putAll(res.values());
        iterationsRequired = res.iterations();
    }

    /**
     * Fixed-point value resolution. Unlike a single sweep, already-resolved
     * outputs are recomputed every pass, so a remaining-item subtraction
     * (e.g. milk bucket − returned bucket) takes effect even when the
     * remaining item's own recipe resolves in a later pass. Seed values are
     * authoritative and never overwritten by recipes.
     */
    static Resolution resolve(
            Map<String, Map<ElementType, Long>> seeds,
            Map<String, List<RecipeNode>> recipeIndex,
            int maxIterations) {
        Map<String, Map<ElementType, Long>> known = new LinkedHashMap<>(seeds);

        boolean changed = true;
        int iterations = 0;
        while (changed && iterations < maxIterations) {
            changed = false;
            iterations++;

            for (var entry : recipeIndex.entrySet()) {
                String outputId = entry.getKey();
                if (seeds.containsKey(outputId)) continue; // seeds are authoritative

                Map<ElementType, Long> best = null;
                for (RecipeNode node : entry.getValue()) {
                    Map<ElementType, Long> computed = computeFromNode(node, known);
                    if (computed == null || computed.isEmpty()) continue;
                    best = computed;
                    break; // first resolvable recipe wins (existing semantics)
                }
                if (best == null) continue; // not all ingredients resolved yet

                Map<ElementType, Long> current = known.get(outputId);
                if (current == null || !current.equals(best)) {
                    known.put(outputId, best);
                    changed = true;
                }
            }
        }
        return new Resolution(known, iterations);
    }

    private static Map<ElementType, Long> computeFromNode(
            RecipeNode node,
            Map<String, Map<ElementType, Long>> known) {
        Map<ElementType, Long> total = new HashMap<>();

        for (IngredientSlot slot : node.slots) {
            Map<ElementType, Long> best = null;  // net value (ingredient - remaining)
            for (int i = 0; i < slot.itemOptions().size(); i++) {
                String itemId = slot.itemOptions().get(i);
                Map<ElementType, Long> val = known.get(itemId);
                if (val == null) continue;

                // Net cost = ingredient value minus remaining item value (e.g. milk bucket - bucket)
                Map<ElementType, Long> net = new HashMap<>(val);
                String remainingId = slot.remainingOptions().get(i);
                if (!remainingId.isEmpty()) {
                    Map<ElementType, Long> remVal = known.get(remainingId);
                    if (remVal != null) {
                        subtractFrom(net, remVal);
                    }
                }

                if (best == null || totalValue(net) < totalValue(best)) {
                    best = net;
                }
            }
            if (best == null) return null; // not all ingredients resolved yet
            addTo(total, best);
        }

        if (total.isEmpty()) return null;

        double efficiency = node.kind().efficiency;
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

    /** Subtract source values from target, removing any element that reaches <= 0. */
    private static void subtractFrom(Map<ElementType, Long> target, Map<ElementType, Long> source) {
        for (var entry : source.entrySet()) {
            Long current = target.get(entry.getKey());
            if (current == null) continue;
            long newVal = current - entry.getValue();
            if (newVal <= 0) {
                target.remove(entry.getKey());
            } else {
                target.put(entry.getKey(), newVal);
            }
        }
    }

    private static RecipeKind toKind(RecipeType<?> type) {
        if (type == RecipeType.SMELTING || type == RecipeType.BLASTING
            || type == RecipeType.SMOKING || type == RecipeType.CAMPFIRE_COOKING)
            return RecipeKind.SMELTING;
        if (type == RecipeType.STONECUTTING) return RecipeKind.STONECUTTING;
        if (type == RecipeType.SMITHING) return RecipeKind.SMITHING;
        return RecipeKind.CRAFTING;
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

    int writeOutput(List<ItemWithValues> items, Set<String> only) throws IOException {
        int written = 0;
        int skipped = 0;

        for (ItemWithValues iwv : items) {
            if (only != null && !only.contains(iwv.itemId)) {
                continue;
            }
            boolean isManual = manualItemIds.contains(iwv.itemId);
            if (iwv.isBlock && manualBlockIds.contains(iwv.itemId)) {
                isManual = true;
            }
            // A rooted run names its seed explicitly, so re-deriving that whole subtree
            // is the point: existing files are overwritten rather than skipped.
            if (only == null && isManual && !force) {
                skipped++;
                continue;
            }

            String json = renderJson(iwv);
            String onDisk = readOnDisk(iwv.itemId);
            if (onDisk != null) {
                if (normalize(onDisk).equals(normalize(json))) {
                    written++; // already up to date — leave the file untouched
                    continue;
                }
                Map<ElementType, Long> old = parseValues(onDisk, iwv.itemId);
                if (old != null && !old.equals(iwv.values)) {
                    changeSummaries.add(iwv.itemId + "  "
                            + formatValues(old) + " → " + formatValues(iwv.values));
                }
            }

            if (!dryRun) {
                writeJson(iwv.itemId, json);
            }
            written++;
        }

        this.filesSkipped = skipped;
        return written;
    }

    /** Serialized mapping file for an item, without touching the filesystem. */
    private String renderJson(ItemWithValues iwv) {
        JsonObject obj = new JsonObject();
        obj.addProperty(iwv.isBlock ? "block" : "item", iwv.itemId);

        JsonObject cost = new JsonObject();
        for (var entry : iwv.values.entrySet()) {
            cost.addProperty(entry.getKey().getId(), entry.getValue());
        }
        obj.add("build_cost", cost);
        return GSON.toJson(obj);
    }

    private void writeJson(String itemId, String json) throws IOException {
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve(itemId.replace(':', '_') + ".json"), json);
    }

    private String readOnDisk(String itemId) throws IOException {
        Path file = outputDir.resolve(itemId.replace(':', '_') + ".json");
        return Files.isRegularFile(file) ? Files.readString(file) : null;
    }

    private static Map<ElementType, Long> parseValues(String raw, String itemId) {
        try {
            return ElementMaps.parse(JsonParser.parseString(raw).getAsJsonObject(), "build_cost");
        } catch (RuntimeException e) {
            Log.warn(TAG, "Unreadable mapping file for {}, treating as changed: {}", itemId, e.getMessage());
            return null;
        }
    }

    /**
     * The working tree is CRLF on Windows while the generator writes LF, so raw
     * comparison would call every file changed (git normalizes them back anyway).
     */
    private static String normalize(String raw) {
        return raw.replace("\r\n", "\n").strip();
    }

    private static String formatValues(Map<ElementType, Long> values) {
        StringBuilder sb = new StringBuilder();
        for (var entry : values.entrySet()) {
            if (sb.length() > 0) sb.append('/');
            sb.append(entry.getKey().getId()).append(' ').append(entry.getValue());
        }
        return sb.length() == 0 ? "(empty)" : sb.toString();
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
            for (IngredientSlot slot : node.slots) {
                // Skip if any option in this slot already has a known value
                if (slot.itemOptions().stream().anyMatch(knownValues::containsKey)) continue;
                for (String ingId : slot.itemOptions()) {
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
        Log.info(TAG, "Root causes written to {} ({} missing seeds)", filePath, sorted.size());
    }

    // ── Orchestrator ──

    public GenerationReport run(Path manualDir, String seedJson) throws IOException {
        return run(manualDir, seedJson, null);
    }

    /**
     * @param rootSeed when non-null, only items whose value is derived from this seed are
     *                 (re)written — a targeted refresh of one material plus everything
     *                 crafted from it, instead of the whole mapping set. The seed's own
     *                 value still comes from {@code element_seeds.json}; the id must be
     *                 present there.
     */
    public GenerationReport run(Path manualDir, String seedJson, String rootSeed) throws IOException {
        changeSummaries.clear();

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

        // Phase 5: write — a rooted run restricts the write set to that seed's subtree
        Set<String> only = null;
        int subtreeSize = 0;
        if (rootSeed != null) {
            only = downstreamOf(rootSeed);
            subtreeSize = only.size();
        }
        int written = writeOutput(outputs, only);

        // Phase 6: trace root causes — a full-run artifact, skipped when rooted
        Map<String, List<String>> rootCauses = traceRootCauses();
        if (rootSeed == null && !dryRun) {
            Path rootCausesFile = outputDir.getParent().getParent().resolve("missing_seeds.txt");
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
            rootCauses,
            rootSeed,
            subtreeSize,
            List.copyOf(changeSummaries)
        );
    }
}
