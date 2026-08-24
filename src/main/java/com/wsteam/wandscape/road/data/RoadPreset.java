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
    /** Convenience factory for a single-block preset by Block ID string. */
    public static RoadPreset single(String id, String displayName, String blockId) {
        return new RoadPreset(id, displayName,
                List.of(new WeightedEntry(blockId, 1)));
    }

    /** Convenience factory for a single-block preset. */
    public static RoadPreset single(String id, String displayName, Block block) {
        var key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
        return single(id, displayName, key != null ? key.toString() : "minecraft:stone");
    }

    /** Pick a block deterministically by XZ position (weighted random for multi-block presets). */
    public String pickBlock(int x, int z) {
        return pickBlock(x, 0, z);
    }

    /** Pick a block deterministically by XYZ position (weighted random for multi-block presets). */
    public String pickBlock(int x, int y, int z) {
        if (blocks.isEmpty()) return "minecraft:stone";
        if (blocks.size() == 1) return blocks.get(0).blockId();
        return pickWeighted(blocks, x, y, z);
    }

    /** Position-based deterministic weighted selection (Splitmix64). */
    private static String pickWeighted(List<WeightedEntry> entries, int x, int y, int z) {
        int total = 0;
        for (var e : entries) total += Math.max(1, e.weight());
        if (total <= 0) return entries.get(0).blockId();

        long h = ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) y * 0x517CC1B727220A95L) ^ ((long) z * 0xC6A4A7935BD1E995L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = h ^ (h >>> 27);
        int roll = (int) (Math.abs(h) % total);

        int cumulative = 0;
        for (var e : entries) {
            cumulative += Math.max(1, e.weight());
            if (roll < cumulative) return e.blockId();
        }
        return entries.get(0).blockId();
    }

    /**
     * Parse a procedural mix string (e.g. {@code custom:minecraft:stone*5;minecraft:gravel*3})
     * or look up a registered preset by id.
     */
    public static RoadPreset parseOrGet(String id) {
        if (id == null || id.isEmpty()) return null;
        if (id.startsWith("custom:")) {
            String payload = id.substring("custom:".length());
            List<WeightedEntry> entries = new ArrayList<>();
            for (String part : payload.split(";")) {
                if (part.isEmpty()) continue;
                String[] split = part.split("\\*", 2);
                String blockId = split[0];
                int weight = 1;
                if (split.length > 1) {
                    try {
                        weight = Math.max(1, Integer.parseInt(split[1]));
                    } catch (NumberFormatException ignored) {}
                }
                entries.add(new WeightedEntry(blockId, weight));
            }
            if (!entries.isEmpty()) {
                return new RoadPreset(id, "自定义混合", List.copyOf(entries));
            }
        }
        return RoadPresetLoader.getInstance().get(id);
    }

    // ---- Built-in presets ----

    public static final List<RoadPreset> DEFAULT_PRESETS = List.of(
            single("dirt_path", "土径", "minecraft:dirt_path"),
            new RoadPreset("road", "路面", List.of(
                    new WeightedEntry("minecraft:stone", 5),
                    new WeightedEntry("minecraft:gravel", 3),
                    new WeightedEntry("minecraft:stone_bricks", 2))),
            single("grass", "草方块", "minecraft:grass_block"),
            single("water", "水源", "minecraft:water"),
            single("cobblestone", "圆石", "minecraft:cobblestone"),
            single("gravel", "砂砾", "minecraft:gravel"),
            single("oak_planks", "橡木木板", "minecraft:oak_planks"));
}
