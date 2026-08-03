package com.wsteam.wandscape.building.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.engine.ColonyApiImpl;
import com.wsteam.wandscape.projection.BuildingRotation;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.warehouse.ColonyItemBank;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Shared logic for building WorkItems from building configs.
 */
public final class EnqueueHelper {

    private static final String TAG = "EnqueueHelper";

    private EnqueueHelper() {}

    /**
     * Register a building with {@link BuildingApi} if it hasn't been registered yet.
     * On each colony's first building registration, seeds that colony's warehouse
     * with starter elements (once per colony).
     *
     * @param pos            the anchor position
     * @param config         the building config
     * @param buildingTypeId building type identifier
     * @return the newly registered {@link BuildingState}, or null if the position was
     *         already occupied or the building overlaps an existing one
     */
    @Nullable
    public static BuildingState registerIfAbsent(BlockPos pos, BuildingConfig config, String buildingTypeId) {
        return registerIfAbsent(pos, config, buildingTypeId, 0);
    }

    /**
     * Register a building with optional rotation.
     *
     * @param pos            the anchor position
     * @param config         the building config
     * @param buildingTypeId building type identifier
     * @param rotationSteps  number of 90° CCW rotations (0-3)
     * @return the newly registered {@link BuildingState}, or null if the position was
     *         already occupied or the building overlaps an existing one
     */
    @Nullable
    public static BuildingState registerIfAbsent(BlockPos pos, BuildingConfig config, String buildingTypeId, int rotationSteps) {
        try {
            BuildingApi api = WandscapeApis.getBuildingApi();
            if (api.getBuildingAt(pos) != null) {
                return null;
            }

            UUID buildingId = UUID.randomUUID();
            BoundingBox bounds;
            if (config.boundary() != null) {
                BuildingConfig.BoundaryBox rotatedBoundary = BuildingRotation.rotateBoundary(config.boundary(), rotationSteps);
                bounds = BuildingSavedData.computeWorldBox(pos, rotatedBoundary);
            } else {
                bounds = new BoundingBox(pos);
            }

            BuildingState state = new BuildingState(
                    buildingId,
                    buildingTypeId,
                    config.category(),
                    pos,
                    bounds,
                    config.comfort(),
                    config.magic(),
                    config.wonder(),
                    config.queue().capacity()
            );
            state.setRotationSteps(rotationSteps);
            api.registerBuilding(state);

            // Assign colony if one exists nearby
            ColonyApiImpl.get().assignColonyIfPossible(state);

            // First building registered for this colony → seed warehouse with starter
            // elements (per-colony, persisted in ColonyItemBank).
            if (state.getColonyId() != null) {
                seedInitialElementsIfNeeded(state.getColonyId());
            }

            return state;
        } catch (IllegalStateException e) {
            return null;
        } catch (BuildingOverlapException e) {
            return null;
        }
    }

    /**
     * Build a WorkItem for the given building at the given position.
     * Clear-offsets are unfiltered (may include other buildings' blocks).
     */
    public static WorkItem buildWorkItem(BuildingConfig config, BlockPos pos,
                                          String buildingTypeId, int priority) {
        return buildWorkItem(config, pos, buildingTypeId, priority, null, null);
    }

    /**
     * Build a WorkItem with optional other-building filtering for clear_offsets.
     * When sd and buildingId are provided, positions belonging to other buildings
     * are excluded from the clear list (prevents damaging nearby structures).
     */
    public static WorkItem buildWorkItem(BuildingConfig config, BlockPos pos,
                                          String buildingTypeId, int priority,
                                          @Nullable BuildingSavedData sd,
                                          @Nullable UUID buildingId) {
        return buildWorkItem(config, pos, buildingTypeId, priority, sd, buildingId, 0);
    }

