package com.wsteam.wandscape.building.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.data.WorkItem;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * Listens for block break and explosion events.
 * Marks affected buildings as {@code structureIntact = false} and enqueues a
 * partial repair WorkItem targeting only the damaged positions.
 */
public final class BuildingBreakHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
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
        if (state == null || !state.isStructureIntact()) return;

        state.setStructureIntact(false);
        enqueueRepairForPositions(state, List.of(pos));
        data.setDirty();
        LOGGER.info("[Building] Structure damaged: type={} at={} (block at {}) — partial repair enqueued",
                state.getBuildingTypeId(), state.getAnchor(), pos);
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

            state.setStructureIntact(false);
            enqueueRepairForPositions(state, entry.getValue());
            data.setDirty();
            LOGGER.info("[Building] Structure damaged by explosion: type={} at={} ({} blocks) — partial repair enqueued",
                    state.getBuildingTypeId(), state.getAnchor(), entry.getValue().size());
        }
    }

    /**
     * Enqueue a partial repair targeting specific world positions.
     * Used when the damaged positions are known from the break/explosion event.
     */
    static void enqueueRepairForPositions(BuildingState state, List<BlockPos> damagedWorldPositions) {
        BuildingConfig config = BuildingConfigLoader.getInstance().get(state.getBuildingTypeId());
        if (config == null) {
            LOGGER.warn("[Building] Cannot enqueue repair — config not found: {}", state.getBuildingTypeId());
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
