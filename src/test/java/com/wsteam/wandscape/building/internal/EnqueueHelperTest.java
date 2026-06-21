package com.wsteam.wandscape.building.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.data.BuildingConfig.BoundaryBox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EnqueueHelper.computeClearOffsets")
class EnqueueHelperTest {

    private static BlockOffset off(int x, int y, int z) {
        return new BlockOffset(x, y, z);
    }

    /** Build a block_mapping map from alternating key,value pairs. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> makeBlockMapping(String... kvPairs) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            m.put(kvPairs[i], kvPairs[i + 1]);
        }
        return m;
    }

    // ──────────────────────────────────────────────
    // Town hall: 3×2×3 boundary = 18 positions
    // Clear = all 18 minus anchor [0,0,0] → 17
    // Pattern positions ARE included — clear wipes the full box
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("town_hall: clear entire boundary box (17 positions), anchor excluded")
    void townHallFullBox() {
        BuildingConfig cfg = new BuildingConfig(
                "town_hall", "Test", "basic", "wandscape:test",
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
                5, 3, 2, 4,
                BuildingConfig.ShutdownPenalty.DEFAULT,
                BuildingConfig.QueueDef.DEFAULT,
                BuildingConfig.UnlockRequirement.NONE,
                new BoundaryBox(off(-1, 0, -1), off(1, 1, 1)),
                null,
                null  // nodeConfig
        );

        JsonElement result = EnqueueHelper.computeClearOffsets(cfg);
        assertTrue(result.isJsonArray());
        JsonArray arr = result.getAsJsonArray();

        // Boundary: 3×2×3 = 18, minus anchor [0,0,0] = 17
        assertEquals(17, arr.size(), "clear entire boundary box minus anchor");

        // Collect as keys
        java.util.Set<String> clearKeys = collectKeys(arr);

        // Anchor must NOT be cleared
        assertFalse(clearKeys.contains("0,0,0"), "anchor must not be cleared");

        // All non-anchor boundary positions must be present
        // x ∈ {-1,0,1}, y ∈ {0,1}, z ∈ {-1,0,1}, skip (0,0,0)
        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    String key = x + "," + y + "," + z;
                    if ("0,0,0".equals(key)) continue;
                    assertTrue(clearKeys.contains(key),
                            "boundary position " + key + " must be in clear_offsets");
                }
            }
        }
    }

    @Test
    @DisplayName("single-block building: anchor-only boundary → empty clear")
    void singleBlockEmptyClear() {
        BuildingConfig cfg = new BuildingConfig(
                "earth_node", "Test", "node", "wandscape:test",
                List.of(off(0, 0, 0)),
                Map.of("0,0,0", "wandscape:earth_node"),
                1, 2, 0, 2,
                BuildingConfig.ShutdownPenalty.DEFAULT,
                BuildingConfig.QueueDef.DEFAULT,
                BuildingConfig.UnlockRequirement.NONE,
                new BoundaryBox(off(0, 0, 0), off(0, 0, 0)),
                null,
                null  // nodeConfig
        );

        JsonElement result = EnqueueHelper.computeClearOffsets(cfg);
        JsonArray arr = result.getAsJsonArray();
        assertEquals(0, arr.size(),
                "single-block building: boundary == anchor → nothing to clear");
    }

    @Test
    @DisplayName("large 3×3×3 boundary: 26 positions cleared, anchor excluded")
    void largeBoundaryFullBox() {
        BuildingConfig cfg = new BuildingConfig(
                "test_large", "Test", "basic", "wandscape:test",
                List.of(off(0, 0, 0)),
                Map.of("0,0,0", "minecraft:stone"),
                1, 0, 0, 1,
                BuildingConfig.ShutdownPenalty.DEFAULT,
                BuildingConfig.QueueDef.DEFAULT,
                BuildingConfig.UnlockRequirement.NONE,
                new BoundaryBox(off(-1, -1, -1), off(1, 1, 1)),
                null,
                null  // nodeConfig
        );

        JsonElement result = EnqueueHelper.computeClearOffsets(cfg);
        JsonArray arr = result.getAsJsonArray();

        // Boundary: 3×3×3 = 27, minus anchor [0,0,0] = 26
        assertEquals(26, arr.size(), "clear entire 27-cell box minus anchor");

        java.util.Set<String> clearKeys = collectKeys(arr);
        assertFalse(clearKeys.contains("0,0,0"), "anchor must not be cleared");

        // All other 26 positions must be present
        for (int x = -1; x <= 1; x++)
            for (int y = -1; y <= 1; y++)
                for (int z = -1; z <= 1; z++) {
                    String key = x + "," + y + "," + z;
                    if ("0,0,0".equals(key)) continue;
                    assertTrue(clearKeys.contains(key), key + " must be in clear_offsets");
                }
    }

    // ── helpers ──

    private static java.util.Set<String> collectKeys(JsonArray arr) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (JsonElement el : arr) {
            JsonArray pos = el.getAsJsonArray();
            keys.add(pos.get(0).getAsInt() + "," + pos.get(1).getAsInt() + "," + pos.get(2).getAsInt());
        }
        return keys;
    }
}
