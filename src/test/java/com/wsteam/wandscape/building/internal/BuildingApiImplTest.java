package com.wsteam.wandscape.building.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;

import org.junit.jupiter.api.Test;

/**
 * Guard against the double-refund item-mint bug in {@code cancelBuilding} (undo
 * an under-construction building). The refund must only cover offsets that were
 * never placed — already-placed offsets are refunded physically by the demolition
 * salvage flow, so refunding the full blueprint cost here mints materials.
 */
class BuildingApiImplTest {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(BlockOffset.class, new BlockOffset.Deserializer())
            .registerTypeAdapter(BuildingConfig.class, new BuildingConfig.Deserializer())
            .create();

    private static BuildingConfig config(String json) {
        return GSON.fromJson(json, BuildingConfig.class);
    }

    private static final String THREE_BLOCK =
            """
            {"id":"t","display_name":"T","category":"basic",
             "pattern":[[0,0,0],[1,0,0],[0,0,1]],
             "palette":["minecraft:stone","minecraft:oak_log"],
             "block_indices":[0,1,0]}
            """;
    // stone, oak_log, stone

    @Test
    void allMissingRefundsFullMaterialCounts() {
        BuildingConfig cfg = config(THREE_BLOCK);
        var missing = List.of(
                new BlockOffset(0, 0, 0), new BlockOffset(1, 0, 0), new BlockOffset(0, 0, 1));

        Map<String, Integer> counts = BuildingApiImpl.materialCountsForMissingOffsets(
                cfg, 0, missing, id -> true);

        assertEquals(Map.of("minecraft:stone", 2, "minecraft:oak_log", 1), counts);
    }

    @Test
    void placedOffsetIsExcludedFromRefund() {
        BuildingConfig cfg = config(THREE_BLOCK);
        // (1,0,0) = oak_log already placed → its material is returned by salvage, not refunded.
        var missing = List.of(new BlockOffset(0, 0, 0), new BlockOffset(0, 0, 1));

        Map<String, Integer> counts = BuildingApiImpl.materialCountsForMissingOffsets(
                cfg, 0, missing, id -> true);

        assertEquals(Map.of("minecraft:stone", 2), counts);
    }

    @Test
    void nothingMissingRefundsNothing() {
        BuildingConfig cfg = config(THREE_BLOCK);

        Map<String, Integer> counts = BuildingApiImpl.materialCountsForMissingOffsets(
                cfg, 0, List.of(), id -> true);

        assertTrue(counts.isEmpty());
    }

    @Test
    void airAndUnmappedBlocksAreSkipped() {
        BuildingConfig cfg = config(
                """
                {"id":"t","display_name":"T","category":"basic",
                 "pattern":[[0,0,0],[1,0,0],[2,0,0]],
                 "palette":["minecraft:air","minecraft:stone","wandscape:free"],
                 "block_indices":[0,1,2]}
                """);
        var missing = List.of(new BlockOffset(0, 0, 0), new BlockOffset(1, 0, 0), new BlockOffset(2, 0, 0));

        // air is skipped by the air filter; wandscape:free has no element mapping.
        Map<String, Integer> counts = BuildingApiImpl.materialCountsForMissingOffsets(
                cfg, 0, missing, id -> id.equals("minecraft:stone"));

        assertEquals(Map.of("minecraft:stone", 1), counts);
    }

    @Test
    void rotatedBuildingKeepsMaterialPerPatternIndex() {
        BuildingConfig cfg = config(THREE_BLOCK);
        // rotationSteps=1 rotates offsets: (0,0,0)->(0,0,0), (1,0,0)->(0,0,1), (0,0,1)->(-1,0,0).
        // Missing set uses the ROTATED coordinates; material still comes from pattern index.
        var missing = List.of(
                new BlockOffset(0, 0, 0), new BlockOffset(0, 0, 1)); // stone + oak_log missing

        Map<String, Integer> counts = BuildingApiImpl.materialCountsForMissingOffsets(
                cfg, 1, missing, id -> true);

        assertEquals(Map.of("minecraft:stone", 1, "minecraft:oak_log", 1), counts);
    }
}
