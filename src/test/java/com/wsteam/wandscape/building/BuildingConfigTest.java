package com.wsteam.wandscape.building;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.data.BuildingConfig.UnlockRequirement;
import com.wsteam.wandscape.shared.data.Activity;
import com.wsteam.wandscape.shared.data.AtmConfig;
import com.wsteam.wandscape.shared.data.RelaxConfig;

import net.minecraft.core.Direction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.List;
import java.util.Map;

class BuildingConfigTest {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(BlockOffset.class, new BlockOffset.Deserializer())
            .registerTypeAdapter(BuildingConfig.class, new BuildingConfig.Deserializer())
            .create();

    @Test
    void parseMinimalConfig() {
        JsonObject json = new JsonObject();
        json.addProperty("id", "town_hall");
        json.addProperty("display_name", "Town Hall");
        json.addProperty("category", "basic");
        // No pattern, block_mapping, queue, unlock_requirement

        BuildingConfig cfg = GSON.fromJson(json, BuildingConfig.class);

        assertEquals("town_hall", cfg.id());
        assertEquals("Town Hall", cfg.displayName());
        assertEquals("basic", cfg.category());
        assertTrue(cfg.pattern().isEmpty());
        assertTrue(cfg.blockMapping().isEmpty());
        assertEquals(0, cfg.comfort());
        assertEquals(0, cfg.magic());
        assertEquals(0, cfg.wonder());
        assertNotNull(cfg.queue());
        assertEquals(5, cfg.queue().capacity());
        assertNotNull(cfg.unlockRequirement());
        assertEquals(1, cfg.unlockRequirement().minColonyLevel());
        assertSame(UnlockRequirement.NONE, cfg.unlockRequirement());
    }

    @Test
    void parseFullConfig() {
        String json = """
            {
              "id": "mage_tower",
              "display_name": "Mage Tower",
              "category": "wonder",
              "pattern": [[0,0,0], [1,0,0], [1,1,0]],
              "palette": ["minecraft:stone_bricks", "wandscape:rune_pillar", "wandscape:mage_crystal"],
              "block_indices": [0, 1, 2],
              "comfort": 2,
              "magic": 3,
              "wonder": 5,
              "queue": {"capacity": 60, "task_types": ["crafting", "ritual"]},
              "unlock_requirement": {"min_colony_level": 5}
            }
            """;

        BuildingConfig cfg = GSON.fromJson(json, BuildingConfig.class);

        assertEquals("mage_tower", cfg.id());
        assertEquals("Mage Tower", cfg.displayName());
        assertEquals("wonder", cfg.category());
        assertEquals(2, cfg.comfort());
        assertEquals(3, cfg.magic());
        assertEquals(5, cfg.wonder());
        assertEquals(60, cfg.queue().capacity());
        assertEquals(List.of("crafting", "ritual"), cfg.queue().taskTypes());
        assertEquals(5, cfg.unlockRequirement().minColonyLevel());

        // Pattern
        assertEquals(3, cfg.pattern().size());
        assertEquals(new BlockOffset(0, 0, 0), cfg.pattern().get(0));
        assertEquals(new BlockOffset(1, 0, 0), cfg.pattern().get(1));
        assertEquals(new BlockOffset(1, 1, 0), cfg.pattern().get(2));

        // Block mapping
        assertEquals(3, cfg.blockMapping().size());
        assertEquals("minecraft:stone_bricks", cfg.blockMapping().get("0,0,0"));
        assertEquals("wandscape:rune_pillar", cfg.blockMapping().get("1,0,0"));
    }

    @Test
    void singleBlockPattern() {
        String json = """
            {"id": "test", "display_name": "Test", "category": "basic",
             "pattern": [[0,0,0]], "palette": ["minecraft:stone"], "block_indices": [0]}
            """;

        BuildingConfig cfg = GSON.fromJson(json, BuildingConfig.class);
        assertEquals(1, cfg.pattern().size());
        assertEquals("0,0,0", cfg.pattern().get(0).toKey());
    }

