package com.wsteam.wandscape.content.building.internal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.wsteam.wandscape.content.building.data.BlockOffset;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.data.BuildingPackage;
import com.wsteam.wandscape.foundation.registry.dataconfig.internal.WandscapeDataLoader;
import com.wsteam.wandscape.content.building.data.WonderEffect;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.registry.WandscapeDataRegistry;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton loader that parses {@link BuildingConfig} and {@link BuildingPackage}
 * from JSON and provides lookup by building type id with dual-key alias resolution.
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
    private final Map<String, String> aliasToFullId = new ConcurrentHashMap<>();
    private final Map<String, BuildingPackage> packages = new ConcurrentHashMap<>();
    private final Map<String, JsonElement> packageRawJsons = new ConcurrentHashMap<>();

    private BuildingConfigLoader() {
        ensureDefaultPackage();
    }

    public static BuildingConfigLoader getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new BuildingConfigLoader();
        }
        return INSTANCE;
    }

    private void ensureDefaultPackage() {
        packages.putIfAbsent(BuildingPackage.DEFAULT_ID, BuildingPackage.defaultPackage());
    }

    /** Clear all loaded building configs, aliases, and packages (called before reload). */
    public synchronized void clear() {
        configs.clear();
        rawJsons.clear();
        aliasToFullId.clear();
        packages.clear();
        packageRawJsons.clear();
        ensureDefaultPackage();
    }

    /** All raw JSON elements for server-to-client network sync. */
    public Map<String, JsonElement> getRawJsons() {
        return Map.copyOf(rawJsons);
    }

    /** All package raw JSON elements. */
    public Map<String, JsonElement> getPackageRawJsons() {
        return Map.copyOf(packageRawJsons);
    }

    /** Register a building config from JSON string at runtime. */
    public void registerFromJsonString(String jsonStr) {
        registerFromJsonString(BuildingPackage.DEFAULT_ID, jsonStr);
    }

    /** Register a building config from JSON string under a specific package at runtime. */
    public void registerFromJsonString(String packageId, String jsonStr) {
        try {
            JsonElement json = com.google.gson.JsonParser.parseString(jsonStr);
            registerFromJson(packageId, json);
        } catch (Exception e) {
            Log.warn(TAG, "Failed to register config from JSON string: {}", e.getMessage());
        }
    }

    /**
     * Register the "buildings" category with the global data loader.
     * Call once during mod construction.
     */
    public WandscapeDataRegistry<BuildingConfig> registerWith(WandscapeDataLoader loader) {
        return loader.register("buildings", this::loadFromDataPath, this::clear);
    }

    /**
     * Entry loader from WandscapeDataLoader scanning.
     * Handles package.json as well as nested building json files.
     */
    public synchronized BuildingConfig loadFromDataPath(String pathId, JsonElement json) {
        if (pathId.equals("package")) {
            // Root package.json (default core package meta)
            try {
                BuildingPackage pkg = BuildingPackage.fromJson(BuildingPackage.DEFAULT_ID, json.getAsJsonObject());
                packages.put(pkg.id(), pkg);
                packageRawJsons.put(pkg.id(), json);
                Log.info(TAG, "loaded root BuildingPackage: {}", pkg.id());
            } catch (Exception e) {
                Log.warn(TAG, "Failed to parse root package.json: {}", e.getMessage());
            }
            return null;
        }

        if (pathId.endsWith("/package")) {
            // Subfolder package.json: e.g. "medieval/package"
            int slashIdx = pathId.indexOf('/');
            String pkgId = pathId.substring(0, slashIdx);
            try {
                BuildingPackage pkg = BuildingPackage.fromJson(pkgId, json.getAsJsonObject());
                packages.put(pkg.id(), pkg);
                packageRawJsons.put(pkg.id(), json);
                Log.info(TAG, "loaded BuildingPackage: {} ({})", pkg.id(), pkg.name());
            } catch (Exception e) {
                Log.warn(TAG, "Failed to parse package.json for '{}': {}", pkgId, e.getMessage());
            }
            return null;
        }

        // Normal building file
        String pkgId = BuildingPackage.DEFAULT_ID;
        if (pathId.contains("/")) {
            String firstPart = pathId.substring(0, pathId.indexOf('/'));
            if (!"deprecated".equals(firstPart)) {
                pkgId = firstPart;
            }
        }

        return parseAndRegister(pkgId, json);
    }

    /** Get a config by building type id (supports full ID, alias, and legacy short name). */
    @Nullable
    public BuildingConfig get(@Nullable String id) {
        if (id == null) return null;

        // 1. Direct hit by fullId
        BuildingConfig config = configs.get(id);
        if (config != null) return config;

        // 2. Alias lookup
        String fullId = aliasToFullId.get(id);
        if (fullId != null) {
            config = configs.get(fullId);
            if (config != null) return config;
        }

        // 3. Fallback: if caller passed "default:foo" but config was registered with bare "foo"
        if (id.startsWith(BuildingPackage.DEFAULT_ID + ":")) {
            String bare = id.substring(BuildingPackage.DEFAULT_ID.length() + 1);
            config = configs.get(bare);
            if (config != null) return config;
        }

        return null;
    }

    /** Resolve any ID or alias to its canonical full ID (<package_id>:<building_id>). */
    public String resolveCanonicalId(String idOrAlias) {
        if (idOrAlias == null) return "";
        if (configs.containsKey(idOrAlias)) return idOrAlias;
        String full = aliasToFullId.get(idOrAlias);
        if (full != null) return full;
        return idOrAlias;
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

    /** All loaded configs (keyed by canonical full ID). */
    public Map<String, BuildingConfig> getAll() {
        return Map.copyOf(configs);
    }

    /** Check if a building type id or alias is known. */
    public boolean has(@Nullable String id) {
        if (id == null) return false;
        return configs.containsKey(id) || aliasToFullId.containsKey(id);
    }

    /** Get a package by package id. */
    @Nullable
    public BuildingPackage getPackage(@Nullable String packageId) {
        if (packageId == null) return null;
        ensureDefaultPackage();
        return packages.get(packageId);
    }

    /** All registered packages sorted by priority. */
    public List<BuildingPackage> getAllPackages() {
        ensureDefaultPackage();
        List<BuildingPackage> list = new ArrayList<>(packages.values());
        list.sort(Comparator.comparingInt(BuildingPackage::priority).thenComparing(BuildingPackage::id));
        return Collections.unmodifiableList(list);
    }

    /** Get all configs belonging to a package. */
    public List<BuildingConfig> getConfigsByPackage(@Nullable String packageId) {
        if (packageId == null) return List.of();
        List<BuildingConfig> result = new ArrayList<>();
        for (BuildingConfig cfg : configs.values()) {
            if (packageId.equals(cfg.packageId())) {
                result.add(cfg);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** Register a package metadata directly. */
    public void registerPackage(BuildingPackage pkg) {
        if (pkg == null || pkg.id() == null) return;
        packages.put(pkg.id(), pkg);
    }

    /**
     * Register a building config from JSON at runtime.
     * Used by scanner export so an exported building is immediately buildable.
     */
    public void registerFromJson(JsonElement json) {
        registerFromJson(BuildingPackage.DEFAULT_ID, json);
    }

    /**
     * Register a building config from JSON for a target package at runtime.
     */
    public void registerFromJson(String packageId, JsonElement json) {
        BuildingConfig config = parseAndRegister(packageId != null ? packageId : BuildingPackage.DEFAULT_ID, json);
        if (config == null) {
            Log.warn(TAG, "Runtime registration failed for exported building JSON");
        }
    }

    // ---- Internal ----

    private synchronized BuildingConfig parseAndRegister(String inferredPackageId, JsonElement json) {
        BuildingConfig config = GSON.fromJson(json, BuildingConfig.class);
        if (config == null || config.id() == null || config.id().isEmpty()) {
            Log.warn(TAG, "BuildingConfig missing id, skipping");
            return null;
        }

        String rawId = config.id();
        String pkgId = inferredPackageId;
        // If JSON explicitly declared package_id (and not default fallback), honor it
        if (config.packageId() != null && !BuildingPackage.DEFAULT_ID.equals(config.packageId())) {
            pkgId = config.packageId();
        }

        // Canonical full ID: <package_id>:<raw_id>
        String fullId;
        if (rawId.contains(":")) {
            fullId = rawId;
            pkgId = rawId.substring(0, rawId.indexOf(':'));
        } else {
            fullId = pkgId + ":" + rawId;
        }

        config = config.withIdAndPackageId(fullId, pkgId);
        configs.put(fullId, config);
        rawJsons.put(fullId, json);

        // Alias mapping:
        // Default package always registers rawId alias (e.g. "cottage" -> "default:cottage")
        if (BuildingPackage.DEFAULT_ID.equals(pkgId)) {
            aliasToFullId.put(rawId, fullId);
            if (rawId.contains("/")) {
                String shortName = rawId.substring(rawId.lastIndexOf('/') + 1);
                aliasToFullId.putIfAbsent(shortName, fullId);
            }
        } else {
            if (!aliasToFullId.containsKey(rawId)) {
                aliasToFullId.put(rawId, fullId);
            } else {
                String existing = aliasToFullId.get(rawId);
                if (!existing.startsWith(BuildingPackage.DEFAULT_ID + ":") && !existing.equals(fullId)) {
                    // Ambiguous across multiple custom packs, remove bare alias
                    aliasToFullId.remove(rawId);
                }
            }
        }

        // Ensure package exists in packages map
        packages.computeIfAbsent(pkgId, k -> new BuildingPackage(
                k, k, "", "", "1.0.0", "minecraft:stone_bricks", 100, List.of()
        ));

        Log.info(TAG, "loaded BuildingConfig: {} [package={}] (category={}, blocks={})",
                fullId, pkgId, config.category(), config.pattern().size());
        return config;
    }
}
