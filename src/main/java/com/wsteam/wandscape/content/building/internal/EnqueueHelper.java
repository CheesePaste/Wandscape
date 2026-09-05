package com.wsteam.wandscape.content.building.internal;
import com.wsteam.wandscape.content.task.component.Position;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.content.building.data.BlockOffset;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.colony.ColonyApiImpl;
import com.wsteam.wandscape.content.building.projection.BuildingRotation;
import com.wsteam.wandscape.api.BuildingApi;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.content.building.data.WorkItem;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.*;

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
        return registerIfAbsent(pos, config, buildingTypeId, rotationSteps, null);
    }

    /**
     * Register a building with optional rotation and explicit owner colony.
     *
     * <p>归属跟「放置者」：{@code ownerColony} 非空时直接归属该镇（与空间最近原点无关，近邻小镇也
     * 各归各的）；为空时按旧行为就近归属（非政府），政府建筑为空则暂不归属、留待建镇命名。
     */
    @Nullable
    public static BuildingState registerIfAbsent(BlockPos pos, BuildingConfig config, String buildingTypeId, int rotationSteps,
                                                 @Nullable UUID ownerColony) {
        try {
            BuildingApiImpl api = BuildingApiImpl.get();

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
                    config.wonder()
            );
            state.setRotationSteps(rotationSteps);
            api.registerBuilding(state);

            // 归属：优先「放置者的小镇」；无放置者时政府建筑不就近归属（建镇/命名路径），
            // 非政府退回按空间最近归属（旧行为：fill/scanner 等无玩家语境）。
            if (ownerColony != null) {
                ColonyApiImpl.get().assignToColony(state, ownerColony);
            } else if (!"government".equals(config.category())) {
                ColonyApiImpl.get().assignColonyIfPossible(state);
            }

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
     * Construction only places the building's own pattern blocks — no boundary
     * volume clearing (so overlapping interiors are never wiped).
     */
    public static WorkItem buildWorkItem(BuildingConfig config, BlockPos pos,
                                          String buildingTypeId, int priority) {
        return buildWorkItem(config, pos, buildingTypeId, priority, null, null);
    }

    /**
     * Build a WorkItem, tagging {@code building_id} when the owning building is
     * known so construction-complete events resolve by id (bounding boxes may
     * overlap, so anchors are no longer unique).
     */
    public static WorkItem buildWorkItem(BuildingConfig config, BlockPos pos,
                                          String buildingTypeId, int priority,
                                          @Nullable BuildingSavedData sd,
                                          @Nullable UUID buildingId) {
        return buildWorkItem(config, pos, buildingTypeId, priority, sd, buildingId, 0);
    }

    /**
     * Build a WorkItem with rotation support. When {@code rotationSteps > 0},
     * the pattern offsets and block_mapping keys and values are all rotated
     * 90° CCW around the Y axis by the specified number of steps.
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
     * Box clearing defaults to on ({@code clearBox = true}).
     */
    public static WorkItem buildWorkItem(BuildingConfig config, BlockPos pos,
                                          String buildingTypeId, int priority,
                                          @Nullable BuildingSavedData sd,
                                          @Nullable UUID buildingId,
                                          int rotationSteps,
                                          boolean skipMaterials) {
        return buildWorkItem(config, pos, buildingTypeId, priority, sd, buildingId,
                rotationSteps, skipMaterials, true);
    }

    /**
     * Build a WorkItem with rotation support, optional material skip, and optional
     * boundary clearing. When {@code clearBox} is true the build enqueue expands the
     * placed voxel set to the whole rotated boundary box — every non-pattern voxel
     * becomes an air mapping — so {@code build:clear_and_build} single-passes the box
     * exactly like the pre-overlap build used to (whole box cleared, then walls placed).
     * When false the params stay pattern-only (pure placement, overlapping interiors
     * untouched). Repair/demolish assemble their own WorkItems and never pass this.
     */
    public static WorkItem buildWorkItem(BuildingConfig config, BlockPos pos,
                                          String buildingTypeId, int priority,
                                          @Nullable BuildingSavedData sd,
                                          @Nullable UUID buildingId,
                                          int rotationSteps,
                                          boolean skipMaterials,
                                          boolean clearBox) {
        Map<String, JsonElement> params = new HashMap<>();

        params.put("anchor", posToJsonArray(pos));
        // Tag the owning building on construction/repair work so completion events
        // resolve by id — anchors are no longer unique once bounding boxes may overlap.
        if (buildingId != null) {
            params.put("building_id", new JsonPrimitive(buildingId.toString()));
        }

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
            // Auto-add entities if not provided by bind (older building JSONs) so the
            // blueprint's for_each $entities always has a value — empty means no decorations.
            if (!params.containsKey("entities")) {
                params.put("entities", entitiesToJson(config));
            }
            // Box clearing: when the BUILD panel toggle is on (clearBox, default),
            // the non-pattern voxels inside the boundary are expanded into the placed
            // set as air mappings — clear_and_build then single-passes the whole box,
            // reproducing the pre-overlap "clear the box, then build" outcome.
            // When off, offsets/blocks stay pattern-only (pure placement).
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
                // Rotate blocks map: rotate the palette once (M blockstate rotations
                // instead of N), then rebuild from pattern-order offsets + rotated palette + indices.
                // Must pair against config.pattern() (NOT the sorted $offsets array): blockIndices
                // is parallel to pattern order, so pairing sorted offsets would scramble blocks.
                if (params.containsKey("blocks")) {
                    var rotatedPalette = BuildingRotation.rotatePalette(config.palette(), rotationSteps);
                    params.put("blocks", blocksFromPalette(
                            config.pattern(), rotatedPalette, config.blockIndices(), rotationSteps));
                }
                // Rotate block_nbt (keys only — values are opaque base64 strings)
                if (params.containsKey("blocks_nbt")) {
                    params.put("blocks_nbt", rotateBlockNbtJson(
                            params.get("blocks_nbt").getAsJsonObject(), rotationSteps));
                }
                // Rotate decoration entities (offsets + facing strings, NBT opaque)
                if (params.containsKey("entities")) {
                    params.put("entities", rotateEntitiesJson(
                            params.get("entities").getAsJsonArray(), rotationSteps));
                }
                // Rotate door_offsets (list of [x,y,z])
                if (params.containsKey("door_offsets")) {
                    JsonArray doorList = params.get("door_offsets").getAsJsonArray();
                    JsonArray rotatedDoors = new JsonArray();
                    for (JsonElement el : doorList) {
                        JsonArray arr = el.getAsJsonArray();
                        if (arr.size() == 3) {
                            BlockOffset off = new BlockOffset(
                                    arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt());
                            rotatedDoors.add(offsetToJson(BuildingRotation.rotateOffset(off, rotationSteps)));
                        }
                    }
                    params.put("door_offsets", rotatedDoors);
                }
            }
        } else {
            blueprintId = "build:" + buildingTypeId;
            params.put("x", new JsonPrimitive(pos.getX()));
            params.put("y", new JsonPrimitive(pos.getY()));
            params.put("z", new JsonPrimitive(pos.getZ()));
        }

        if (clearBox) {
            fillBoundaryAsAir(params, config, rotationSteps);
        }

        return new WorkItem(blueprintId, params, priority);
    }

    /**
     * Expand the build params' placed set to the whole rotated boundary box when box
     * clearing is enabled: every boundary voxel that has no pattern mapping gets an
     * {@code "minecraft:air"} mapping (free, no material) appended to {@code offsets}
     * ahead of the pattern voxels, so {@code clear_and_build} single-passes the box the
     * way the pre-overlap "clear then build" used to. Voxels belonging to the pattern
     * (already present in {@code offsets}) are left untouched. Air coords are rotated
     * with the same {@link BuildingRotation#rotateOffset} used for pattern offsets.
     * No-op when the building has no boundary or its blueprint has no offsets/blocks.
     */
    private static void fillBoundaryAsAir(Map<String, JsonElement> params, BuildingConfig config, int rotationSteps) {
        BuildingConfig.BoundaryBox boundary = config.boundary();
        if (boundary == null) return;
        JsonElement offsetsEl = params.get("offsets");
        JsonElement blocksEl = params.get("blocks");
        if (!(offsetsEl instanceof JsonArray) || !(blocksEl instanceof JsonObject)) return;
        JsonArray offsets = offsetsEl.getAsJsonArray();
        JsonObject blocks = blocksEl.getAsJsonObject();

        int steps = rotationSteps & 3;
        Set<String> existing = new HashSet<>(offsets.size() * 2);
        for (JsonElement el : offsets) {
            JsonArray arr = el.getAsJsonArray();
            existing.add(arr.get(0).getAsInt() + "," + arr.get(1).getAsInt() + "," + arr.get(2).getAsInt());
        }

        JsonArray airs = new JsonArray();
        for (BlockOffset off : boundary.allPositions()) {
            BlockOffset r = BuildingRotation.rotateOffset(off, steps);
            String key = r.x() + "," + r.y() + "," + r.z();
            if (existing.contains(key)) continue;
            existing.add(key);
            blocks.addProperty(key, "minecraft:air");
            airs.add(offsetToJson(r));
        }

        if (!airs.isEmpty()) {
            JsonArray merged = new JsonArray();
            for (JsonElement el : airs) merged.add(el);
            for (JsonElement el : offsets) merged.add(el);
            params.put("offsets", merged);
        }
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
            case "entities" -> entitiesToJson(config);
            case "door_offsets" -> {
                JsonArray arr = new JsonArray();
                if (config.doorOffsets() != null) {
                    for (BlockOffset off : config.doorOffsets()) {
                        arr.add(offsetToJson(off));
                    }
                }
                yield arr;
            }
            default -> null;
        };
    }

    /**
     * Compute deduped material counts (pure block id → total) from pattern → block_mapping.
     * Skips air blocks. Blocks without an element mapping are "free" materials and are
     * skipped (not requested from the warehouse); blockstate properties are stripped
     * before counting so mappings registered for bare block IDs match.
     *
     * <p>Public for the construction-site panel, which reuses the same demand口径.
     * Returns an empty map when the building needs no warehouse-supplied materials.
     */
    public static Map<String, Integer> computeMaterialCounts(BuildingConfig config) {
        var counts = new java.util.LinkedHashMap<String, Integer>();
        var elementApi = WandscapeApis.getElementApi();
        for (int i = 0; i < config.pattern().size(); i++) {
            String blockId = config.blockIdAt(i);
            if ("minecraft:air".equals(blockId)) continue;
            // Strip blockstate properties (e.g. "[facing=south]") before checking
            // element mappings — mappings are registered for bare block IDs only.
            String pureId = blockId.replaceAll("\\[.*?\\]", "").trim();
            if (!elementApi.hasElementMapping(pureId)) continue;
            counts.merge(pureId, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * First block in the pattern whose element mapping is explicitly disabled, or null
     * when the building uses no disabled blocks. Callers refuse the build on a non-null
     * result — a disabled block must not be placed as a free material (the "ban → free"
     * inversion). Mirrors the bare-id iteration of {@link #computeMaterialCounts}.
     */
    @Nullable
    public static String findDisabledBlock(BuildingConfig config) {
        var elementApi = WandscapeApis.getElementApi();
        for (int i = 0; i < config.pattern().size(); i++) {
            String blockId = config.blockIdAt(i);
            if ("minecraft:air".equals(blockId)) continue;
            String pureId = blockId.replaceAll("\\[.*?\\]", "").trim();
            if (elementApi.isDisabled(pureId)) return pureId;
        }
        return null;
    }

    /**
     * Compute deduped material_list + material_counts from pattern → block_mapping.
     * Returns a record with list (unique types) and counts (type→total).
     */
    private static MaterialData computeMaterialData(BuildingConfig config) {
        var counts = computeMaterialCounts(config);
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

    /** Serialize decoration entities to a JSON array of {offset, type, facing, nbt}. */
    static JsonArray entitiesToJson(BuildingConfig config) {
        JsonArray arr = new JsonArray();
        for (BuildingConfig.DecorationEntity ent : config.entities()) {
            JsonObject obj = new JsonObject();
            obj.add("offset", offsetToJson(ent.offset()));
            obj.addProperty("type", ent.type());
            obj.addProperty("facing", ent.facing());
            if (ent.nbtBase64() != null) {
                obj.addProperty("nbt", ent.nbtBase64());
            }
            arr.add(obj);
        }
        return arr;
    }

    private static JsonElement boundaryToJson(BuildingConfig config) {
        var b = config.boundary();
        JsonObject obj = new JsonObject();
        obj.add("min", offsetToJson(b.min()));
        obj.add("max", offsetToJson(b.max()));
        return obj;
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

    /**
     * Seed the colony warehouse once per colony, on its first building registration.
     * Items start empty; the colony receives {@link Config#INITIAL_ELEMENT_COUNT} of every element type.
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

        long initialCount = Config.INITIAL_ELEMENT_COUNT.get();
        for (ElementType element : ElementType.values()) {
            bank.addElement(colonyId, element, initialCount);
        }
        bank.markSeeded(colonyId);

        Log.info(TAG, "[Enqueue] seeded warehouse: {} elements x{} (colony={})",
                ElementType.values().length, initialCount,
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

    /**
     * Rebuild the blocks map (rotated offset→blockstate) from pattern-order offsets,
     * a pre-rotated palette and block indices. {@code blockIndices} is parallel to
     * {@code pattern}, so index alignment is preserved regardless of any other
     * ordering the offsets array may be in.
     */
    static JsonObject blocksFromPalette(List<BlockOffset> pattern,
                                        List<String> rotatedPalette,
                                        List<Integer> blockIndices,
                                        int steps) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < pattern.size(); i++) {
            BlockOffset rotated = BuildingRotation.rotateOffset(pattern.get(i), steps);
            result.addProperty(rotated.toKey(), rotatedPalette.get(blockIndices.get(i)));
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

    /** Rotate a JSON array of decoration entity objects: offset + facing rotate, NBT stays opaque. */
    static JsonArray rotateEntitiesJson(JsonArray entities, int steps) {
        JsonArray result = new JsonArray();
        for (int i = 0; i < entities.size(); i++) {
            JsonObject ent = entities.get(i).getAsJsonObject();
            JsonArray offArr = ent.getAsJsonArray("offset");
            BlockOffset off = new BlockOffset(
                    offArr.get(0).getAsInt(), offArr.get(1).getAsInt(), offArr.get(2).getAsInt());
            BlockOffset rotated = BuildingRotation.rotateOffset(off, steps);
            JsonObject rotatedEnt = new JsonObject();
            rotatedEnt.add("offset", offsetToJson(rotated));
            rotatedEnt.addProperty("type", ent.get("type").getAsString());
            rotatedEnt.addProperty("facing", BuildingRotation.rotateFacing(
                    ent.get("facing").getAsString(), steps));
            if (ent.has("nbt")) {
                rotatedEnt.addProperty("nbt", ent.get("nbt").getAsString());
            }
            result.add(rotatedEnt);
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
