package com.wsteam.wandscape.building.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.engine.ColonyApiImpl;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.data.ItemKey;
import com.wsteam.wandscape.shared.data.WorkItem;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
import com.wsteam.wandscape.warehouse.ColonyItemBank;
import com.wsteam.wandscape.wand.internal.WandPresetLoader.WandPreset;

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

    /** Guard: seed warehouse only once per session. */
    private static boolean warehouseSeeded = false;

    private EnqueueHelper() {}

    /**
     * Register a building with {@link BuildingApi} if it hasn't been registered yet.
     * On the first-ever registration, seeds the colony warehouse with starter items.
     *
     * @param pos            the anchor position
     * @param config         the building config
     * @param buildingTypeId building type identifier
     * @return true if newly registered, false if already registered
     */
    public static boolean registerIfAbsent(BlockPos pos, BuildingConfig config, String buildingTypeId) {
        try {
            BuildingApi api = WandscapeApis.getBuildingApi();
            if (api.getBuildingAt(pos) != null) {
                return false;
            }

            UUID buildingId = UUID.randomUUID();
            BoundingBox bounds;
            if (config.boundary() != null) {
                bounds = BuildingSavedData.computeWorldBox(pos, config.boundary());
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
            api.registerBuilding(state);

            // Assign colony if one exists nearby
            ColonyApiImpl.get().assignColonyIfPossible(state);

            // First building registered → seed warehouse so it has materials to build itself
            if (!warehouseSeeded && state.getColonyId() != null) {
                boolean ok = seedBuilderWand(state.getColonyId());
                if (ok) {
                    warehouseSeeded = true;
                } else {
                    Log.warn(TAG, "[Enqueue] warehouse seed failed — will retry on next registration");
                }
            }

            return true;
        } catch (IllegalStateException e) {
            return false;
        } catch (BuildingOverlapException e) {
            return false;
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
            if (config.boundary() != null) {
                if (sd != null && buildingId != null) {
                    params.put("clear_offsets", computeClearOffsetsFiltered(config, sd, pos, buildingId));
                } else {
                    params.put("clear_offsets", computeClearOffsets(config));
                }
            }
            // material_list + material_counts: auto-computed from pattern → block_mapping
            if (!params.containsKey("material_list")) {
                var materialData = computeMaterialData(config);
                if (materialData != null) {
                    params.put("material_list", materialData.list());
                    params.put("material_counts", materialData.counts());
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
            case "comfort" -> new JsonPrimitive(config.comfort());
            case "magic" -> new JsonPrimitive(config.magic());
            case "wonder" -> new JsonPrimitive(config.wonder());
            case "boundary" -> boundaryToJson(config);
            default -> null;
        };
    }

    /**
     * Compute deduped material_list + material_counts from pattern → block_mapping.
     * Skips air blocks. Returns a record with list (unique types) and counts (type→total).
     */
    private static MaterialData computeMaterialData(BuildingConfig config) {
        var counts = new java.util.LinkedHashMap<String, Integer>();
        for (var offset : config.pattern()) {
            String blockId = config.blockMapping().get(offset.toKey());
            if (blockId == null || "minecraft:air".equals(blockId)) continue;
            counts.merge(blockId, 1, Integer::sum);
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

    private static JsonElement blockMappingToJson(BuildingConfig config) {
        JsonObject obj = new JsonObject();
        for (var entry : config.blockMapping().entrySet()) {
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

    /**
     * Seed the colony warehouse on first building registration.
     * 1x builder_wand + 64 of every non-air block used by any building config.
     */
    private static boolean seedBuilderWand(UUID colonyId) {
        if (colonyId == null) colonyId = new UUID(0, 0);
        Level level = getServerLevel();
        if (level == null) return false;
        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) {
            Log.warn(TAG, "[Enqueue] seedBuilderWand: ColonyItemBank not available");
            return false;
        }

        // 1x builder_wand
        WandPreset preset = Wandscape.WAND_PRESET_LOADER.getPreset("builder_wand");
        if (preset != null) {
            ItemKey wandKey = ItemKey.of("wandscape:wand", preset.nbt().copy());
            if (bank.count(colonyId, wandKey) == 0) {
                bank.add(colonyId, wandKey, 1);
                Log.info(TAG, "[Enqueue] seeded builder_wand (colony={})",
                        colonyId.toString().substring(0, 8));
            }
        }

        // 64x of every unique non-air block across ALL building configs
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (BuildingConfig cfg : BuildingConfigLoader.getInstance().getAll().values()) {
            for (String blockId : cfg.blockMapping().values()) {
                if ("minecraft:air".equals(blockId)) continue;
                seen.add(blockId);
            }
        }
        for (String blockId : seen) {
            bank.add(colonyId, ItemKey.of(blockId, null), 64);
        }

        Log.info(TAG, "[Enqueue] seeded warehouse: builder_wand + 64x{} unique materials (colony={})",
                seen.size(), colonyId.toString().substring(0, 8));
        return true;
    }

    private static Level getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }
}