    /**
     * Build a WorkItem with rotation support. When {@code rotationSteps > 0},
     * the pattern offsets, block_mapping keys and values, and clear_offsets are
     * all rotated 90° CCW around the Y axis by the specified number of steps.
     */
    public static WorkItem buildWorkItem(BuildingConfig config, BlockPos pos,
                                          String buildingTypeId, int priority,
                                          @Nullable BuildingSavedData sd,
                                          @Nullable UUID buildingId,
                                          int rotationSteps) {
        return buildWorkItem(config, pos, buildingTypeId, priority, sd, buildingId, rotationSteps, false);
    }

    /**
     * Build a WorkItem with rotation support and optional material skip.
     * When {@code skipMaterials} is true, material_list and material_counts
     * are omitted so the NPC does not request any items from the warehouse.
     */
    public static WorkItem buildWorkItem(BuildingConfig config, BlockPos pos,
                                          String buildingTypeId, int priority,
                                          @Nullable BuildingSavedData sd,
                                          @Nullable UUID buildingId,
                                          int rotationSteps,
                                          boolean skipMaterials) {
        Map<String, JsonElement> params = new HashMap<>();

        params.put("anchor", posToJsonArray(pos));

        BuildingConfig.BlueprintRef bpRef = config.blueprint();
        String blueprintId;
        if (bpRef != null) {
            blueprintId = bpRef.id();
            for (var bindEntry : bpRef.bind().entrySet()) {
                String blueprintParamName = bindEntry.getKey();
                String fieldRef = bindEntry.getValue();
                String fieldName = fieldRef.startsWith("$") ? fieldRef.substring(1) : fieldRef;
                JsonElement value = resolveField(config, fieldName);
                if (value != null) {
                    params.put(blueprintParamName, value);
                }
            }
            // Auto-add blocks_nbt if not provided by bind (backward compat with older building JSONs)
            if (!params.containsKey("blocks_nbt")) {
                params.put("blocks_nbt", blockNbtToJson(config));
            }
            if (config.boundary() != null) {
                if (sd != null && buildingId != null) {
                    params.put("clear_offsets", computeClearOffsetsFiltered(config, sd, pos, buildingId));
                } else {
                    params.put("clear_offsets", computeClearOffsets(config));
                }
            }
            // material_list + material_counts: auto-computed from pattern → block_mapping
            // When skipMaterials is true, emit empty arrays so the blueprint
            // always has the param; the NPC simply requests nothing.
            if (!params.containsKey("material_list")) {
                if (skipMaterials) {
                    params.put("material_list", new JsonArray());
                    params.put("material_counts", new JsonObject());
                } else {
                    var materialData = computeMaterialData(config);
                    if (materialData != null) {
                        params.put("material_list", materialData.list());
                        params.put("material_counts", materialData.counts());
                    } else {
                        // No element-mapped blocks → nothing to request
                        params.put("material_list", new JsonArray());
                        params.put("material_counts", new JsonObject());
                    }
                }
            }

            // ── Apply rotation to params if needed ──
            if (rotationSteps != 0) {
                rotationSteps = rotationSteps & 3;
                // Rotate pattern (offsets)
                if (params.containsKey("offsets")) {
                    params.put("offsets", rotatePatternJson(
                            params.get("offsets").getAsJsonArray(), rotationSteps));
                }
                // Rotate block_mapping (keys and block state values)
                if (params.containsKey("blocks")) {
                    params.put("blocks", rotateBlockMappingJson(
                            params.get("blocks").getAsJsonObject(), rotationSteps));
                }
                // Rotate block_nbt (keys only — values are opaque base64 strings)
                if (params.containsKey("blocks_nbt")) {
                    params.put("blocks_nbt", rotateBlockNbtJson(
                            params.get("blocks_nbt").getAsJsonObject(), rotationSteps));
                }
                // Rotate clear_offsets
                if (params.containsKey("clear_offsets")) {
                    params.put("clear_offsets", rotateOffsetsJson(
                            params.get("clear_offsets").getAsJsonArray(), rotationSteps));
                }
                // Rotate tourist_interact_aabb
                if (params.containsKey("tourist_interact_aabb")) {
                    params.put("tourist_interact_aabb", rotateTouristInteractAabbJson(
                            params.get("tourist_interact_aabb").getAsJsonArray(), rotationSteps));
                }
                // Rotate door_offset
                if (params.containsKey("door_offset")) {
                    JsonArray arr = params.get("door_offset").getAsJsonArray();
                    if (arr.size() == 3) {
                        BlockOffset off = new BlockOffset(
                                arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt());
                        BlockOffset rotated = BuildingRotation.rotateOffset(off, rotationSteps);
                        params.put("door_offset", offsetToJson(rotated));
                    }
                }
            }
        } else {
            blueprintId = "build:" + buildingTypeId;
            params.put("x", new JsonPrimitive(pos.getX()));
            params.put("y", new JsonPrimitive(pos.getY()));
            params.put("z", new JsonPrimitive(pos.getZ()));
        }

        return new WorkItem(blueprintId, params, priority);
    }