    @Test
    void paletteFormatExposesIndicesAndDerivedMapping() {
        String json = """
            {"id": "s", "display_name": "S", "category": "basic",
             "pattern": [[0,0,0], [1,0,0], [0,0,1]],
             "palette": ["minecraft:stone", "minecraft:oak_log"],
             "block_indices": [0, 1, 0]}
            """;
        BuildingConfig cfg = GSON.fromJson(json, BuildingConfig.class);

        assertEquals(List.of("minecraft:stone", "minecraft:oak_log"), cfg.palette());
        assertEquals(List.of(0, 1, 0), cfg.blockIndices());
        assertEquals("minecraft:stone", cfg.blockIdAt(0));
        assertEquals("minecraft:oak_log", cfg.blockIdAt(1));
        assertEquals("minecraft:stone", cfg.blockIdAt(2));

        Map<String, String> derived = cfg.blockMapping();
        assertEquals(3, derived.size());
        assertEquals("minecraft:stone", derived.get("0,0,0"));
        assertEquals("minecraft:oak_log", derived.get("1,0,0"));
        assertEquals("minecraft:stone", derived.get("0,0,1"));
    }

    @Test
    void legacyBlockMappingIsRejected() {
        String json = """
            {"id": "old", "display_name": "Old", "category": "basic",
             "pattern": [[0,0,0]], "block_mapping": {"0,0,0": "minecraft:stone"}}
            """;
        assertThrows(com.google.gson.JsonParseException.class, () -> GSON.fromJson(json, BuildingConfig.class));
    }

    @Test
    void misalignedBlockIndicesAreRejected() {
        String json = """
            {"id": "bad", "display_name": "Bad", "category": "basic",
             "pattern": [[0,0,0], [1,0,0]],
             "palette": ["minecraft:stone"],
             "block_indices": [0]}
            """;
        assertThrows(com.google.gson.JsonParseException.class, () -> GSON.fromJson(json, BuildingConfig.class));

        String outOfRange = """
            {"id": "bad2", "display_name": "Bad2", "category": "basic",
             "pattern": [[0,0,0]],
             "palette": ["minecraft:stone"],
             "block_indices": [5]}
            """;
        assertThrows(com.google.gson.JsonParseException.class, () -> GSON.fromJson(outOfRange, BuildingConfig.class));
    }

    @Nested
    class BlockOffsetTests {
        @Test
        void toKeyFormatsCorrectly() {
            assertEquals("3,-2,7", new BlockOffset(3, -2, 7).toKey());
            assertEquals("0,0,0", new BlockOffset(0, 0, 0).toKey());
        }

        @Test
        void equalityIsValueBased() {
            assertEquals(new BlockOffset(1, 2, 3), new BlockOffset(1, 2, 3));
            assertNotEquals(new BlockOffset(1, 2, 3), new BlockOffset(1, 2, 4));
        }

        @Test
        void factoryMethod() {
            assertEquals(new BlockOffset(5, 6, 7), BlockOffset.of(5, 6, 7));
        }
    }

    @Nested
    class DefaultValues {
        @Test
        void missingQueueUsesDefault() {
            JsonObject json = new JsonObject();
            json.addProperty("id", "test");
            json.addProperty("display_name", "Test");
            json.addProperty("category", "basic");

            BuildingConfig cfg = GSON.fromJson(json, BuildingConfig.class);
            assertEquals(5, cfg.queue().capacity());
            assertEquals(List.of("building"), cfg.queue().taskTypes());
        }

        @Test
        void missingUnlockRequirementUsesNone() {
            JsonObject json = new JsonObject();
            json.addProperty("id", "test");
            json.addProperty("display_name", "Test");
            json.addProperty("category", "basic");

            BuildingConfig cfg = GSON.fromJson(json, BuildingConfig.class);
            assertEquals(1, cfg.unlockRequirement().minColonyLevel());
        }
    }

