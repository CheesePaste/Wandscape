package com.wsteam.wandscape.production.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.shared.data.ElementType;

public record BrewPotionRecipe(
    String id,
    String outputItem,
    Map<ElementType, Long> cost,
    List<String> inputItems,
    int requiredLevel,
    int unlockMagicValue
) {
    public static BrewPotionRecipe fromJson(String id, JsonElement json) {
        JsonObject obj = json.getAsJsonObject();
        String outputItem = obj.getAsJsonObject("output").get("item").getAsString();
        Map<ElementType, Long> cost = parseElementMap(obj, "cost");
        List<String> inputItems = parseInputItems(obj);
        int requiredLevel = obj.has("required_level") ? obj.get("required_level").getAsInt() : 1;
        int unlockMagicValue = obj.has("unlock_magic_value") ? obj.get("unlock_magic_value").getAsInt() : 0;
        return new BrewPotionRecipe(id, outputItem, cost, inputItems, requiredLevel, unlockMagicValue);
    }

    private static List<String> parseInputItems(JsonObject obj) {
        List<String> items = new ArrayList<>();
        if (!obj.has("input_items")) return items;
        for (JsonElement e : obj.getAsJsonArray("input_items")) {
            items.add(e.getAsString());
        }
        return items;
    }

    private static Map<ElementType, Long> parseElementMap(JsonObject obj, String key) {
        Map<ElementType, Long> map = new HashMap<>();
        if (!obj.has(key)) return map;
        JsonObject costObj = obj.getAsJsonObject(key);
        for (var entry : costObj.entrySet()) {
            ElementType type = ElementType.valueOf(entry.getKey().toUpperCase());
            map.put(type, entry.getValue().getAsLong());
        }
        return map;
    }
}
