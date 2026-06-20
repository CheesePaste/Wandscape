package com.wsteam.wandscape.building.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

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
     * Register a building with {@link BuildingApi} if it hasn't been registered yet.
     * Handles both command-placed and naturally-placed buildings via right-click.
     *
     * @param pos            the building block position
     * @param config         the building config
     * @param buildingTypeId building type identifier
     * @return true if newly registered, false if already registered
     */
    public static boolean registerIfAbsent(BlockPos pos, BuildingConfig config, String buildingTypeId) {
        try {
            BuildingApi api = WandscapeApis.getBuildingApi();
            if (api.getBuildingAt(pos) != null) {
                return false; // already registered
            }

            UUID buildingId = UUID.randomUUID();
            BuildingDataImpl data = new BuildingDataImpl(
                    buildingId,
                    buildingTypeId,
                    config.category(),
                    pos,
                    null, // colonyId — stage 4
                    config.comfort(),
                    config.magic(),
                    config.wonder(),
                    config.maintenanceCost(),
                    config.queue().capacity()
            );
            api.registerBuilding(data);
            return true;
        } catch (IllegalStateException e) {
            return false; // API not available
        }
    }

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
            // 3. If boundary is present, pre-compute clear_offsets
            //    (boundary volume minus pattern offsets — those get placed, not cleared)
            if (config.boundary() != null) {
                params.put("clear_offsets", computeClearOffsets(config));
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
            case "boundary" -> boundaryToJson(config);
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

    /** Convert boundary to a JsonObject {min: [x,y,z], max: [x,y,z]}. */
    private static JsonElement boundaryToJson(BuildingConfig config) {
        var b = config.boundary();
        JsonObject obj = new JsonObject();
        obj.add("min", offsetToJson(b.min()));
        obj.add("max", offsetToJson(b.max()));
        return obj;
    }

    /**
     * Compute offsets to clear: ALL positions within the AABB boundary
     * MINUS the anchor [0,0,0] (removing it destroys the BE).
     *
     * <p>Pattern positions ARE included — the clear phase wipes the entire
     * box to air, then the build phase places the pattern blocks on clean
     * ground. Duplicate air→block is harmless (TransformOp.place overwrites).
     */
    static JsonElement computeClearOffsets(BuildingConfig config) {
        JsonArray arr = new JsonArray();
        for (BlockOffset off : config.boundary().allPositions()) {
            if (!"0,0,0".equals(off.toKey())) {
                arr.add(offsetToJson(off));
            }
        }
        return arr;
    }

    /** Convert a BlockOffset to [x, y, z] JsonArray. */
    private static JsonArray offsetToJson(BlockOffset off) {
        JsonArray arr = new JsonArray();
        arr.add(off.x());
        arr.add(off.y());
        arr.add(off.z());
        return arr;
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
