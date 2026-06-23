package com.wsteam.wandscape.building.data;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;

/**
 * Parsed from {@code data/wandscape/buildings/<id>.json}.
 * Immutable — created once at JSON load time.
 */
public record BuildingConfig(
        String id,
        @SerializedName("display_name") String displayName,
        String category,
        List<BlockOffset> pattern,
        @SerializedName("block_mapping") Map<String, String> blockMapping,
        int comfort,
        int magic,
        int wonder,
        @SerializedName("maintenance_cost") int maintenanceCost,
        @SerializedName("shutdown_penalty") ShutdownPenalty shutdownPenalty,
        QueueDef queue,
        @SerializedName("unlock_requirement") UnlockRequirement unlockRequirement,
        @Nullable BoundaryBox boundary,
        @Nullable BlueprintRef blueprint,
        @Nullable NodeConfig nodeConfig
) {
    public record ShutdownPenalty(
            @SerializedName("output_reduction") double outputReduction,
            @SerializedName("time_multiplier") double timeMultiplier
    ) {
        public static final ShutdownPenalty DEFAULT = new ShutdownPenalty(0.5, 2.0);
    }

    public record QueueDef(
            int capacity,
            @SerializedName("task_types") List<String> taskTypes
    ) {
        public static final QueueDef DEFAULT = new QueueDef(5, List.of("building"));
    }

    public record UnlockRequirement(
            @SerializedName("min_comfort") int minComfort,
            @SerializedName("min_magic")   int minMagic,
            @SerializedName("min_wonder")  int minWonder
    ) {
        public static final UnlockRequirement NONE = new UnlockRequirement(0, 0, 0);
    }

    /**
     * Node building configuration. Only present when {@code category == "node"}.
     */
    public record NodeConfig(
            String blueprint,
            String element,
            @SerializedName("amount_per_harvest") int amountPerHarvest,
            @SerializedName("channel_ticks") int channelTicks,
            @SerializedName("mana_cost") int manaCost,
            @SerializedName("wand_level") Map<String, Integer> wandLevel
    ) {
        public NodeConfig {
            if (manaCost <= 0) manaCost = 5; // default 5 mana
            if (wandLevel == null) wandLevel = Collections.emptyMap();
        }
    }

    /**
     * Reference to a Blueprint DSL JSON that defines task logic for this building.
     *
     * @param id   the blueprint ID (e.g. "build:place_structure")
     * @param bind key = blueprint param name, value = {@code $field_name}
     *             bare variable reference to a building JSON field
     */
    public record BlueprintRef(
            String id,
            Map<String, String> bind
    ) {
        public BlueprintRef {
            if (bind == null) bind = Collections.emptyMap();
        }
    }

    /** AABB 包围盒，角点相对于 anchor。 */
    public record BoundaryBox(
            BlockOffset min,
            BlockOffset max
    ) {
        public BoundaryBox {
            if (min == null || max == null) {
                throw new IllegalArgumentException("boundary min and max must not be null");
            }
        }

        /** 遍历 AABB 内所有坐标（含边界）。 */
        public List<BlockOffset> allPositions() {
            List<BlockOffset> result = new ArrayList<>();
            for (int x = min.x(); x <= max.x(); x++) {
                for (int y = min.y(); y <= max.y(); y++) {
                    for (int z = min.z(); z <= max.z(); z++) {
                        result.add(new BlockOffset(x, y, z));
                    }
                }
            }
            return result;
        }
    }

    /**
     * Custom Gson deserializer that applies defaults for missing optional sections.
     */
    public static class Deserializer implements JsonDeserializer<BuildingConfig> {
        @Override
        public BuildingConfig deserialize(JsonElement json, Type typeOfT,
                                           JsonDeserializationContext context) throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();

            String id = getString(obj, "id", "");
            String displayName = getString(obj, "display_name", "");
            String category = getString(obj, "category", "basic");

            // Pattern
            List<BlockOffset> pattern = List.of();
            if (obj.has("pattern")) {
                List<BlockOffset> list = new ArrayList<>();
                JsonArray arr = obj.getAsJsonArray("pattern");
                BlockOffset.Deserializer offsetDs = new BlockOffset.Deserializer();
                for (JsonElement el : arr) {
                    list.add(offsetDs.deserialize(el, BlockOffset.class, context));
                }
                pattern = List.copyOf(list);
            }

            // Block mapping
            Map<String, String> blockMapping = Map.of();
            if (obj.has("block_mapping")) {
                JsonObject map = obj.getAsJsonObject("block_mapping");
                Map<String, String> m = new HashMap<>();
                for (var entry : map.entrySet()) {
                    m.put(entry.getKey(), entry.getValue().getAsString());
                }
                blockMapping = Map.copyOf(m);
            }

            int comfort = getInt(obj, "comfort", 0);
            int magic = getInt(obj, "magic", 0);
            int wonder = getInt(obj, "wonder", 0);
            int maintenanceCost = getInt(obj, "maintenance_cost", 0);

            ShutdownPenalty shutdownPenalty = ShutdownPenalty.DEFAULT;
            if (obj.has("shutdown_penalty")) {
                shutdownPenalty = context.deserialize(
                        obj.get("shutdown_penalty"), ShutdownPenalty.class);
            }

            QueueDef queue = QueueDef.DEFAULT;
            if (obj.has("queue")) {
                queue = context.deserialize(obj.get("queue"), QueueDef.class);
            }

            UnlockRequirement unlockRequirement = UnlockRequirement.NONE;
            if (obj.has("unlock_requirement")) {
                unlockRequirement = context.deserialize(
                        obj.get("unlock_requirement"), UnlockRequirement.class);
            }

            // Boundary
            BoundaryBox boundary = null;
            if (obj.has("boundary")) {
                JsonObject bObj = obj.getAsJsonObject("boundary");
                BlockOffset.Deserializer offsetDs2 = new BlockOffset.Deserializer();
                BlockOffset bMin = offsetDs2.deserialize(bObj.get("min"), BlockOffset.class, context);
                BlockOffset bMax = offsetDs2.deserialize(bObj.get("max"), BlockOffset.class, context);
                boundary = new BoundaryBox(bMin, bMax);
            }

            // Blueprint reference
            BlueprintRef blueprint = null;
            if (obj.has("blueprint")) {
                JsonObject bpObj = obj.getAsJsonObject("blueprint");
                String bpId = bpObj.get("id").getAsString();
                Map<String, String> bind = Collections.emptyMap();
                if (bpObj.has("bind")) {
                    JsonObject bindObj = bpObj.getAsJsonObject("bind");
                    Map<String, String> m = new HashMap<>();
                    for (var entry : bindObj.entrySet()) {
                        m.put(entry.getKey(), entry.getValue().getAsString());
                    }
                    bind = Map.copyOf(m);
                }
                blueprint = new BlueprintRef(bpId, bind);
            }

            // Node config (only for category=node buildings)
            NodeConfig nodeConfig = null;
            if (obj.has("node_config")) {
                nodeConfig = context.deserialize(obj.get("node_config"), NodeConfig.class);
            }

            return new BuildingConfig(id, displayName, category,
                    pattern, blockMapping,
                    comfort, magic, wonder, maintenanceCost,
                    shutdownPenalty, queue, unlockRequirement, boundary, blueprint, nodeConfig);
        }

        private static String getString(JsonObject obj, String key, String def) {
            return obj.has(key) ? obj.get(key).getAsString() : def;
        }

        private static int getInt(JsonObject obj, String key, int def) {
            return obj.has(key) ? obj.get(key).getAsInt() : def;
        }
    }
}
