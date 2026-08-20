package com.wsteam.wandscape.worldreloader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wsteam.wandscape.shared.log.Log;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.loading.FMLPaths;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for the WorldReloader terrain transformation system.
 * Persisted in config/wandscape_worldreloader.json.
 */
public class WorldReloaderConfig {

    private static final String TAG = "WorldReloaderConfig";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static File getConfigFile() {
        try {
            if (FMLPaths.CONFIGDIR != null && FMLPaths.CONFIGDIR.get() != null) {
                return FMLPaths.CONFIGDIR.get().resolve("wandscape_worldreloader.json").toFile();
            }
        } catch (Throwable ignored) {}
        return new File("config/wandscape_worldreloader.json");
    }

    public enum OperationMode {
        STANDARD, SURFACE
    }

    public enum PositionMode {
        DETECT, FIXED, BIOME, RANDOM
    }

    public OperationMode mode = OperationMode.STANDARD;
    public PositionMode posMode = PositionMode.DETECT;

    public int posX = 0;
    public int posY = 64;
    public int posZ = 0;

    public String targetBiomeId = "minecraft:plains";
    public int searchRadius = 6400;
    public int randomRadius = 1000;

    public int maxRadius = 76;
    public int itemCleanupInterval = 20;
    public boolean debug = false;
    public boolean preserveBeacon = true;
    public boolean changeBiome = true;
    public String minPermission = "op"; // "player", "op", "disabled"

    public String targetBlock = "minecraft:beacon";
    public String dimension = "minecraft:overworld";

    public List<ItemRequirement> targetBlockDict = new ArrayList<>(List.of(
            new ItemRequirement("minecraft:nether_star", 1)
    ));

    // Standard mode parameters
    public int paddingCount = 12;
    public int totalSteps2 = 3;
    public int yMin = 40;
    public int yMaxThanSurface = 80;

    // Surface mode parameters
    public int totalSteps = 10;
    public int height = 15;
    public int depth = 15;

    public static class ItemRequirement {
        public String itemId = "";
        public int count = 1;
        public boolean enabled = true;

        public ItemRequirement() {}

        public ItemRequirement(String itemId, int count) {
            this.itemId = itemId;
            this.count = count;
            this.enabled = true;
        }
    }

    public static class StructureMapping {
        public String blockId = "";
        public String structureId = "";
        public boolean enabled = true;

        public StructureMapping() {}

        public StructureMapping(String blockId, String structureId) {
            this.blockId = blockId;
            this.structureId = structureId;
            this.enabled = true;
        }
    }

    public static class BiomeMapping {
        public String blockId = "";
        public String biomeId = "";
        public boolean enabled = true;

        public BiomeMapping() {}

        public BiomeMapping(String blockId, String biomeId) {
            this.blockId = blockId;
            this.biomeId = biomeId;
            this.enabled = true;
        }
    }

    public List<StructureMapping> structureMappings = new ArrayList<>(List.of(
            new StructureMapping("minecraft:target", "minecraft:village_snowy"),
            new StructureMapping("minecraft:cobblestone", "minecraft:pillager_outpost"),
            new StructureMapping("minecraft:mossy_cobblestone", "minecraft:jungle_pyramid"),
            new StructureMapping("minecraft:smooth_sandstone", "minecraft:desert_pyramid"),
            new StructureMapping("minecraft:bookshelf", "minecraft:mansion")
    ));

    public List<BiomeMapping> biomeMappings = new ArrayList<>(List.of(
            new BiomeMapping("minecraft:grass_block", "minecraft:plains"),
            new BiomeMapping("minecraft:jungle_log", "minecraft:jungle"),
            new BiomeMapping("minecraft:sand", "minecraft:desert"),
            new BiomeMapping("minecraft:snow_block", "minecraft:snowy_plains"),
            new BiomeMapping("minecraft:dark_oak_log", "minecraft:dark_forest"),
            new BiomeMapping("minecraft:mycelium", "minecraft:mushroom_fields"),
            new BiomeMapping("minecraft:oak_log", "minecraft:forest"),
            new BiomeMapping("minecraft:amethyst_block", "minecraft:flower_forest"),
            new BiomeMapping("minecraft:hay_block", "minecraft:sunflower_plains"),
            new BiomeMapping("minecraft:moss_block", "minecraft:swamp"),
            new BiomeMapping("minecraft:podzol", "minecraft:old_growth_pine_taiga"),
            new BiomeMapping("minecraft:mud", "minecraft:mangrove_swamp"),
            new BiomeMapping("minecraft:sandstone", "minecraft:badlands"),
            new BiomeMapping("minecraft:red_sandstone", "minecraft:eroded_badlands"),
            new BiomeMapping("minecraft:ice", "minecraft:ice_spikes"),
            new BiomeMapping("minecraft:packed_ice", "minecraft:frozen_peaks"),
            new BiomeMapping("minecraft:birch_log", "minecraft:birch_forest"),
            new BiomeMapping("minecraft:spruce_log", "minecraft:taiga"),
            new BiomeMapping("minecraft:acacia_log", "minecraft:savanna"),
            new BiomeMapping("minecraft:cherry_log", "minecraft:cherry_grove")
    ));

    public Map<String, String> getStructureMap() {
        Map<String, String> map = new HashMap<>();
        for (StructureMapping m : structureMappings) {
            if (m.enabled) {
                map.put(normalizeId(m.blockId), m.structureId);
            }
        }
        return map;
    }

    public Map<String, String> getBiomeMap() {
        Map<String, String> map = new HashMap<>();
        for (BiomeMapping m : biomeMappings) {
            if (m.enabled) {
                map.put(normalizeId(m.blockId), m.biomeId);
            }
        }
        return map;
    }

    public static String normalizeId(String id) {
        if (id == null || id.isEmpty()) return "";
        if (!id.contains(":")) {
            return "minecraft:" + id;
        }
        return id;
    }

    public static WorldReloaderConfig load() {
        File file = getConfigFile();
        if (file.exists()) {
            try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                WorldReloaderConfig cfg = GSON.fromJson(reader, WorldReloaderConfig.class);
                if (cfg != null) {
                    return cfg;
                }
            } catch (Exception e) {
                Log.error(TAG, "Failed to load WorldReloader config, falling back to default: {}", e.getMessage());
            }
        }
        WorldReloaderConfig cfg = new WorldReloaderConfig();
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            File file = getConfigFile();
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            Log.error(TAG, "Failed to save WorldReloader config: {}", e.getMessage());
        }
    }
}