    private static JsonElement resolveField(BuildingConfig config, String fieldName) {
        return switch (fieldName) {
            case "id" -> new JsonPrimitive(config.id());
            case "display_name" -> new JsonPrimitive(config.displayName());
            case "category" -> new JsonPrimitive(config.category());
            case "pattern" -> patternToJson(config);
            case "block_mapping" -> blockMappingToJson(config);
            case "block_nbt" -> blockNbtToJson(config);
            case "comfort" -> new JsonPrimitive(config.comfort());
            case "magic" -> new JsonPrimitive(config.magic());
            case "wonder" -> new JsonPrimitive(config.wonder());
            case "boundary" -> boundaryToJson(config);
            case "tourist_interact_aabb" -> touristInteractAabbToJson(config);
            case "door_offset" -> config.doorOffset() != null
                    ? offsetToJson(config.doorOffset()) : new JsonArray();
            default -> null;
        };
    }

    /**
     * Compute deduped material_list + material_counts from pattern → block_mapping.
     * Skips air blocks. Returns a record with list (unique types) and counts (type→total).
     */
    private static MaterialData computeMaterialData(BuildingConfig config) {
        var counts = new java.util.LinkedHashMap<String, Integer>();
        var elementApi = WandscapeApis.getElementApi();
        for (var offset : config.pattern()) {
            String blockId = config.blockMapping().get(offset.toKey());
            if (blockId == null || "minecraft:air".equals(blockId)) continue;
            // Strip blockstate properties (e.g. "[facing=south]") before checking
            // element mappings — mappings are registered for bare block IDs only.
            String pureId = blockId.replaceAll("\\[.*?\\]", "").trim();
            // Blocks without element mappings are considered "free" materials
            // and should not be requested from the warehouse.
            if (!elementApi.hasElementMapping(pureId)) continue;
            counts.merge(pureId, 1, Integer::sum);
        }
        if (counts.isEmpty()) return null;
        JsonArray list = new JsonArray();
        JsonObject map = new JsonObject();
        for (var entry : counts.entrySet()) {
            list.add(new JsonPrimitive(entry.getKey()));
            map.addProperty(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return new MaterialData(list, map);
    }

    private record MaterialData(JsonArray list, JsonObject counts) {}

    /** Pattern offsets sorted Y→X→Z so the building rises from bottom to top. */
    private static JsonElement patternToJson(BuildingConfig config) {
        var sorted = new ArrayList<>(config.pattern());
        sorted.sort(Comparator.comparingInt(BlockOffset::y)
                .thenComparingInt(BlockOffset::x)
                .thenComparingInt(BlockOffset::z));
        JsonArray arr = new JsonArray();
        for (var offset : sorted) {
            arr.add(offsetToJson(offset));
        }
        return arr;
    }

    private static JsonElement blockMappingToJson(BuildingConfig config) {
        JsonObject obj = new JsonObject();
        for (var entry : config.blockMapping().entrySet()) {
            obj.addProperty(entry.getKey(), entry.getValue());
        }
        return obj;
    }

    private static JsonElement blockNbtToJson(BuildingConfig config) {
        Map<String, String> nbt = config.blockNbt();
        if (nbt == null) return new JsonObject();
        JsonObject obj = new JsonObject();
        for (var entry : nbt.entrySet()) {
            obj.addProperty(entry.getKey(), entry.getValue());
        }
        return obj;
    }

    private static JsonElement boundaryToJson(BuildingConfig config) {
        var b = config.boundary();
        JsonObject obj = new JsonObject();
        obj.add("min", offsetToJson(b.min()));
        obj.add("max", offsetToJson(b.max()));
        return obj;
    }

    /**
     * Compute offsets to clear: ALL positions within the AABB boundary.
     * Anchor is now a vanilla block — no special skip needed.
     */
    static JsonElement computeClearOffsets(BuildingConfig config) {
        JsonArray arr = new JsonArray();
        for (BlockOffset off : config.boundary().allPositions()) {
            arr.add(offsetToJson(off));
        }
        return arr;
    }

    /**
     * Compute clear offsets but exclude positions that are occupied by other
     * buildings' pattern blocks (or AABB for legacy buildings without pattern).
     * Prevents the clear step from damaging nearby structures.
     *
     * @param config         the building being placed
     * @param sd             the building saved data (for querying other buildings)
     * @param anchor         world anchor of the building being placed
     * @param selfBuildingId UUID of the building being placed (to exclude from checks)
     * @return a JSON array of offset positions safe to clear
     */
    static JsonElement computeClearOffsetsFiltered(BuildingConfig config, BuildingSavedData sd,
                                                    BlockPos anchor, UUID selfBuildingId) {
        JsonArray arr = computeClearOffsets(config).getAsJsonArray();
        JsonArray filtered = new JsonArray();
        for (int i = 0; i < arr.size(); i++) {
            JsonArray posArr = arr.get(i).getAsJsonArray();
            BlockPos worldPos = anchor.offset(
                    posArr.get(0).getAsInt(),
                    posArr.get(1).getAsInt(),
                    posArr.get(2).getAsInt());
            if (!sd.isPositionOccupiedByOtherBuilding(worldPos, selfBuildingId)) {
                filtered.add(arr.get(i));
            }
        }
        return filtered;
    }

    private static JsonArray offsetToJson(BlockOffset off) {
        JsonArray arr = new JsonArray();
        arr.add(off.x());
        arr.add(off.y());
        arr.add(off.z());
        return arr;
    }

    private static JsonArray posToJsonArray(BlockPos pos) {
        JsonArray arr = new JsonArray();
        arr.add(pos.getX());
        arr.add(pos.getY());
        arr.add(pos.getZ());
        return arr;
    }

    // ──────────────── Warehouse seed ────────────────

    /** Amount of each element seeded into the first colony warehouse. */
    private static final long INITIAL_ELEMENT_COUNT = 2000;

    /**
     * Seed the colony warehouse once per colony, on its first building registration.
     * Items start empty; the colony receives 2000 of every element type.
     * Idempotent across restarts — the seeded marker persists in ColonyItemBank.
     */
    private static void seedInitialElementsIfNeeded(UUID colonyId) {
        Level level = getServerLevel();
        if (level == null) return;
        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) {
            Log.warn(TAG, "[Enqueue] seedInitialElements: ColonyItemBank not available");
            return;
        }
        if (bank.isSeeded(colonyId)) return;

        for (ElementType element : ElementType.values()) {
            bank.addElement(colonyId, element, INITIAL_ELEMENT_COUNT);
        }
        bank.markSeeded(colonyId);

        Log.info(TAG, "[Enqueue] seeded warehouse: {} elements x{} (colony={})",
                ElementType.values().length, INITIAL_ELEMENT_COUNT,
                colonyId.toString().substring(0, 8));
    }

    private static Level getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }

