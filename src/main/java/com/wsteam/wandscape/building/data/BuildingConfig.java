package com.wsteam.wandscape.building.data;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        @SerializedName("block_id") String blockId,
        List<BlockOffset> pattern,
        @SerializedName("block_mapping") Map<String, String> blockMapping,
        int comfort,
        int magic,
        int wonder,
        @SerializedName("maintenance_cost") int maintenanceCost,
        @SerializedName("shutdown_penalty") ShutdownPenalty shutdownPenalty,
        QueueDef queue,
        @SerializedName("unlock_requirement") UnlockRequirement unlockRequirement
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
            @SerializedName("min_wonder") int minWonder
    ) {
        public static final UnlockRequirement NONE = new UnlockRequirement(0);
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
            String blockId = getString(obj, "block_id", "");

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

            return new BuildingConfig(id, displayName, category, blockId,
                    pattern, blockMapping,
                    comfort, magic, wonder, maintenanceCost,
                    shutdownPenalty, queue, unlockRequirement);
        }

        private static String getString(JsonObject obj, String key, String def) {
            return obj.has(key) ? obj.get(key).getAsString() : def;
        }

        private static int getInt(JsonObject obj, String key, int def) {
            return obj.has(key) ? obj.get(key).getAsInt() : def;
        }
    }
}
