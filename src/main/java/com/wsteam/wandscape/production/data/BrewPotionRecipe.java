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
    String craftStation,
    String outputItem,
    Map<ElementType, Long> cost,
    List<String> inputItems,
    RecipeUnlockRequirement unlockRequirement
) {
    public static BrewPotionRecipe fromJson(String id, JsonElement json) {
        JsonObject obj = json.getAsJsonObject();

        String craftStation = obj.has("craft_station")
                ? obj.get("craft_station").getAsString() : "potion_station";

        String outputItem = obj.getAsJsonObject("output").get("item").getAsString();
        Map<ElementType, Long> cost = parseElementMap(obj, "cost");
        List<String> inputItems = parseInputItems(obj);

        RecipeUnlockRequirement req = obj.has("unlock_requirement")
                ? RecipeUnlockRequirement.fromJson(obj.getAsJsonObject("unlock_requirement"))
                : RecipeUnlockRequirement.NONE;

        return new BrewPotionRecipe(id, craftStation, outputItem, cost, inputItems, req);
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
