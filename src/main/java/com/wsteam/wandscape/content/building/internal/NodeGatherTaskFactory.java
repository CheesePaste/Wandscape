package com.wsteam.wandscape.content.building.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.data.WorkItem;
import com.wsteam.wandscape.foundation.registry.WandscapeConstants;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds a {@code node:gather} {@link WorkItem} from a node building's
 * {@link BuildingConfig.NodeConfig}. Mirrors the auto-supply construction in
 * {@code BuildingTaskSource}, except {@code amount} is scaled by the number of
 * harvests requested by the player.
 */
public final class NodeGatherTaskFactory {

    // 玩家手动发布的采集任务与玩家生产同级，进最高优先级段（补货/自动合成之前）。
    private static final int GATHER_PRIORITY = WandscapeConstants.TASK_PRIORITY_PLAYER;

    private NodeGatherTaskFactory() {}

    public static WorkItem buildWorkItem(BlockPos nodePos, BuildingConfig.NodeConfig config, int harvests) {
        int count = Math.max(harvests, 1);
        Map<String, JsonElement> params = new LinkedHashMap<>();
        params.put("anchor", posToJsonArray(nodePos));
        params.put("element", new JsonPrimitive(config.element()));
        params.put("amount", new JsonPrimitive(config.amountPerHarvest() * count));
        params.put("channel_ticks", new JsonPrimitive(config.channelTicks()));
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
