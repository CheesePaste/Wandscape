package com.wsteam.wandscape.building.internal;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.data.WorkItem;

import net.minecraft.core.BlockPos;

/**
 * Shared logic for building WorkItems from building configs.
 *
 * <p>Implements the enqueuer rename pattern:
 * <ol>
 *   <li>Read {@link BuildingConfig#blueprint()} ref</li>
 *   <li>Resolve {@code bind} entries: {@code $field_name} → building config field value</li>
 *   <li>Hardcode {@code anchor = [x, y, z]}</li>
 *   <li>Build {@link WorkItem} with blueprint ID from ref (or fallback "build:"+typeId)</li>
 * </ol>
 */
public final class EnqueueHelper {

    private EnqueueHelper() {}

    /**
     * Build a WorkItem for the given building at the given position.
     *
     * @param config         the building config
     * @param pos            the anchor position
     * @param buildingTypeId fallback building type ID
     * @param priority       task priority
     * @return a WorkItem ready for enqueue
     */
    public static WorkItem buildWorkItem(BuildingConfig config, BlockPos pos,
                                          String buildingTypeId, int priority) {
        Map<String, JsonElement> params = new HashMap<>();

        // 1. Hardcode anchor (decision #9)
        params.put("anchor", posToJsonArray(pos));

        // 2. Resolve blueprint bind
        BuildingConfig.BlueprintRef bpRef = config.blueprint();
        String blueprintId;
        if (bpRef != null) {
            blueprintId = bpRef.id();
            for (var bindEntry : bpRef.bind().entrySet()) {
                String blueprintParamName = bindEntry.getKey();
                String fieldRef = bindEntry.getValue(); // e.g. "$pattern"
                String fieldName = fieldRef.startsWith("$") ? fieldRef.substring(1) : fieldRef;
                JsonElement value = resolveField(config, fieldName);
                if (value != null) {
                    params.put(blueprintParamName, value);
                }
            }
        } else {
            // Legacy fallback: no blueprint ref → use "build:<typeId>" blueprint
            // with simple x/y/z params (in addition to anchor)
            blueprintId = "build:" + buildingTypeId;
            params.put("x", new JsonPrimitive(pos.getX()));
            params.put("y", new JsonPrimitive(pos.getY()));
            params.put("z", new JsonPrimitive(pos.getZ()));
        }

        return new WorkItem(blueprintId, params, priority);
    }

    /**
     * Resolve a {@code $field_name} reference against the building config.
     * Returns the field value as a JsonElement, or null if unknown.
     */
    private static JsonElement resolveField(BuildingConfig config, String fieldName) {
        return switch (fieldName) {
            case "id" -> new JsonPrimitive(config.id());
            case "display_name" -> new JsonPrimitive(config.displayName());
            case "category" -> new JsonPrimitive(config.category());
            case "block_id" -> new JsonPrimitive(config.blockId());
            case "pattern" -> patternToJson(config);
            case "block_mapping" -> blockMappingToJson(config);
            case "comfort" -> new JsonPrimitive(config.comfort());
            case "magic" -> new JsonPrimitive(config.magic());
            case "wonder" -> new JsonPrimitive(config.wonder());
            case "maintenance_cost" -> new JsonPrimitive(config.maintenanceCost());
            default -> null;
        };
    }

    /** Convert the pattern (List of BlockOffset) to a JsonArray of [x,y,z] arrays. */
    private static JsonElement patternToJson(BuildingConfig config) {
        JsonArray arr = new JsonArray();
        for (var offset : config.pattern()) {
            JsonArray pos = new JsonArray();
            pos.add(offset.x());
            pos.add(offset.y());
            pos.add(offset.z());
            arr.add(pos);
        }
        return arr;
    }

    /** Convert block_mapping to a JsonObject. */
    private static JsonElement blockMappingToJson(BuildingConfig config) {
        JsonObject obj = new JsonObject();
        for (var entry : config.blockMapping().entrySet()) {
            obj.addProperty(entry.getKey(), entry.getValue());
        }
        return obj;
    }

    /** Convert a BlockPos to a [x, y, z] JsonArray. */
    private static JsonArray posToJsonArray(BlockPos pos) {
        JsonArray arr = new JsonArray();
        arr.add(pos.getX());
        arr.add(pos.getY());
        arr.add(pos.getZ());
        return arr;
    }
}
