package com.wsteam.wandscape.dataconfig.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.wsteam.wandscape.shared.registry.WandscapeDataRegistry;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Scans {@code data/wandscape/<category>/*.json} for each registered category.
 *
 * <p>Uses per-category {@code FileToIdConverter} scans rather than trying
 * to use a single prefix — MC's {@code SimpleJsonResourceReloadListener}
 * filters by resource path prefix, and our files are nested under
 * {@code data/wandscape/<category>/}, not {@code data/wandscape/} directly.
 */
public class WandscapeDataLoader extends SimpleJsonResourceReloadListener {
    private static final String TAG = "WandscapeDataLoader";
    private static final Gson GSON = new GsonBuilder().create();

    /** Mod 自身命名空间。派生 id 冲突（跨命名空间同名文件）时优先，见 {@link #apply}。 */
    private static final String MOD_NAMESPACE = "wandscape";

    private final Map<String, List<SimpleDataRegistry<?>>> registries = new HashMap<>();

    /**
     * Directory passed to super is unused because we override
     * {@link #prepare(ResourceManager, ProfilerFiller)} to scan per-category.
     */
    public WandscapeDataLoader() {
        super(GSON, "");
    }

    public <T> WandscapeDataRegistry<T> register(String category, BiFunction<String, JsonElement, T> parser) {
        SimpleDataRegistry<T> registry = new SimpleDataRegistry<>(parser);
        registries.computeIfAbsent(category, k -> new ArrayList<>()).add(registry);
        return registry;
    }

    /**
     * Override prepare to scan each registered category directory separately.
     * Each category (e.g. "buildings") maps to {@code data/<ns>/<category>/*.json}.
     *
     * <p>{@code scanDirectory} strips the category prefix via {@code fileToId},
     * so we re-insert it as {@code category/id} for downstream parsing.
     */
    @Override
    protected Map<ResourceLocation, JsonElement> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, JsonElement> all = new HashMap<>();
        for (String cat : registries.keySet()) {
            Map<ResourceLocation, JsonElement> catData = new HashMap<>();
            scanDirectory(manager, cat, GSON, catData);
            for (var entry : catData.entrySet()) {
                ResourceLocation newKey = ResourceLocation.fromNamespaceAndPath(
                        entry.getKey().getNamespace(),
                        cat + "/" + entry.getKey().getPath() + ".json");
                all.put(newKey, entry.getValue());
            }

            // Fallback listResources search to ensure data/ resources are also loaded on client
            var found = manager.listResources(cat, loc -> loc.getPath().endsWith(".json"));
            for (var entry : found.entrySet()) {
                ResourceLocation loc = entry.getKey();
                String path = loc.getPath();
                ResourceLocation newKey = ResourceLocation.fromNamespaceAndPath(loc.getNamespace(), path);
                if (!all.containsKey(newKey)) {
                    try (var reader = entry.getValue().openAsReader()) {
                        JsonElement json = com.google.gson.JsonParser.parseReader(reader);
                        all.put(newKey, json);
                    } catch (Exception e) {
                        Log.warn(TAG, "Failed to read resource '{}': {}", loc, e.getMessage());
                    }
                }
            }
        }
        return all;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager manager, ProfilerFiller profiler) {
        for (List<SimpleDataRegistry<?>> list : registries.values()) {
            for (SimpleDataRegistry<?> registry : list) {
                registry.clear();
            }
        }

        // Deterministic load order. Derived ids are filename-only (namespace stripped),
        // so files from different namespaces can collide on the same id. Rule: the mod's
        // own namespace wins on collision; other namespaces resolve by sorted key. Overriding
        // the mod therefore requires the wandscape namespace at equal-or-higher pack priority
        // (pack layering is already resolved by the ResourceManager before this point).
        List<Map.Entry<ResourceLocation, JsonElement>> sorted = data.entrySet().stream()
                .sorted(Comparator
                        .comparing((Map.Entry<ResourceLocation, JsonElement> e) ->
                                MOD_NAMESPACE.equals(e.getKey().getNamespace()) ? 0 : 1)
                        .thenComparing(e -> e.getKey().getNamespace())
                        .thenComparing(e -> e.getKey().getPath()))
                .toList();

        Set<String> seenIds = new HashSet<>();
        int loaded = 0;
        for (var entry : sorted) {
            ResourceLocation loc = entry.getKey();
            String path = loc.getPath();
            int slashIdx = path.indexOf('/');
            if (slashIdx < 0) continue;

            String category = path.substring(0, slashIdx);
            String id = path.substring(slashIdx + 1).replace(".json", "");
            if (!seenIds.add(category + "/" + id)) continue;

            List<SimpleDataRegistry<?>> list = registries.get(category);
            if (list == null) continue;

            for (SimpleDataRegistry<?> registry : list) {
                try {
                    registry.loadEntry(id, entry.getValue());
                    loaded++;
                } catch (Exception e) {
                    Log.warn(TAG, "Failed to parse config '{}': {}", loc, e.getMessage());
                }
            }
        }
        Log.info(TAG, "WandscapeDataLoader reloaded: {} files across {} categories",
                loaded, registries.size());
    }
}
