package com.wsteam.wandscape.road.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.wsteam.wandscape.shared.registry.WandscapeDataRegistry;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Singleton loader for road placement presets.
 *
 * <p>Seeded with {@link RoadPreset#DEFAULT_PRESETS}, then augmented by any
 * {@code data/wandscape/road_presets/*.json} files from the datapack (mod jar / world datapacks),
 * auto-refreshed on /reload via {@link com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader}.
 * The scanner export registers exported presets at runtime so they are usable immediately.
 */
public final class RoadPresetLoader {
    private static final String TAG = "RoadPresetLoader";
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(RoadPreset.class, new RoadPreset.Deserializer())
            .create();

    private static RoadPresetLoader INSTANCE;

    private final Map<String, RoadPreset> presets = new ConcurrentHashMap<>();

    private RoadPresetLoader() {
        for (RoadPreset p : RoadPreset.DEFAULT_PRESETS) {
            presets.put(p.id(), p);
        }
    }

    public static RoadPresetLoader getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new RoadPresetLoader();
        }
        return INSTANCE;
    }

    /**
     * Register the "road_presets" category with the global data loader.
     * Call once during mod construction.
     */
    public WandscapeDataRegistry<RoadPreset> registerWith(
            com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader loader) {
        return loader.register("road_presets", (id, json) -> parsePreset(json));
    }

    /** Get a preset by id (built-in or datapack-provided). */
    @Nullable
    public RoadPreset get(String id) {
        return presets.get(id);
    }

    /** All presets: built-in defaults first, then datapack additions alphabetically. */
    public List<RoadPreset> getAll() {
        List<RoadPreset> ordered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RoadPreset def : RoadPreset.DEFAULT_PRESETS) {
            RoadPreset cur = presets.get(def.id());
            if (cur != null) {
                ordered.add(cur);
                seen.add(def.id());
            }
        }
        presets.entrySet().stream()
                .filter(e -> !seen.contains(e.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> ordered.add(e.getValue()));
        return List.copyOf(ordered);
    }

    /**
     * Register a preset from JSON at runtime.
     * Used by the scanner export so an exported road preset is immediately usable
     * without waiting for a /reload. On /reload the same JSON is re-parsed from the datapack.
     */
    public void registerFromJson(JsonElement json) {
        RoadPreset preset = parsePreset(json);
        if (preset == null) {
            Log.warn(TAG, "Runtime registration failed for exported road preset JSON");
        }
    }

    // ---- Internal ----

    private synchronized RoadPreset parsePreset(JsonElement json) {
        RoadPreset preset = GSON.fromJson(json, RoadPreset.class);
        if (preset == null || preset.id() == null || preset.id().isEmpty()) {
            Log.warn(TAG, "RoadPreset missing id, skipping");
            return null;
        }
        presets.put(preset.id(), preset);
        Log.info(TAG, "loaded RoadPreset: {} (blocks={})", preset.id(), preset.blocks().size());
        return preset;
    }
}
