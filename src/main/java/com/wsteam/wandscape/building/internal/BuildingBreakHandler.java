package com.wsteam.wandscape.building.internal;

import java.util.ArrayList;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Listens for block break and explosion events.
 * Marks affected buildings as {@code structureIntact = false} and enqueues a
 * partial repair WorkItem targeting only the damaged positions.
 */
public final class BuildingBreakHandler {
    private static final String TAG = "BuildingBreakHandler";
    // 49 = below the PENDING_APPROVAL gate (>= 50) but above node supply (15).
    // addFirst in the building queue guarantees repair tasks are dequeued first.
    static final int REPAIR_PRIORITY = 49;

    private BuildingBreakHandler() {}

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        Level level = event.getPlayer().level();
        if (level.isClientSide()) return;

        BuildingSavedData data = BuildingSavedData.get(level);
        BlockPos pos = event.getPos();
        UUID buildingId = data.getBuildingIdAt(pos);
        if (buildingId == null) return;

        BuildingState state = data.getBuilding(buildingId);
        if (state == null) return;

        BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        if (config == null) return;

        // Re-verify entire building after break
        List<BlockOffset> damaged = BuildCompleteListener.findDamagedBlocks(level, state.getAnchor(), config);
        if (damaged.isEmpty()) return; // broken block wasn't part of pattern

        boolean broken = BuildCompleteListener.isBroken(damaged.size(), config.pattern().size());
        if (!broken) {
            // Minor damage — still operational, no repair yet
            if (!state.isStructureIntact()) {
                // Building was already broken, ignore (no change)
            }
            Log.info(TAG, "[Building] {} minor damage: {}/{} blocks (< 1/3), still operational",
                    state.getBuildingTypeId(), damaged.size(), config.pattern().size());
            return;
        }

        if (!state.isStructureIntact()) return; // already broken

        state.setStructureIntact(false);
        data.setDirty();

        // Remove contribution
        UUID colonyId = state.getColonyId();
        if (colonyId != null) {
            boolean changed = data.removeBuildingContribution(colonyId, state.getBuildingTypeId());
            if (changed) {
                Log.info(TAG, "[Evaluation] Colony {} lost last contribution from {} — evaluation values decreased",
                        colonyId.toString().substring(0, 8), state.getBuildingTypeId());
            }
        }

        // If town_hall destroyed, delete the colony
        com.wsteam.wandscape.shared.api.ColonyApi colonyApi =
                com.wsteam.wandscape.shared.registry.WandscapeApis.getColonyApiSilently();
        if (colonyApi != null) {
            colonyApi.onBuildingDestroyed(state);
        }

        enqueueRepairForOffsets(state, config, damaged);
        Log.info(TAG, "[Building] {} BROKEN at {} — {}/{} blocks damaged (>= 1/3), repair enqueued",
                state.getBuildingTypeId(), state.getAnchor(), damaged.size(), config.pattern().size());
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;

        BuildingSavedData data = BuildingSavedData.get(level);

        Map<UUID, List<BlockPos>> buildingDamage = new HashMap<>();
        for (BlockPos pos : event.getAffectedBlocks()) {
            UUID buildingId = data.getBuildingIdAt(pos);
            if (buildingId == null) continue;
            buildingDamage.computeIfAbsent(buildingId, k -> new ArrayList<>()).add(pos.immutable());
        }

