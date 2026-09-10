package com.wsteam.wandscape.content.building;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.data.BuildingPackage;
import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BuildingPackageTest {

    @BeforeEach
    void setUp() {
        BuildingConfigLoader.getInstance().clear();
    }

    @Test
    void testDefaultPackagePresence() {
        BuildingPackage defPkg = BuildingConfigLoader.getInstance().getPackage(BuildingPackage.DEFAULT_ID);
        assertNotNull(defPkg);
        assertEquals("default", defPkg.id());
        assertEquals(0, defPkg.priority());
    }

    @Test
    void testPackageJsonParsing() {
        String jsonStr = """
                {
                    "id": "oriental",
                    "name": "Oriental Village",
                    "description": "Traditional oriental buildings",
                    "author": "Tester",
                    "version": "1.2.0",
                    "icon": "minecraft:bamboo",
                    "priority": 50
                }
                """;
        JsonObject obj = JsonParser.parseString(jsonStr).getAsJsonObject();
        BuildingPackage pkg = BuildingPackage.fromJson("oriental", obj);
        assertEquals("oriental", pkg.id());
        assertEquals("Oriental Village", pkg.name());
        assertEquals("Traditional oriental buildings", pkg.description());
        assertEquals("Tester", pkg.author());
        assertEquals("1.2.0", pkg.version());
        assertEquals("minecraft:bamboo", pkg.iconItem());
        assertEquals(50, pkg.priority());
    }

    @Test
    void testBuildingDualKeyAliasResolution() {
        String buildingJson = """
                {
                    "id": "cottage",
                    "display_name": "Cozy Cottage",
                    "category": "basic",
                    "palette": ["minecraft:oak_planks"],
                    "block_indices": [0],
                    "pattern": [[0, 0, 0]]
                }
                """;
        BuildingConfigLoader loader = BuildingConfigLoader.getInstance();
        loader.registerFromJsonString("default", buildingJson);

        // Canonical full ID lookup
        BuildingConfig byFullId = loader.get("default:cottage");
        assertNotNull(byFullId);
        assertEquals("default:cottage", byFullId.id());
        assertEquals("default", byFullId.packageId());

        // Alias lookup with short name
        BuildingConfig byAlias = loader.get("cottage");
        assertNotNull(byAlias);
        assertSame(byFullId, byAlias);

        assertTrue(loader.has("default:cottage"));
        assertTrue(loader.has("cottage"));
        assertEquals("default:cottage", loader.resolveCanonicalId("cottage"));
    }

    @Test
    void testCustomPackageBuildingIsolation() {
        String customJson = """
                {
                    "id": "tea_house",
                    "display_name": "Tea House",
                    "category": "service",
                    "palette": ["minecraft:bamboo_planks"],
                    "block_indices": [0],
                    "pattern": [[0, 0, 0]]
                }
                """;
        BuildingConfigLoader loader = BuildingConfigLoader.getInstance();
        loader.registerFromJsonString("oriental", customJson);

        BuildingConfig byFull = loader.get("oriental:tea_house");
        assertNotNull(byFull);
        assertEquals("oriental:tea_house", byFull.id());
        assertEquals("oriental", byFull.packageId());

        // Alias lookup for tea_house
        BuildingConfig byAlias = loader.get("tea_house");
        assertNotNull(byAlias);
        assertSame(byFull, byAlias);

        List<BuildingConfig> orientalBuildings = loader.getConfigsByPackage("oriental");
        assertEquals(1, orientalBuildings.size());
        assertEquals("oriental:tea_house", orientalBuildings.get(0).id());
    }

    @Test
    void testMultiplePackagesSortingByPriority() {
        BuildingConfigLoader loader = BuildingConfigLoader.getInstance();
        loader.registerPackage(new BuildingPackage("pack_c", "Pack C", "", "", "1.0", "", 200, List.of()));
        loader.registerPackage(new BuildingPackage("pack_a", "Pack A", "", "", "1.0", "", 10, List.of()));

        List<BuildingPackage> all = loader.getAllPackages();
        assertTrue(all.size() >= 3);
        // Default pack has priority 0
        assertEquals("default", all.get(0).id());
        assertEquals("pack_a", all.get(1).id());
    }
}
