package com.wsteam.wandscape.content.building;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.data.BuildingPackage;
import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.foundation.ui.settings.SettingItem;
import com.wsteam.wandscape.foundation.ui.settings.SettingTab;
import com.wsteam.wandscape.foundation.ui.settings.SettingsRegistry;
import com.wsteam.wandscape.foundation.ui.settings.network.ConfigUpdatePacket;
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

    @Test
    void testScannerExportPackageRegistration() {
        BuildingConfigLoader loader = BuildingConfigLoader.getInstance();

        // Simulate package metadata auto-registration from scanner export
        JsonObject pkgJson = new JsonObject();
        pkgJson.addProperty("id", "steampunk");
        pkgJson.addProperty("name", "Steampunk Era");
        pkgJson.addProperty("author", "Engineer");
        BuildingPackage pkgMeta = BuildingPackage.fromJson("steampunk", pkgJson);
        loader.registerPackage(pkgMeta);

        // Simulate building JSON exported by scanner
        JsonObject buildingJson = new JsonObject();
        buildingJson.addProperty("id", "clock_tower");
        buildingJson.addProperty("package_id", "steampunk");
        buildingJson.addProperty("display_name", "Clock Tower");
        buildingJson.addProperty("category", "wonder");
        com.google.gson.JsonArray palette = new com.google.gson.JsonArray();
        palette.add("minecraft:copper_block");
        buildingJson.add("palette", palette);
        buildingJson.add("block_indices", new com.google.gson.JsonArray());
        buildingJson.add("pattern", new com.google.gson.JsonArray());

        loader.registerFromJson("steampunk", buildingJson);

        // Verify package exists and is listed
        BuildingPackage foundPkg = loader.getPackage("steampunk");
        assertNotNull(foundPkg);
        assertEquals("Steampunk Era", foundPkg.name());

        // Verify building is registered under canonical ID
        BuildingConfig config = loader.get("steampunk:clock_tower");
        assertNotNull(config);
        assertEquals("steampunk:clock_tower", config.id());
        assertEquals("steampunk", config.packageId());
        assertEquals("Clock Tower", config.displayName());

        // Verify package filtering
        List<BuildingConfig> list = loader.getConfigsByPackage("steampunk");
        assertEquals(1, list.size());
        assertEquals("steampunk:clock_tower", list.get(0).id());
    }

    @Test
    void testPackageEnableDisableConfig() {
        Config.setDisabledPackages(List.of());
        assertTrue(Config.isPackageEnabled("oriental"));
        assertTrue(Config.isPackageEnabled("default"));

        Config.setPackageEnabled("oriental", false);
        assertFalse(Config.isPackageEnabled("oriental"));
        assertTrue(Config.isPackageEnabled("default"));

        Config.setPackageEnabled("oriental", true);
        assertTrue(Config.isPackageEnabled("oriental"));
    }

    @Test
    void testConfigUpdatePacketPackageIntegration() {
        Config.setDisabledPackages(List.of());
        Config.setPackageEnabled("oriental", true);

        // Disable via packet apply
        assertTrue(ConfigUpdatePacket.applyConfig("building.package.oriental", "false"));
        assertFalse(Config.isPackageEnabled("oriental"));

        // Enable via packet apply
        assertTrue(ConfigUpdatePacket.applyConfig("building.package.oriental", "true"));
        assertTrue(Config.isPackageEnabled("oriental"));

        // Bulk disable via disabledPackages
        assertTrue(ConfigUpdatePacket.applyConfig("building.disabledPackages", "oriental,steampunk"));
        assertFalse(Config.isPackageEnabled("oriental"));
        assertFalse(Config.isPackageEnabled("steampunk"));
        assertTrue(Config.isPackageEnabled("default"));

        // Reset
        assertTrue(ConfigUpdatePacket.applyConfig("building.disabledPackages", ""));
        assertTrue(Config.isPackageEnabled("oriental"));
        assertTrue(Config.isPackageEnabled("steampunk"));
    }

    @Test
    void testSettingsRegistryPackagesTab() {
        Config.setDisabledPackages(List.of());
        BuildingConfigLoader loader = BuildingConfigLoader.getInstance();
        loader.registerPackage(new BuildingPackage("oriental", "东风竹韵包", "东方风格建筑", "Wandscape", "1.0.0", "minecraft:bamboo", 50, List.of()));

        List<SettingItem> items = SettingsRegistry.getItems(SettingTab.PACKAGES);
        assertNotNull(items);
        assertFalse(items.isEmpty());

        SettingItem orientalItem = items.stream()
                .filter(it -> it.key().equals("building.package.oriental"))
                .findFirst()
                .orElse(null);
        assertNotNull(orientalItem);
        assertEquals(SettingItem.Type.BOOLEAN, orientalItem.type());

        SettingItem.BooleanSetting bs = (SettingItem.BooleanSetting) orientalItem;
        assertTrue(bs.get());

        // Toggle off
        bs.set(false);
        assertFalse(Config.isPackageEnabled("oriental"));
        assertFalse(bs.get());
        assertEquals("已关闭", bs.formatValue());

        // Reset tab
        SettingsRegistry.resetTab(SettingTab.PACKAGES);
        assertTrue(Config.isPackageEnabled("oriental"));
        assertTrue(bs.get());
    }

    @Test
    void testLoadOrientalPackageAndBuildings() {
        BuildingConfigLoader loader = BuildingConfigLoader.getInstance();

        // 1. Load oriental package metadata
        JsonObject pkgMetaJson = new JsonObject();
        pkgMetaJson.addProperty("id", "oriental");
        pkgMetaJson.addProperty("name", "wandscape.pack.oriental.name");
        pkgMetaJson.addProperty("description", "wandscape.pack.oriental.desc");
        pkgMetaJson.addProperty("author", "Wandscape");
        pkgMetaJson.addProperty("icon", "minecraft:bamboo");
        pkgMetaJson.addProperty("priority", 50);
        loader.loadFromDataPath("oriental/package", pkgMetaJson);

        BuildingPackage orientalPkg = loader.getPackage("oriental");
        assertNotNull(orientalPkg);
        assertEquals("oriental", orientalPkg.id());
        assertEquals("wandscape.pack.oriental.name", orientalPkg.name());
        assertEquals(50, orientalPkg.priority());

        // 2. Load oriental building: e.g. bamboo_hall
        JsonObject buildingJson = new JsonObject();
        buildingJson.addProperty("id", "bamboo_hall");
        buildingJson.addProperty("display_name", "Bamboo Hall");
        buildingJson.addProperty("category", "basic");
        com.google.gson.JsonArray palette = new com.google.gson.JsonArray();
        palette.add("minecraft:bamboo_planks");
        buildingJson.add("palette", palette);
        buildingJson.add("block_indices", new com.google.gson.JsonArray());
        buildingJson.add("pattern", new com.google.gson.JsonArray());

        loader.loadFromDataPath("oriental/bamboo_hall", buildingJson);

        // Verify full ID and package ID
        BuildingConfig config = loader.get("oriental:bamboo_hall");
        assertNotNull(config);
        assertEquals("oriental:bamboo_hall", config.id());
        assertEquals("oriental", config.packageId());

        // Verify alias lookup
        BuildingConfig byAlias = loader.get("bamboo_hall");
        assertNotNull(byAlias);
        assertSame(config, byAlias);

        // Verify package configs list
        List<BuildingConfig> orientalList = loader.getConfigsByPackage("oriental");
        assertEquals(1, orientalList.size());
        assertEquals("oriental:bamboo_hall", orientalList.get(0).id());
    }
}
