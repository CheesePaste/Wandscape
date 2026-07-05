package com.wsteam.wandscape.building.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.data.BuildingConfig.BoundaryBox;
import com.wsteam.wandscape.building.data.InteractionRadius;
import com.wsteam.wandscape.shared.data.MaintenanceCostConfig;
import com.wsteam.wandscape.shared.data.ShopConfig;
import com.wsteam.wandscape.shared.data.WonderConfig;
import com.wsteam.wandscape.shared.data.ServiceConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EnqueueHelper.computeClearOffsets")
class EnqueueHelperTest {

    private static BlockOffset off(int x, int y, int z) {
        return new BlockOffset(x, y, z);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> makeBlockMapping(String... kvPairs) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            m.put(kvPairs[i], kvPairs[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("town_hall: clear entire boundary box (18 positions), anchor included")
    void townHallFullBox() {
        BuildingConfig cfg = new BuildingConfig(
                "town_hall", "Test", "basic",
                List.of(
                        off(-1, 0, -1), off(-1, 0, 0), off(-1, 0, 1),
                        off(0, 0, -1),                     off(0, 0, 1),
                        off(1, 0, -1),  off(1, 0, 0),  off(1, 0, 1),
                        off(-1, 1, -1), off(-1, 1, 1), off(1, 1, -1), off(1, 1, 1)),
                makeBlockMapping(
                        "-1,0,-1", "minecraft:stone_bricks",
                        "-1,0,0", "minecraft:stone_bricks",
                        "-1,0,1", "minecraft:stone_bricks",
                        "0,0,-1", "minecraft:stone_bricks",
                        "0,0,1", "minecraft:stone_bricks",
                        "1,0,-1", "minecraft:stone_bricks",
                        "1,0,0", "minecraft:stone_bricks",
                        "1,0,1", "minecraft:stone_bricks",
                        "-1,1,-1", "minecraft:oak_log",
                        "-1,1,1", "minecraft:oak_log",
                        "1,1,-1", "minecraft:oak_log",
                        "1,1,1", "minecraft:oak_log"),
                5, 3, 2,
                BuildingConfig.QueueDef.DEFAULT,
                BuildingConfig.UnlockRequirement.NONE,
                new BoundaryBox(off(-1, 0, -1), off(1, 1, 1)),
                null,
                null, MaintenanceCostConfig.NONE, null, WonderConfig.NONE, ShopConfig.NONE, ServiceConfig.NONE, InteractionRadius.NONE, null, null  // nodeConfig
        );

        JsonElement result = EnqueueHelper.computeClearOffsets(cfg);
        assertTrue(result.isJsonArray());
        JsonArray arr = result.getAsJsonArray();

        // Boundary: 3×2×3 = 18 — anchor is vanilla now, included in clear
        assertEquals(18, arr.size(), "clear entire boundary box");

        java.util.Set<String> clearKeys = collectKeys(arr);

        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    String key = x + "," + y + "," + z;
                    assertTrue(clearKeys.contains(key),
                            "boundary position " + key + " must be in clear_offsets");
                }
            }
        }
    }

    @Test
    @DisplayName("single-block building: anchor-only boundary → clear anchor")
    void singleBlockClear() {
        BuildingConfig cfg = new BuildingConfig(
                "earth_node", "Test", "node",
                List.of(off(0, 0, 0)),
                Map.of("0,0,0", "minecraft:lodestone"),
                1, 2, 0,
                BuildingConfig.QueueDef.DEFAULT,
                BuildingConfig.UnlockRequirement.NONE,
                new BoundaryBox(off(0, 0, 0), off(0, 0, 0)),
                null,
                null, MaintenanceCostConfig.NONE, null, WonderConfig.NONE, ShopConfig.NONE, ServiceConfig.NONE, InteractionRadius.NONE, null, null  // nodeConfig
        );

        JsonElement result = EnqueueHelper.computeClearOffsets(cfg);
        JsonArray arr = result.getAsJsonArray();
        assertEquals(1, arr.size(),
                "single-block: anchor is vanilla → included in clear");
    }

    @Test
    @DisplayName("large 3×3×3 boundary: all 27 positions cleared")
    void largeBoundaryFullBox() {
        BuildingConfig cfg = new BuildingConfig(
                "test_large", "Test", "basic",
                List.of(off(0, 0, 0)),
                Map.of("0,0,0", "minecraft:stone"),
                1, 0, 0,
                BuildingConfig.QueueDef.DEFAULT,
                BuildingConfig.UnlockRequirement.NONE,
                new BoundaryBox(off(-1, -1, -1), off(1, 1, 1)),
                null,
                null, MaintenanceCostConfig.NONE, null, WonderConfig.NONE, ShopConfig.NONE, ServiceConfig.NONE, InteractionRadius.NONE, null, null  // nodeConfig
        );

        JsonElement result = EnqueueHelper.computeClearOffsets(cfg);
        JsonArray arr = result.getAsJsonArray();

        assertEquals(27, arr.size(), "clear entire 27-cell box");

        java.util.Set<String> clearKeys = collectKeys(arr);

        for (int x = -1; x <= 1; x++)
            for (int y = -1; y <= 1; y++)
                for (int z = -1; z <= 1; z++) {
                    String key = x + "," + y + "," + z;
                    assertTrue(clearKeys.contains(key), key + " must be in clear_offsets");
                }
    }

    private static java.util.Set<String> collectKeys(JsonArray arr) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (JsonElement el : arr) {
            JsonArray pos = el.getAsJsonArray();
            keys.add(pos.get(0).getAsInt() + "," + pos.get(1).getAsInt() + "," + pos.get(2).getAsInt());
        }
        return keys;
    }
}
