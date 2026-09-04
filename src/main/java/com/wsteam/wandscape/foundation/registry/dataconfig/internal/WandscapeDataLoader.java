package com.wsteam.wandscape.foundation.registry.dataconfig.internal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.registry.WandscapeDataRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.*;
import java.util.function.BiFunction;

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
     * 最近一次 reload 按类目分组的原始 JSON（只含确实加载过的条目，id 已去重）。
     * 服务端用其把数据同步给专用服务器客户端；可能为 null（尚未 reload，如启动早期）。
     */
    private volatile Map<String, Map<String, JsonElement>> lastDataByCategory;

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
        Map<String, Map<String, JsonElement>> grouped = new HashMap<>();
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
            grouped.computeIfAbsent(category, k -> new HashMap<>()).put(id, entry.getValue());

            for (SimpleDataRegistry<?> registry : list) {
                try {
                    registry.loadEntry(id, entry.getValue());
                    loaded++;
                } catch (Exception e) {
                    Log.warn(TAG, "Failed to parse config '{}': {}", loc, e.getMessage());
                }
            }
        }
        lastDataByCategory = grouped;
        Log.info(TAG, "WandscapeDataLoader reloaded: {} files across {} categories",
                loaded, registries.size());
    }

    /** 最近一次 reload 的原始 JSON（类目 → id → JSON），供服务端 datapack 同步转发；未 reload 过为 null。 */
    public Map<String, Map<String, JsonElement>> getRawByCategory() {
        return lastDataByCategory;
    }

    /**
     * 把服务端同步来的某一类目原始 JSON 灌进对应 registry（专用服务器客户端恢复 JEI /
     * 创造栏 / tooltip 数据的入口；客户端 reload 只扫 assets/，扫不到 data/）。
     * 幂等：先清该类目所有 registry 再按 id 排序载入，与服务端 apply 的确定性一致。
     */
    public void applyCategoryFrom(String category, Map<String, JsonElement> entriesById) {
        List<SimpleDataRegistry<?>> list = registries.get(category);
        if (list == null) {
            Log.warn(TAG, "applyCategoryFrom: unknown category '{}'", category);
            return;
        }
        for (SimpleDataRegistry<?> registry : list) {
            registry.clear();
        }
        List<Map.Entry<String, JsonElement>> sorted = new ArrayList<>(entriesById.entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        for (Map.Entry<String, JsonElement> entry : sorted) {
            for (SimpleDataRegistry<?> registry : list) {
                try {
                    registry.loadEntry(entry.getKey(), entry.getValue());
                } catch (Exception e) {
                    Log.warn(TAG, "Failed to parse synced config '{}' in '{}': {}",
                            entry.getKey(), category, e.getMessage());
                }
            }
        }
        Log.info(TAG, "Applied {} server configs for category '{}'", sorted.size(), category);
    }
}
