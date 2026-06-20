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
import org.junit.jupiter.api.Nested;
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
    // Town hall scenario: 3×2×3 boundary, 12 pattern blocks (8 floor + 4 pillars)
    // Anchor [0,0,0] is NOT in pattern — must be preserved
    // ──────────────────────────────────────────────

    @Test
    @DisplayName("town_hall: anchor excluded, non-pattern positions included")
    void townHallAnchorPreserved() {
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
                null // no blueprint ref — doesn't matter for this test
        );

        JsonElement result = EnqueueHelper.computeClearOffsets(cfg);
        assertTrue(result.isJsonArray());
        JsonArray arr = result.getAsJsonArray();

        // Boundary: 3×2×3 = 18 positions
        // Pattern:  12 positions in block_mapping
        // Anchor:    1 position [0,0,0] (auto-preserved)
        // Expected clear_offsets: 18 - 12 - 1 = 5 positions
        assertEquals(5, arr.size(), "should clear 5 non-pattern, non-anchor positions");

        // These are the 5 y=1 non-corner positions
        // (-1,1,0), (0,1,-1), (0,1,0), (0,1,1), (1,1,0)
        // Verify that ALL 12 pattern positions are NOT in clear_offsets
        String[] patternKeys = {
                "-1,0,-1", "-1,0,0", "-1,0,1",
                "0,0,-1", "0,0,1",
                "1,0,-1", "1,0,0", "1,0,1",
                "-1,1,-1", "-1,1,1", "1,1,-1", "1,1,1"
        };
        String[] expectedClear = {"-1,1,0", "0,1,-1", "0,1,0", "0,1,1", "1,1,0"};

        // Collect clear_offsets as key strings
        java.util.Set<String> clearKeys = new java.util.HashSet<>();
        for (JsonElement el : arr) {
            JsonArray pos = el.getAsJsonArray();
            String key = pos.get(0).getAsInt() + "," + pos.get(1).getAsInt() + "," + pos.get(2).getAsInt();
            clearKeys.add(key);
        }

        // Pattern positions must NOT be in clear_offsets
        for (String pk : patternKeys) {
            assertFalse(clearKeys.contains(pk),
                    "pattern position " + pk + " must NOT be in clear_offsets");
        }

        // Anchor [0,0,0] must NOT be in clear_offsets
        assertFalse(clearKeys.contains("0,0,0"),
                "anchor [0,0,0] must NOT be in clear_offsets");

        // Expected clear positions must be present
        for (String ek : expectedClear) {
            assertTrue(clearKeys.contains(ek),
                    "expected clear position " + ek + " must be in clear_offsets");
        }
    }

    @Test
    @DisplayName("single-block building: clear_offsets empty when boundary == pattern + anchor")
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
                null
        );

        JsonElement result = EnqueueHelper.computeClearOffsets(cfg);
        assertTrue(result.isJsonArray());
        JsonArray arr = result.getAsJsonArray();
        // Boundary = 1 position [0,0,0], Pattern = [0,0,0], Anchor = auto-preserved
        // clear = 1 - 1 - 0 = 0
        assertEquals(0, arr.size(),
                "single-block building with boundary at anchor should have no clear offsets");
    }

    @Test
    @DisplayName("large boundary: extra volume around pattern is cleared")
    void largeBoundaryExtraVolume() {
        // A 3×3×3 boundary with a 1×1×1 pattern at center
        BuildingConfig cfg = new BuildingConfig(
                "test_large", "Test", "basic", "wandscape:test",
                List.of(off(0, 0, 0)),
                Map.of("0,0,0", "minecraft:stone"),
                1, 0, 0, 1,
                BuildingConfig.ShutdownPenalty.DEFAULT,
                BuildingConfig.QueueDef.DEFAULT,
                BuildingConfig.UnlockRequirement.NONE,
                new BoundaryBox(off(-1, -1, -1), off(1, 1, 1)),
                null
        );

        JsonElement result = EnqueueHelper.computeClearOffsets(cfg);
        JsonArray arr = result.getAsJsonArray();

        // Boundary: 3×3×3 = 27 positions
        // Pattern:  1 position "0,0,0"
        // Anchor:   [0,0,0] same as pattern in this case, already in pattern keys
        // clear = 27 - 1 = 26 positions
        assertEquals(26, arr.size());

        // Verify [0,0,0] is NOT in clear offsets
        java.util.Set<String> clearKeys = new java.util.HashSet<>();
        for (JsonElement el : arr) {
            JsonArray pos = el.getAsJsonArray();
            String key = pos.get(0).getAsInt() + "," + pos.get(1).getAsInt() + "," + pos.get(2).getAsInt();
            clearKeys.add(key);
        }
        assertFalse(clearKeys.contains("0,0,0"), "anchor/pattern position must not be cleared");
    }
}
