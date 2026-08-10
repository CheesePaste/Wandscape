package com.wsteam.wandscape.building.internal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.data.WonderEffect;
import com.wsteam.wandscape.shared.registry.WandscapeDataRegistry;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Singleton loader that parses {@link BuildingConfig} from JSON
 * and provides lookup by building type id.
 *
 * <p>Initialized once during mod construction, auto-refreshed on /reload
 * via {@link com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader}.
 */
public final class BuildingConfigLoader {
    private static final String TAG = "BuildingConfigLoader";
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

    /** Get the first config whose category matches, or null if none. */
    @Nullable
    public BuildingConfig getByCategory(String category) {
        for (BuildingConfig config : configs.values()) {
            if (category.equals(config.category())) {
                return config;
            }
        }
        return null;
    }

    /** All loaded configs. */
    public Map<String, BuildingConfig> getAll() {
        return Map.copyOf(configs);
    }

    /** Check if a building type id is known. */
    public boolean has(String id) {
        return configs.containsKey(id);
    }

    /**
     * Register a building config from JSON at runtime.
     * Used by the scanner export so an exported building is immediately buildable
     * without waiting for a /reload. On /reload the same JSON is re-parsed from the datapack.
     */
    public void registerFromJson(JsonElement json) {
        BuildingConfig config = parseConfig(json);
        if (config == null) {
            Log.warn(TAG, "Runtime registration failed for exported building JSON");
        }
    }

    // ---- Internal ----

    private synchronized BuildingConfig parseConfig(JsonElement json) {
        BuildingConfig config = GSON.fromJson(json, BuildingConfig.class);
        if (config.id() == null || config.id().isEmpty()) {
            Log.warn(TAG, "BuildingConfig missing id, skipping");
            return null;
        }
        configs.put(config.id(), config);
        Log.info(TAG, "loaded BuildingConfig: {} (category={}, blocks={})", config.id(), config.category(), config.pattern().size());
        return config;
    }
}
