package com.wsteam.wandscape.dataconfig.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

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

public class WandscapeDataLoader extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();

    private final Map<String, SimpleDataRegistry<?>> registries = new HashMap<>();

    public WandscapeDataLoader() {
        super(GSON, "wandscape");
    }

    public <T> WandscapeDataRegistry<T> register(String category, Class<T> type) {
        SimpleDataRegistry<T> registry = new SimpleDataRegistry<>();
        registries.put(category, registry);
        return registry;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager manager, ProfilerFiller profiler) {
        for (var entry : data.entrySet()) {
            ResourceLocation loc = entry.getKey();
            String path = loc.getPath();
            int slashIdx = path.indexOf('/');
            if (slashIdx < 0) continue;

            String category = path.substring(0, slashIdx);
            SimpleDataRegistry<?> registry = registries.get(category);
            if (registry == null) {
                LOGGER.warn("Unknown config category '{}' for file '{}'", category, loc);
                continue;
            }
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
                registry.loadEntry(id, loc, entry.getValue());
            } catch (Exception e) {
                LOGGER.warn("Failed to parse config '{}': {}", loc, e.getMessage());
            }
        }
    }

    private static class SimpleDataRegistry<T> implements WandscapeDataRegistry<T> {
        private final Map<String, T> entries = new HashMap<>();
        private Function<JsonElement, T> parser;

        @Override
        public T get(String id) {
            return entries.get(id);
        }

        @Override
        public Map<String, T> getAll() {
            return Map.copyOf(entries);
        }

        @Override
        public boolean contains(String id) {
            return entries.containsKey(id);
        }

        @SuppressWarnings("unchecked")
        void loadEntry(String id, ResourceLocation loc, JsonElement json) {
            if (parser != null) {
                entries.put(id, parser.apply(json));
            } else {
                entries.put(id, (T) json);
            }
        }

        void clear() {
            entries.clear();
        }
    }
}
