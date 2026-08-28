package com.wsteam.wandscape.building.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.data.WorkItem;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Manual repair entry point: {@link #triggerRepair} scans a building for missing
 * pattern blocks and enqueues a {@code build:place_structure} repair task.
 * 建筑被破坏不再导致停摆（已移除 1/3 阈值与自动修复），缺失方块仅在玩家主动点「修复」时补齐；
 * 受损建筑照常运转、照常贡献。
 */
public final class BuildingRepairHandler {
    private static final String TAG = "BuildingRepairHandler";
    // 49 = below the PENDING_APPROVAL gate (>= 50) but above node supply (15).
    // addFirst in the building queue guarantees repair tasks are dequeued first.
    static final int REPAIR_PRIORITY = 49;

    private BuildingRepairHandler() {}

    /**
     * Enqueue a partial repair targeting specific offsets.
     * Used by {@link #triggerRepair} to fix missing pattern blocks.
     */
    static void enqueueRepairForOffsets(BuildingState state, BuildingConfig config,
                                        List<BlockOffset> damagedOffsets) {
        int rotationSteps = state.getRotationSteps();
        java.util.Map<String, String> blockMapping = rotationSteps != 0
                ? com.wsteam.wandscape.projection.BuildingRotation.rotateBlockMapping(
                        config.blockMapping(), rotationSteps)
                : config.blockMapping();
        java.util.Map<String, String> blockNbt = rotationSteps != 0
                ? com.wsteam.wandscape.projection.BuildingRotation.rotateBlockNbt(
                        config.blockNbt(), rotationSteps)
                : config.blockNbt();

        JsonArray offsets = new JsonArray();
        JsonObject blocks = new JsonObject();
        JsonObject blocksNbt = new JsonObject();

        for (BlockOffset offset : damagedOffsets) {
            String key = offset.toKey();
            String blockSpec = blockMapping.get(key);
            if (blockSpec == null) continue;

            JsonArray off = new JsonArray();
            off.add(offset.x());
            off.add(offset.y());
            off.add(offset.z());
            offsets.add(off);
            blocks.addProperty(key, blockSpec);

            // Carry over the block's NBT (e.g. container contents) so the
            // repaired block matches the original, not just the block state.
            if (blockNbt != null) {
                String nbt = blockNbt.get(key);
                if (nbt != null) {
                    blocksNbt.addProperty(key, nbt);
                }
            }
        }

        if (offsets.isEmpty()) return;

        enqueueRepairWorkItem(state, config, offsets, blocks, blocksNbt, rotationSteps);
    }

    private static void enqueueRepairWorkItem(BuildingState state, BuildingConfig config,
                                               JsonArray offsets, JsonObject blocks,
                                               JsonObject blocksNbt, int rotationSteps) {
        Map<String, JsonElement> params = new HashMap<>();
        params.put("anchor", posToJsonArray(state.getAnchor()));
        params.put("offsets", offsets);
        params.put("blocks", blocks);
        params.put("blocks_nbt", blocksNbt);
        params.put("name", new JsonPrimitive(config.displayName()));

        // Restore decorative entities too — replay the full list (idempotent:
        // spawnDecoration clears the cell before spawning, so undamaged ones are
        // re-confirmed without loss and lost ones come back). Skipped when none.
        if (!config.entities().isEmpty()) {
            JsonArray entities = EnqueueHelper.entitiesToJson(config);
            if (rotationSteps != 0) {
                entities = EnqueueHelper.rotateEntitiesJson(entities, rotationSteps);
            }
            params.put("entities", entities);
        }

        // Compute material_list + material_counts from damaged blocks so the
        // build:place_structure blueprint can request resources from the warehouse.
        var materialData = computeRepairMaterialData(blocks);
        params.put("material_list", materialData != null ? materialData.list : new JsonArray());
        params.put("material_counts", materialData != null ? materialData.counts : new JsonObject());

        WorkItem repairWork = new WorkItem("build:place_structure", params, REPAIR_PRIORITY);
        state.getTaskQueue().addFirst(repairWork);
    }

    /** Compute deduped material_list + material_counts from a block mapping. */
    private static RepairMaterialData computeRepairMaterialData(JsonObject blocks) {
        var counts = new java.util.LinkedHashMap<String, Integer>();
        var elementApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getElementApi();
        for (var entry : blocks.entrySet()) {
            String blockId = entry.getValue().getAsString();
            if (blockId == null || "minecraft:air".equals(blockId)) continue;
            // Strip blockstate properties; element mappings use bare IDs only.
            String pureId = blockId.replaceAll("\\[.*?\\]", "").trim();
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
        return new RepairMaterialData(list, map);
    }

    private record RepairMaterialData(JsonArray list, JsonObject counts) {}

    /**
     * Manually trigger a repair scan and enqueue repair work for a building with
     * any missing pattern blocks.
     * Called from the V-panel Repair button.
     *
     * @return true if a repair was enqueued, false if the building has no damage
     *         or was not found
     */
    public static boolean triggerRepair(Level level, UUID buildingId) {
        BuildingSavedData data = BuildingSavedData.get(level);
        if (data == null) return false;

        BuildingState state = data.getBuilding(buildingId);
        if (state == null) return false;

        BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        if (config == null) return false;

        List<BlockOffset> damaged = BuildCompleteListener.findDamagedBlocks(level, state.getAnchor(), config, state.getRotationSteps());
        if (damaged.isEmpty()) {
            Log.info(TAG, "[Building] Repair triggered but no damage found for {} at {}",
                    state.getBuildingTypeId(), state.getAnchor());
            return false;
        }

        enqueueRepairForOffsets(state, config, damaged);
        data.setDirty();
        Log.info(TAG, "[Building] Repair triggered manually for {} at {} — {} blocks damaged, repair enqueued",
                state.getBuildingTypeId(), state.getAnchor(), damaged.size());
        return true;
    }

    private static JsonArray posToJsonArray(BlockPos pos) {
        JsonArray arr = new JsonArray();
        arr.add(pos.getX());
        arr.add(pos.getY());
        arr.add(pos.getZ());
        return arr;
    }
}
