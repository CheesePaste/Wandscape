package com.wsteam.wandscape.building.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.data.WonderEffect;
import com.wsteam.wandscape.shared.registry.WandscapeDataRegistry;

/**
 * Singleton loader that parses {@link BuildingConfig} from JSON
 * and provides lookup by building type id.
 *
 * <p>Initialized once during mod construction, auto-refreshed on /reload
 * via {@link com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader}.
 */
public final class BuildingConfigLoader {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(BlockOffset.class, new BlockOffset.Deserializer())
            .registerTypeAdapter(BuildingConfig.class, new BuildingConfig.Deserializer())
            .registerTypeAdapter(WonderEffect.class, new WonderEffect.Deserializer())
            .create();

    private static BuildingConfigLoader INSTANCE;

    private final Map<String, BuildingConfig> configs = new ConcurrentHashMap<>();

    private BuildingConfigLoader() {}

    public static BuildingConfigLoader getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new BuildingConfigLoader();
        }
        return INSTANCE;
    }

    /**
     * Register the "buildings" category with the global data loader.
     * Call once during mod construction.
     */
    public WandscapeDataRegistry<BuildingConfig> registerWith(
            com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader loader) {
        return loader.register("buildings", (id, json) -> parseConfig(json));
    }

    /** Get a config by building type id. */
    @Nullable
    public BuildingConfig get(String id) {
        return configs.get(id);
    }

    /** All loaded configs. */
    public Map<String, BuildingConfig> getAll() {
        return Map.copyOf(configs);
    }

    /** Check if a building type id is known. */
    public boolean has(String id) {
        return configs.containsKey(id);
    }

    // ---- Internal ----

    private synchronized BuildingConfig parseConfig(JsonElement json) {
        BuildingConfig config = GSON.fromJson(json, BuildingConfig.class);
        if (config.id() == null || config.id().isEmpty()) {
            LOGGER.warn("BuildingConfig missing id, skipping");
            return null;
        }
        configs.put(config.id(), config);
        LOGGER.info("loaded BuildingConfig: {} (category={}, blocks={})", config.id(), config.category(), config.pattern().size());
        return config;
    }
}