    @Nested
    class NewTouristSchema {
        @Test
        void parseInteractSpotsWithActions() {
            String json = """
                {"id":"bakery","category":"shop",
                 "interact_spots":[
                   {"pos":[1,0,1],"action":"browse"},
                   {"pos":[3,0,1],"action":"eat"}
                 ]}
                """;
            BuildingConfig cfg = GSON.fromJson(json, BuildingConfig.class);
            assertEquals(2, cfg.interactSpots().size());
            assertEquals(new BlockOffset(1, 0, 1), cfg.interactSpots().get(0).pos());
            assertEquals(Activity.BROWSE, cfg.interactSpots().get(0).action());
            assertEquals(Activity.EAT, cfg.interactSpots().get(1).action());
        }

        @Test
        void invalidActionFallsBackToBrowse() {
            // "bath" 不是枚举值（BATHE）→ 回退 BROWSE，静默不报错
            String json = """
                {"id":"x","category":"shop",
                 "interact_spots":[{"pos":[0,0,0],"action":"bath"}]}
                """;
            BuildingConfig cfg = GSON.fromJson(json, BuildingConfig.class);
            assertEquals(Activity.BROWSE, cfg.interactSpots().get(0).action());
        }

        @Test
        void missingActionDefaultsBrowse() {
            String json = """
                {"id":"x","category":"shop",
                 "interact_spots":[{"pos":[0,0,0]}]}
                """;
            BuildingConfig cfg = GSON.fromJson(json, BuildingConfig.class);
            assertEquals(Activity.BROWSE, cfg.interactSpots().get(0).action());
        }

        @Test
        void parseSpotFacing() {
            String json = """
                {"id":"x","category":"shop",
                 "interact_spots":[{"pos":[0,0,0],"action":"eat","facing":"north"}]}
                """;
            BuildingConfig cfg = GSON.fromJson(json, BuildingConfig.class);
            assertEquals(Direction.NORTH, cfg.interactSpots().get(0).facing());

            // 缺省/非法 facing → 回退 SOUTH
            String noFacing = """
                {"id":"x","category":"shop",
                 "interact_spots":[{"pos":[0,0,0],"action":"eat"}]}
                """;
            assertEquals(Direction.SOUTH,
                    GSON.fromJson(noFacing, BuildingConfig.class).interactSpots().get(0).facing());
            String badFacing = """
                {"id":"x","category":"shop",
                 "interact_spots":[{"pos":[0,0,0],"action":"eat","facing":"up"}]}
                """;
            assertEquals(Direction.SOUTH,
                    GSON.fromJson(badFacing, BuildingConfig.class).interactSpots().get(0).facing());
        }

        @Test
        void parseRelaxAndAtmBlocks() {
            String relaxJson = """
                {"id":"bath","category":"relax",
                 "relax":{"energy_restore":40,"interaction_duration_ticks":1200},
                 "interact_spots":[{"pos":[0,1,0],"action":"bathe"}]}
                """;
            BuildingConfig relaxCfg = GSON.fromJson(relaxJson, BuildingConfig.class);
            assertEquals(40, relaxCfg.relax().energyRestore());
            assertEquals(1200, relaxCfg.relax().interactionDurationTicks());
            assertEquals(Activity.BATHE, relaxCfg.interactSpots().get(0).action());

            String atmJson = """
                {"id":"atm1","category":"atm",
                 "atm":{"interaction_duration_ticks":1200},
                 "interact_spots":[{"pos":[0,0,0],"action":"withdraw"}]}
                """;
            BuildingConfig atmCfg = GSON.fromJson(atmJson, BuildingConfig.class);
            assertEquals(1200, atmCfg.atm().interactionDurationTicks());
            assertEquals(Activity.WITHDRAW, atmCfg.interactSpots().get(0).action());

            // 缺省 = NONE / 空
            String basicJson = "{\"id\":\"b\",\"category\":\"basic\"}";
            BuildingConfig basic = GSON.fromJson(basicJson, BuildingConfig.class);
            assertEquals(RelaxConfig.NONE, basic.relax());
            assertEquals(AtmConfig.NONE, basic.atm());
            assertTrue(basic.interactSpots().isEmpty());
        }

