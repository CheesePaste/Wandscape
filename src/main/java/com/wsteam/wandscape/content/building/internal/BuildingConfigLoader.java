package com.wsteam.wandscape.content.building.internal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.wsteam.wandscape.content.building.data.BlockOffset;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.foundation.registry.dataconfig.internal.WandscapeDataLoader;
import com.wsteam.wandscape.shared.data.WonderEffect;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.registry.WandscapeDataRegistry;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton loader that parses {@link BuildingConfig} from JSON
 * and provides lookup by building type id.
 *
 * <p>Initialized once during mod construction, auto-refreshed on /reload
 * via {@link WandscapeDataLoader}.
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
    private final Map<String, JsonElement> rawJsons = new ConcurrentHashMap<>();

    private BuildingConfigLoader() {}

    public static BuildingConfigLoader getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new BuildingConfigLoader();
        }
        return INSTANCE;
    }

    /** All raw JSON elements for server-to-client network sync. */
    public Map<String, JsonElement> getRawJsons() {
        return Map.copyOf(rawJsons);
    }

    /** Register a building config from JSON string at runtime. */
    public void registerFromJsonString(String jsonStr) {
        try {
            JsonElement json = com.google.gson.JsonParser.parseString(jsonStr);
            registerFromJson(json);
        } catch (Exception e) {
            Log.warn(TAG, "Failed to register config from JSON string: {}", e.getMessage());
        }
    }

    /**
     * Register the "buildings" category with the global data loader.
     * Call once during mod construction.
     */
    public WandscapeDataRegistry<BuildingConfig> registerWith(
            WandscapeDataLoader loader) {
        return loader.register("buildings", (id, json) -> parseConfig(json));
    }

    /** Get a config by building type id. */
    @Nullable
    public BuildingConfig get(@Nullable String id) {
        if (id == null) return null;
        return configs.get(id);
    }

    /** Get the first config whose category matches, or null if none. */
    @Nullable
    public BuildingConfig getByCategory(@Nullable String category) {
        if (category == null) return null;
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
    public boolean has(@Nullable String id) {
        if (id == null) return false;
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
        rawJsons.put(config.id(), json);
        Log.info(TAG, "loaded BuildingConfig: {} (category={}, blocks={})", config.id(), config.category(), config.pattern().size());
        return config;
    }
}