    // ──────────────── Rotation helpers ────────────────

    /** Rotate a JSON array of [x,y,z] offset arrays by {@code steps} 90° CCW. */
    private static JsonArray rotatePatternJson(JsonArray pattern, int steps) {
        JsonArray result = new JsonArray();
        for (int i = 0; i < pattern.size(); i++) {
            JsonArray pos = pattern.get(i).getAsJsonArray();
            BlockOffset off = new BlockOffset(pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt());
            BlockOffset rotated = BuildingRotation.rotateOffset(off, steps);
            JsonArray newPos = new JsonArray();
            newPos.add(rotated.x());
            newPos.add(rotated.y());
            newPos.add(rotated.z());
            result.add(newPos);
        }
        return result;
    }

    /** Rotate a JSON block_mapping object (keys and block state values). */
    private static JsonObject rotateBlockMappingJson(JsonObject mapping, int steps) {
        JsonObject result = new JsonObject();
        for (var entry : mapping.entrySet()) {
            BlockOffset off = parseKey(entry.getKey());
            if (off == null) continue;
            BlockOffset rotatedOff = BuildingRotation.rotateOffset(off, steps);
            String rotatedBlock = BuildingRotation.rotateBlockStateString(entry.getValue().getAsString(), steps);
            result.addProperty(rotatedOff.toKey(), rotatedBlock);
        }
        return result;
    }

