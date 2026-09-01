package com.wsteam.wandscape.foundation.registry.dataconfig.internal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.util.BalanceValues;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.HashMap;
import java.util.Map;

/**
 * Loads the single runtime-balance override file {@code data/wandscape/wandscape_balance.json}.
 *
 * <p>Flat keys mirror {@link BalanceValues} invariant override names (e.g. {@code "guardRange"}).
 * On (re)load it first {@link BalanceValues#reset()}, then applies each key present in the file —
 * the file is the sole persistent source and {@code /reload} is deterministic. Unknown keys and
 * non-numeric values are logged and skipped (stable-first, no silent failure). Keys prefixed with
 * {@code _} are treated as human annotations (e.g. {@code _comment}) and ignored. An absent file =
 * pure defaults.
 */
public class WandscapeBalanceLoader extends SimpleJsonResourceReloadListener {
    private static final String TAG = "WandscapeBalanceLoader";
    private static final Gson GSON = new GsonBuilder().create();
    private static final String FILE_PATH = "wandscape_balance.json";

    public WandscapeBalanceLoader() {
        super(GSON, "");
    }

    @Override
    protected Map<ResourceLocation, JsonElement> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, JsonElement> out = new HashMap<>();
        for (String ns : manager.getNamespaces()) {
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(ns, FILE_PATH);
            var resource = manager.getResource(rl);
            if (resource.isEmpty()) continue;
            try (var reader = resource.get().openAsReader()) {
                out.put(rl, JsonParser.parseReader(reader));
            } catch (Exception e) {
                Log.warn(TAG, "Failed to read '{}': {}", rl, e.getMessage());
            }
        }
        return out;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager manager, ProfilerFiller profiler) {
        BalanceValues.reset();
        if (data.isEmpty()) {
            Log.info(TAG, "No wandscape_balance.json — default balance values active");
            return;
        }
        int applied = 0;
        for (var entry : data.entrySet()) {
            JsonElement root = entry.getValue();
            if (root == null || !root.isJsonObject()) {
                Log.warn(TAG, "Balance override '{}' must be a JSON object", entry.getKey());
                continue;
            }
            for (var kv : root.getAsJsonObject().entrySet()) {
                String key = kv.getKey();
                if (key.startsWith("_")) continue;
                double value;
                try {
                    value = kv.getValue().getAsDouble();
                } catch (Exception e) {
                    Log.warn(TAG, "Balance key '{}' is not a number — skipped", key);
                    continue;
                }
                if (BalanceValues.apply(key, value)) {
                    applied++;
                } else {
                    Log.warn(TAG, "Unknown balance key '{}' — skipped (see BalanceValues.KNOWN_KEYS)", key);
                }
            }
        }
        Log.info(TAG, "Applied {} balance override(s) from wandscape_balance.json", applied);
    }
}
