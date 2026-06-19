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

public class WandscapeDataLoader extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();

    private final Map<String, SimpleDataRegistry<?>> registries = new HashMap<>();

    public WandscapeDataLoader() {
        super(GSON, "wandscape");
    }

    public <T> WandscapeDataRegistry<T> register(String category, BiFunction<String, JsonElement, T> parser) {
        SimpleDataRegistry<T> registry = new SimpleDataRegistry<>(parser);
        registries.put(category, registry);
        return registry;
    }

    /**
     * Convenience: register with a parser that only needs the JsonElement (ignores id).
     */
    public <T> WandscapeDataRegistry<T> register(String category,
                                                  java.util.function.Function<JsonElement, T> parser) {
        return register(category, (id, json) -> parser.apply(json));
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
