package com.wsteam.wandscape.dataconfig.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.shared.registry.WandscapeDataRegistry;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Scans {@code data/wandscape/<category>/*.json} for each registered category.
 *
 * <p>Uses per-category {@code FileToIdConverter} scans rather than trying
 * to use a single prefix — MC's {@code SimpleJsonResourceReloadListener}
 * filters by resource path prefix, and our files are nested under
 * {@code data/wandscape/<category>/}, not {@code data/wandscape/} directly.
 */
public class WandscapeDataLoader extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();

    private final Map<String, SimpleDataRegistry<?>> registries = new HashMap<>();

    /**
     * Directory passed to super is unused because we override
     * {@link #prepare(ResourceManager, ProfilerFiller)} to scan per-category.
     */
    public WandscapeDataLoader() {
        super(GSON, "");
    }

    public <T> WandscapeDataRegistry<T> register(String category, BiFunction<String, JsonElement, T> parser) {
        SimpleDataRegistry<T> registry = new SimpleDataRegistry<>(parser);
        registries.put(category, registry);
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
        }
        return all;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager manager, ProfilerFiller profiler) {
        for (SimpleDataRegistry<?> registry : registries.values()) {
            registry.clear();
        }

        for (var entry : data.entrySet()) {
            ResourceLocation loc = entry.getKey();
            String path = loc.getPath();
            int slashIdx = path.indexOf('/');
            if (slashIdx < 0) continue;

            String category = path.substring(0, slashIdx);
            String id = path.substring(slashIdx + 1).replace(".json", "");

            SimpleDataRegistry<?> registry = registries.get(category);
            if (registry == null) continue;

            try {
                registry.loadEntry(id, entry.getValue());
            } catch (Exception e) {
                LOGGER.warn("Failed to parse config '{}': {}", loc, e.getMessage());
            }
        }
    }
}
