package com.wsteam.wandscape.compat.curios;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 Curios 饰品标签 JSON 格式正确、且包含预期的 Wandscape 物品。
 */
class CuriosTagsValidationTest {

    @Test
    void ringTagContainsAllOathRings() {
        JsonObject json = loadTagJson("ring");
        JsonArray values = json.getAsJsonArray("values");
        List<String> items = values.asList().stream().map(e -> e.getAsString()).toList();
        assertTrue(items.contains("wandscape:oath_ring"));
        assertTrue(items.contains("wandscape:oath_ring_mid"));
        assertTrue(items.contains("wandscape:oath_ring_high"));
    }

    @Test
    void charmTagContainsAllMagicCompasses() {
        JsonObject json = loadTagJson("charm");
        JsonArray values = json.getAsJsonArray("values");
        List<String> items = values.asList().stream().map(e -> e.getAsString()).toList();
        assertTrue(items.contains("wandscape:magic_compass"));
        assertTrue(items.contains("wandscape:advanced_magic_compass"));
        assertTrue(items.contains("wandscape:ultimate_magic_compass"));
    }

    @Test
    void handsAndBraceletTagsContainWarehouseTerminal() {
        JsonObject handsJson = loadTagJson("hands");
        JsonArray handsValues = handsJson.getAsJsonArray("values");
        List<String> handsItems = handsValues.asList().stream().map(e -> e.getAsString()).toList();
        assertTrue(handsItems.contains("wandscape:warehouse_terminal"));

        JsonObject braceletJson = loadTagJson("bracelet");
        JsonArray braceletValues = braceletJson.getAsJsonArray("values");
        List<String> braceletItems = braceletValues.asList().stream().map(e -> e.getAsString()).toList();
        assertTrue(braceletItems.contains("wandscape:warehouse_terminal"));
    }

    @Test
    void curiosCompatGracefullyDegradesWhenNotLoaded() {
        assertFalse(CuriosCompat.isLoaded());
        assertFalse(CuriosCompat.isEquipped(null, (net.minecraft.world.item.Item) null));
    }

    private JsonObject loadTagJson(String tagName) {
        String path = "/data/curios/tags/item/" + tagName + ".json";
        InputStream stream = getClass().getResourceAsStream(path);
        assertNotNull(stream, "Tag file not found: " + path);
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            assertNotNull(obj);
            assertTrue(obj.has("values"));
            return obj;
        } catch (Exception e) {
            throw new RuntimeException("Failed to read tag " + tagName, e);
        }
    }
}
