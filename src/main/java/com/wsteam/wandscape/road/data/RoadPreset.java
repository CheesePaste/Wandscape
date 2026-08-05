package com.wsteam.wandscape.road.data;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * A road placement preset — either a single block or a weighted-random multi-block set.
 */
public record RoadPreset(String id, String displayName, List<WeightedEntry> blocks) {

    public record WeightedEntry(String blockId, int weight) {}

    /**
     * Custom Gson deserializer matching the scanner export format
     * ({@code id}, {@code display_name}, {@code blocks:[{blockId, weight}]}).
     */
    public static class Deserializer implements JsonDeserializer<RoadPreset> {
        @Override
        public RoadPreset deserialize(JsonElement json, Type typeOfT,
                                       JsonDeserializationContext context) throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();
            String id = obj.has("id") ? obj.get("id").getAsString() : "";
            String name = obj.has("display_name") ? obj.get("display_name").getAsString() : id;
            List<WeightedEntry> blocks = new ArrayList<>();
            if (obj.has("blocks")) {
                JsonArray arr = obj.getAsJsonArray("blocks");
                for (JsonElement el : arr) {
                    JsonObject bo = el.getAsJsonObject();
                    String blockId = bo.has("blockId") ? bo.get("blockId").getAsString() : "";
                    int weight = bo.has("weight") ? bo.get("weight").getAsInt() : 1;
                    if (!blockId.isEmpty()) blocks.add(new WeightedEntry(blockId, weight));
                }
            }
            return new RoadPreset(id, name, List.copyOf(blocks));
        }
    }

    /** Convenience factory for a single-block preset. */
    public static RoadPreset single(String id, String displayName, Block block) {
        var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
        return new RoadPreset(id, displayName,
                List.of(new WeightedEntry(key.toString(), 1)));
    }

    /** Pick a block deterministically by XZ position (weighted random for multi-block presets). */
    public String pickBlock(int x, int z) {
        if (blocks.size() <= 1) return blocks.get(0).blockId();
        return pickWeighted(blocks, x, z);
    }

    /** Position-based deterministic weighted selection (Splitmix64). */
    private static String pickWeighted(List<WeightedEntry> entries, int x, int z) {
        int total = 0;
        for (var e : entries) total += e.weight();

        long h = ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) z * 0xC6A4A7935BD1E995L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = h ^ (h >>> 27);
        int roll = (int) (Math.abs(h) % total);

        int cumulative = 0;
        for (var e : entries) {
            cumulative += e.weight();
            if (roll < cumulative) return e.blockId();
        }
        return entries.get(0).blockId();
    }

    // ---- Built-in presets ----

    public static final List<RoadPreset> DEFAULT_PRESETS = List.of(
            single("dirt_path", "土径", Blocks.DIRT_PATH),
            new RoadPreset("road", "路面", List.of(
                    new WeightedEntry("minecraft:stone", 5),
                    new WeightedEntry("minecraft:gravel", 3),
                    new WeightedEntry("minecraft:stone_bricks", 2))),
            single("grass", "草方块", Blocks.GRASS_BLOCK),
            single("water", "水源", Blocks.WATER),
            single("cobblestone", "圆石", Blocks.COBBLESTONE),
            single("gravel", "砂砾", Blocks.GRAVEL),
            single("oak_planks", "橡木木板", Blocks.OAK_PLANKS));
}
