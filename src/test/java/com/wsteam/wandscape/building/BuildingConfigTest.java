package com.wsteam.wandscape.building;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.data.BuildingConfig.UnlockRequirement;
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
        assertEquals(0, cfg.unlockRequirement().minComfort());
        assertEquals(0, cfg.unlockRequirement().minMagic());
        assertEquals(0, cfg.unlockRequirement().minComfort());
        assertEquals(0, cfg.unlockRequirement().minMagic());
        assertEquals(0, cfg.unlockRequirement().minWonder());
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
              "unlock_requirement": {"min_comfort": 0, "min_magic": 0, "min_wonder": 15}
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
        assertEquals(15, cfg.unlockRequirement().minWonder());
        assertEquals(0, cfg.unlockRequirement().minComfort());
        assertEquals(0, cfg.unlockRequirement().minMagic());

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
            assertEquals(0, cfg.unlockRequirement().minComfort());
        assertEquals(0, cfg.unlockRequirement().minMagic());
        assertEquals(0, cfg.unlockRequirement().minWonder());
        }
    }
}