    /** Rotate block_nbt keys (offset string → rotated offset string). Values are opaque base64. */
    private static JsonObject rotateBlockNbtJson(JsonObject nbt, int steps) {
        JsonObject result = new JsonObject();
        for (var entry : nbt.entrySet()) {
            BlockOffset off = parseKey(entry.getKey());
            if (off == null) continue;
            BlockOffset rotatedOff = BuildingRotation.rotateOffset(off, steps);
            result.addProperty(rotatedOff.toKey(), entry.getValue().getAsString());
        }
        return result;
    }

    /** Rotate a JSON array of [x,y,z] clear offsets. */
    private static JsonArray rotateOffsetsJson(JsonArray offsets, int steps) {
        JsonArray result = new JsonArray();
        for (int i = 0; i < offsets.size(); i++) {
            JsonArray pos = offsets.get(i).getAsJsonArray();
            BlockOffset off = new BlockOffset(pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt());
            BlockOffset rotated = BuildingRotation.rotateOffset(off, steps);
            result.add(offsetToJson(rotated));
        }
        return result;
    }

    /** Serialize tourist_interact_aabb list to a JSON array of boundary objects. */
    private static JsonElement touristInteractAabbToJson(BuildingConfig config) {
        JsonArray arr = new JsonArray();
        for (BuildingConfig.BoundaryBox zone : config.touristInteractAabb()) {
            JsonObject obj = new JsonObject();
            obj.add("min", offsetToJson(zone.min()));
            obj.add("max", offsetToJson(zone.max()));
            arr.add(obj);
        }
        return arr;
    }

    /** Rotate a JSON array of tourist interact AABB boundary objects. */
    private static JsonArray rotateTouristInteractAabbJson(JsonArray zones, int steps) {
        JsonArray result = new JsonArray();
        for (int i = 0; i < zones.size(); i++) {
            JsonObject zone = zones.get(i).getAsJsonObject();
            JsonArray minArr = zone.getAsJsonArray("min");
            JsonArray maxArr = zone.getAsJsonArray("max");
            BlockOffset min = new BlockOffset(
                    minArr.get(0).getAsInt(), minArr.get(1).getAsInt(), minArr.get(2).getAsInt());
            BlockOffset max = new BlockOffset(
                    maxArr.get(0).getAsInt(), maxArr.get(1).getAsInt(), maxArr.get(2).getAsInt());
            BuildingConfig.BoundaryBox rotated = BuildingRotation.rotateBoundary(
                    new BuildingConfig.BoundaryBox(min, max), steps);
            JsonObject rotatedZone = new JsonObject();
            rotatedZone.add("min", offsetToJson(rotated.min()));
            rotatedZone.add("max", offsetToJson(rotated.max()));
            result.add(rotatedZone);
        }
        return result;
    }

    /** Parse a "x,y,z" key string into a BlockOffset. */
    private static BlockOffset parseKey(String key) {
        String[] parts = key.split(",");
        if (parts.length != 3) return null;
        try {
            return new BlockOffset(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
