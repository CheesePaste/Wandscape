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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.List;

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
              "block_mapping": {"0,0,0": "minecraft:stone_bricks", "1,0,0": "wandscape:rune_pillar", "1,1,0": "wandscape:mage_crystal"},
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
             "pattern": [[0,0,0]], "block_mapping": {"0,0,0": "minecraft:stone"}}
            """;

        BuildingConfig cfg = GSON.fromJson(json, BuildingConfig.class);
        assertEquals(1, cfg.pattern().size());
        assertEquals("0,0,0", cfg.pattern().get(0).toKey());
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
                {"id":"breadshop","category":"shop",
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
                 "atm":{"withdraw_amount":50,"interaction_duration_ticks":1200},
                 "interact_spots":[{"pos":[0,0,0],"action":"withdraw"}]}
                """;
            BuildingConfig atmCfg = GSON.fromJson(atmJson, BuildingConfig.class);
            assertEquals(50, atmCfg.atm().withdrawAmount());
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
                {"id":"a","category":"atm","atm":{"withdraw_amount":50,"interaction_duration_ticks":100}}""";
            assertTrue(GSON.fromJson(atmJson, BuildingConfig.class).isTouristTarget());
            String basicJson = "{\"id\":\"b\",\"category\":\"basic\"}";
            assertFalse(GSON.fromJson(basicJson, BuildingConfig.class).isTouristTarget());
        }

        @Test
        void derivedTouristInteractAabbFromSpots() {
            String json = """
                {"id":"x","category":"shop",
                 "interact_spots":[{"pos":[1,0,1],"action":"browse"}]}
                """;
            BuildingConfig cfg = GSON.fromJson(json, BuildingConfig.class);
            assertEquals(1, cfg.touristInteractAabb().size());
            assertEquals(new BlockOffset(1, 0, 1), cfg.touristInteractAabb().get(0).min());
            assertEquals(new BlockOffset(1, 0, 1), cfg.touristInteractAabb().get(0).max());
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
}
