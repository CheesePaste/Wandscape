package com.wsteam.wandscape.building.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.building.data.BuildingConfig.NodeConfig;
import com.wsteam.wandscape.shared.data.WorkItem;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeGatherTaskFactoryTest {

    private static final NodeConfig CONFIG =
            new NodeConfig("node:gather", "wood", 10, 1200);

    @Test
    void buildWorkItem_scalesAmountByHarvests() {
        WorkItem item = NodeGatherTaskFactory.buildWorkItem(new BlockPos(3, 64, -5), CONFIG, 4);

        assertEquals("node:gather", item.blueprintId());
        assertEquals(15, item.priority());
        assertNotNull(item.params());

        assertEquals(3, arrayParam(item.params(), "anchor", 0));
        assertEquals(64, arrayParam(item.params(), "anchor", 1));
        assertEquals(-5, arrayParam(item.params(), "anchor", 2));
        assertEquals("wood", strParam(item.params(), "element"));
        assertEquals(40, intParam(item.params(), "amount"));
        assertEquals(1200, intParam(item.params(), "channel_ticks"));
    }

    @Test
    void buildWorkItem_singleHarvestKeepsBaseValues() {
        WorkItem item = NodeGatherTaskFactory.buildWorkItem(new BlockPos(1, 2, 3), CONFIG, 1);

        assertEquals(10, intParam(item.params(), "amount"));
    }

    @Test
    void buildWorkItem_clampsHarvestsToOne() {
        WorkItem item = NodeGatherTaskFactory.buildWorkItem(new BlockPos(0, 0, 0), CONFIG, 0);

        assertEquals(10, intParam(item.params(), "amount"));
    }

    private static int intParam(Map<String, JsonElement> params, String key) {
        JsonElement el = params.get(key);
        assertTrue(el instanceof JsonPrimitive && ((JsonPrimitive) el).isNumber(),
                "expected numeric param " + key);
        return el.getAsInt();
    }

    private static String strParam(Map<String, JsonElement> params, String key) {
        JsonElement el = params.get(key);
        assertTrue(el instanceof JsonPrimitive && ((JsonPrimitive) el).isString(),
                "expected string param " + key);
        return el.getAsString();
    }

    private static int arrayParam(Map<String, JsonElement> params, String key, int index) {
        JsonElement el = params.get(key);
        assertTrue(el instanceof JsonArray, "expected array param " + key);
        JsonArray arr = (JsonArray) el;
        assertTrue(arr.get(index).isJsonPrimitive() && arr.get(index).getAsJsonPrimitive().isNumber(),
                "expected numeric array element " + index + " of " + key);
        return arr.get(index).getAsInt();
    }
}