        for (var entry : buildingDamage.entrySet()) {
            BuildingState state = data.getBuilding(entry.getKey());
            if (state == null || !state.isStructureIntact()) continue;

            BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
            if (config == null) continue;

            // Re-verify entire building after explosion
            List<BlockOffset> damaged = BuildCompleteListener.findDamagedBlocks(level, state.getAnchor(), config);
            if (damaged.isEmpty()) continue;

            boolean broken = BuildCompleteListener.isBroken(damaged.size(), config.pattern().size());
            if (!broken) {
                Log.info(TAG, "[Building] {} minor explosion damage: {}/{} blocks (< 1/3), still operational",
                        state.getBuildingTypeId(), damaged.size(), config.pattern().size());
                continue;
            }

            state.setStructureIntact(false);
            data.setDirty();

            // Remove contribution
            UUID colonyId = state.getColonyId();
            if (colonyId != null) {
                boolean changed = data.removeBuildingContribution(colonyId, state.getBuildingTypeId());
                if (changed) {
                    Log.info(TAG, "[Evaluation] Colony {} lost last contribution from {} — evaluation values decreased",
                            colonyId.toString().substring(0, 8), state.getBuildingTypeId());
                }
            }

            enqueueRepairForOffsets(state, config, damaged);
            Log.info(TAG, "[Building] {} BROKEN by explosion at {} — {}/{} blocks damaged (>= 1/3), repair enqueued",
                    state.getBuildingTypeId(), state.getAnchor(), damaged.size(), config.pattern().size());
        }
    }

    /**
     * Enqueue a partial repair targeting specific world positions.
     * Used when the damaged positions are known from the break/explosion event.
     */
    static void enqueueRepairForPositions(BuildingState state, List<BlockPos> damagedWorldPositions) {
        BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        if (config == null) {
            Log.warn(TAG, "[Building] Cannot enqueue repair — config not found: {}", state.getBuildingTypeId());
            return;
        }

        BlockPos anchor = state.getAnchor();
        JsonArray offsets = new JsonArray();
        JsonObject blocks = new JsonObject();

        for (BlockPos worldPos : damagedWorldPositions) {
            int dx = worldPos.getX() - anchor.getX();
            int dy = worldPos.getY() - anchor.getY();
            int dz = worldPos.getZ() - anchor.getZ();
            String key = dx + "," + dy + "," + dz;
            String blockSpec = config.blockMapping().get(key);
            if (blockSpec == null) continue;

            JsonArray off = new JsonArray();
            off.add(dx);
            off.add(dy);
            off.add(dz);
            offsets.add(off);
            blocks.addProperty(key, blockSpec);
        }

        if (offsets.isEmpty()) return;

        enqueueRepairWorkItem(state, config, offsets, blocks);
    }

    /**
     * Enqueue a partial repair targeting specific offsets.
     * Used by {@link BuildCompleteListener} when post-repair verification fails.
     */
    static void enqueueRepairForOffsets(BuildingState state, BuildingConfig config,
                                        List<BlockOffset> damagedOffsets) {
        JsonArray offsets = new JsonArray();
        JsonObject blocks = new JsonObject();

        for (BlockOffset offset : damagedOffsets) {
            String key = offset.toKey();
            String blockSpec = config.blockMapping().get(key);
            if (blockSpec == null) continue;

            JsonArray off = new JsonArray();
            off.add(offset.x());
            off.add(offset.y());
            off.add(offset.z());
            offsets.add(off);
            blocks.addProperty(key, blockSpec);
        }

        if (offsets.isEmpty()) return;

        enqueueRepairWorkItem(state, config, offsets, blocks);
    }

    private static void enqueueRepairWorkItem(BuildingState state, BuildingConfig config,
                                               JsonArray offsets, JsonObject blocks) {
        Map<String, JsonElement> params = new HashMap<>();
        params.put("anchor", posToJsonArray(state.getAnchor()));
        params.put("offsets", offsets);
        params.put("blocks", blocks);
        params.put("name", new JsonPrimitive(config.displayName()));

        WorkItem repairWork = new WorkItem("build:place_structure", params, REPAIR_PRIORITY);
        state.getTaskQueue().addFirst(repairWork);
    }

    private static JsonArray posToJsonArray(BlockPos pos) {
        JsonArray arr = new JsonArray();
        arr.add(pos.getX());
        arr.add(pos.getY());
        arr.add(pos.getZ());
        return arr;
    }
}
