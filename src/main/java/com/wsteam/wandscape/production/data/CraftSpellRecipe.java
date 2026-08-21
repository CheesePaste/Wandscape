package com.wsteam.wandscape.production.data;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.shared.data.ElementType;

/**
 * 魔法卷轴合成配方（{@code data/wandscape/craft_recipes/*.json}，JSON {@code type=="spell"}）。
 *
 * <p>产出 {@code wandscape:spell_scroll} 并绑定 {@code magic_id}（写入 CUSTOM_DATA），
 * 只覆盖四类战斗魔法——teleport（导航回退）/revive（祭坛专属）属 UTILITY，不做成物品。
 */
public record CraftSpellRecipe(
    String id,
    String craftStation,
    String displayName,
    String outputItem,
    String magicId,
    Map<ElementType, Long> cost,
    RecipeUnlockRequirement unlockRequirement
) {
    public static CraftSpellRecipe fromJson(String id, JsonElement json) {
        JsonObject obj = json.getAsJsonObject();

        String craftStation = obj.has("craft_station")
                ? obj.get("craft_station").getAsString() : "magic_station";
        String displayName = obj.has("display_name")
                ? obj.get("display_name").getAsString() : id;

        JsonObject output = obj.getAsJsonObject("output");
        String outputItem = output.get("item").getAsString();
        String magicId = output.has("magic_id")
                ? output.get("magic_id").getAsString() : id;

        Map<ElementType, Long> cost = parseElementMap(obj, "cost");

        RecipeUnlockRequirement req = obj.has("unlock_requirement")
                ? RecipeUnlockRequirement.fromJson(obj.getAsJsonObject("unlock_requirement"))
                : RecipeUnlockRequirement.NONE;

        return new CraftSpellRecipe(id, craftStation, displayName, outputItem, magicId, cost, req);
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