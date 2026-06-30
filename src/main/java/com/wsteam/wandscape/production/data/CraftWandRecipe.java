package com.wsteam.wandscape.production.data;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.shared.data.ElementType;

import net.minecraft.nbt.CompoundTag;
public record CraftWandRecipe(
    String id,
    String craftStation,
    String displayName,
    String outputItem,
    CompoundTag outputNbt,
    Map<ElementType, Long> cost,
    RecipeUnlockRequirement unlockRequirement,
    @Nullable Map<String, Integer> wandLevel
) {
    public static CraftWandRecipe fromJson(String id, JsonElement json) {
        JsonObject obj = json.getAsJsonObject();

        String craftStation = obj.has("craft_station")
                ? obj.get("craft_station").getAsString() : "crafting_station";
        String displayName = obj.has("display_name")
                ? obj.get("display_name").getAsString() : id;

        String outputItem = obj.getAsJsonObject("output").get("item").getAsString();

        CompoundTag nbt = new CompoundTag();
        if (obj.has("wand_color")) {
            nbt.putString("wand_color", obj.get("wand_color").getAsString());
        }
        if (obj.has("behaviors")) {
            CompoundTag behaviors = new CompoundTag();
            JsonObject btObj = obj.getAsJsonObject("behaviors");
            for (var entry : btObj.entrySet()) {
                behaviors.putInt(entry.getKey(), entry.getValue().getAsInt());
            }
            nbt.put("behaviors", behaviors);
        }
        if (obj.has("range")) {
            nbt.putInt("range", obj.get("range").getAsInt());
        }
        if (obj.has("mana_cost_multiplier")) {
            nbt.putFloat("mana_cost_multiplier", obj.get("mana_cost_multiplier").getAsFloat());
        }

        Map<ElementType, Long> cost = parseElementMap(obj, "cost");

        RecipeUnlockRequirement req = obj.has("unlock_requirement")
                ? RecipeUnlockRequirement.fromJson(obj.getAsJsonObject("unlock_requirement"))
                : RecipeUnlockRequirement.NONE;

        Map<String, Integer> wandLevel = null;
        if (obj.has("wand_level")) {
            JsonObject wl = obj.getAsJsonObject("wand_level");
            wandLevel = new HashMap<>();
            for (var e : wl.entrySet()) wandLevel.put(e.getKey(), e.getValue().getAsInt());
        }

        return new CraftWandRecipe(id, craftStation, displayName, outputItem, nbt, cost, req, wandLevel);
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
