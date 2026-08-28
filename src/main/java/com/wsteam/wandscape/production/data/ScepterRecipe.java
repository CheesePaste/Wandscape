package com.wsteam.wandscape.production.data;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.shared.data.ElementType;

/**
 * 玩家权杖合成配方（{@code data/wandscape/craft_recipes/*.json}，JSON {@code type=="scepter"}）。
 *
 * <p>与法杖 {@code type=="wand"} 同构但产物是独立注册物品（{@code wandscape:peace_wand} 等），
 * 不携带 preset 属性/NBT——权杖是右键行为法器，不是属性法杖。合成站 1 级解锁。
 */
public record ScepterRecipe(
    String id,
    String craftStation,
    String displayName,
    String outputItem,
    Map<ElementType, Long> cost,
    RecipeUnlockRequirement unlockRequirement
) {
    public static ScepterRecipe fromJson(String id, JsonElement json) {
        JsonObject obj = json.getAsJsonObject();

        String craftStation = obj.has("craft_station")
                ? obj.get("craft_station").getAsString() : "crafting_station";
        String displayName = obj.has("display_name")
                ? obj.get("display_name").getAsString() : id;

        JsonObject output = obj.getAsJsonObject("output");
        String outputItem = output.get("item").getAsString();

        Map<ElementType, Long> cost = parseElementMap(obj, "cost");

        RecipeUnlockRequirement req = obj.has("unlock_requirement")
                ? RecipeUnlockRequirement.fromJson(obj.getAsJsonObject("unlock_requirement"))
                : RecipeUnlockRequirement.NONE;

        return new ScepterRecipe(id, craftStation, displayName, outputItem, cost, req);
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