        @Test
        void isTouristTargetDetectsFourCategories() {
            String shopJson = """
                {"id":"s","category":"shop","shop":{"goods":[],"profit_rate":0.3,"interaction_duration_ticks":2400}}""";
            assertTrue(GSON.fromJson(shopJson, BuildingConfig.class).isTouristTarget());
            String serviceJson = """
                {"id":"i","category":"service","service":{"energy_per_use":20,"max_occupancy":0,"interaction_duration_ticks":1200}}""";
            assertTrue(GSON.fromJson(serviceJson, BuildingConfig.class).isTouristTarget());
            String relaxJson = """
                {"id":"r","category":"relax","relax":{"energy_restore":40,"interaction_duration_ticks":100}}""";
            assertTrue(GSON.fromJson(relaxJson, BuildingConfig.class).isTouristTarget());
            String atmJson = """
                {"id":"a","category":"atm","atm":{"interaction_duration_ticks":100}}""";
            assertTrue(GSON.fromJson(atmJson, BuildingConfig.class).isTouristTarget());
            String basicJson = "{\"id\":\"b\",\"category\":\"basic\"}";
            assertFalse(GSON.fromJson(basicJson, BuildingConfig.class).isTouristTarget());
        }

        @Test
        void legacyTouristInteractAabbIsIgnored() {
            String json = """
                {"id":"x","category":"shop",
                 "tourist_interact_aabb":[{"min":[-1,0,-1],"max":[1,0,1]}]}
                """;
            BuildingConfig cfg = GSON.fromJson(json, BuildingConfig.class);
            assertTrue(cfg.interactSpots().isEmpty());
        }
    }

    @Nested
    class DoorOffsets {
        @Test
        void parseMultiDoorOffsets() {
            String json = """
                {"id":"inn","category":"tavern",
                 "door_offsets":[[1,0,1],[3,0,1]]}
                """;
            BuildingConfig cfg = GSON.fromJson(json, BuildingConfig.class);
            assertEquals(2, cfg.doorOffsets().size());
            assertEquals(new BlockOffset(1, 0, 1), cfg.doorOffsets().get(0));
            assertEquals(new BlockOffset(3, 0, 1), cfg.doorOffsets().get(1));
        }

        @Test
        void legacySingleDoorOffsetStillLoads() {
            String json = """
                {"id":"bakery","category":"shop",
                 "door_offset":[1,0,1]}
                """;
            BuildingConfig cfg = GSON.fromJson(json, BuildingConfig.class);
            assertEquals(1, cfg.doorOffsets().size());
            assertEquals(new BlockOffset(1, 0, 1), cfg.doorOffsets().get(0));
        }

        @Test
        void missingDoorOffsetsDefaultsEmpty() {
            String json = """
                {"id":"plaza","category":"basic"}
                """;
            BuildingConfig cfg = GSON.fromJson(json, BuildingConfig.class);
            assertTrue(cfg.doorOffsets().isEmpty());
        }
    }

    @Nested
    class DecorationEntities {
        @Test
        void parseEntitiesArray() {
            String json = """
                {"id":"gallery","category":"custom",
                 "entities":[
                   {"offset":[1,2,0],"type":"minecraft:item_frame","facing":"north","nbt":"aGVsbG8="},
                   {"offset":[1,2,0],"type":"minecraft:painting","facing":"south","nbt":"d29ybGQ="}
                 ]}
                """;
            BuildingConfig cfg = GSON.fromJson(json, BuildingConfig.class);
            assertEquals(2, cfg.entities().size());
            assertEquals(new BlockOffset(1, 2, 0), cfg.entities().get(0).offset());
            assertEquals("minecraft:item_frame", cfg.entities().get(0).type());
            assertEquals("north", cfg.entities().get(0).facing());
            assertEquals("aGVsbG8=", cfg.entities().get(0).nbtBase64());
            assertEquals("minecraft:painting", cfg.entities().get(1).type());
            assertEquals("south", cfg.entities().get(1).facing());
        }

        @Test
        void missingEntitiesDefaultsEmpty() {
            String json = "{\"id\":\"x\",\"category\":\"basic\"}";
            BuildingConfig cfg = GSON.fromJson(json, BuildingConfig.class);
            assertTrue(cfg.entities().isEmpty());
        }
    }
}
