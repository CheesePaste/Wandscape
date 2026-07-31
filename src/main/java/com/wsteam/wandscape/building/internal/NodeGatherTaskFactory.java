package com.wsteam.wandscape.building.internal;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.data.WorkItem;

import net.minecraft.core.BlockPos;

/**
 * Builds a {@code node:gather} {@link WorkItem} from a node building's
 * {@link BuildingConfig.NodeConfig}. Mirrors the auto-supply construction in
 * {@code BuildingTaskSource}, except {@code amount} and {@code mana_cost} are
 * scaled by the number of harvests requested by the player.
 */
public final class NodeGatherTaskFactory {

    private static final int GATHER_PRIORITY = 15;

    private NodeGatherTaskFactory() {}

    public static WorkItem buildWorkItem(BlockPos nodePos, BuildingConfig.NodeConfig config, int harvests) {
        int count = Math.max(harvests, 1);
        Map<String, JsonElement> params = new LinkedHashMap<>();
        params.put("anchor", posToJsonArray(nodePos));
        params.put("element", new JsonPrimitive(config.element()));
        params.put("amount", new JsonPrimitive(config.amountPerHarvest() * count));
        params.put("channel_ticks", new JsonPrimitive(config.channelTicks()));
        params.put("mana_cost", new JsonPrimitive(config.manaCost() * count));
        return new WorkItem(config.blueprint(), params, GATHER_PRIORITY);
    }

    private static JsonArray posToJsonArray(BlockPos pos) {
        JsonArray arr = new JsonArray();
        arr.add(pos.getX());
        arr.add(pos.getY());
        arr.add(pos.getZ());
        return arr;
    }
}
