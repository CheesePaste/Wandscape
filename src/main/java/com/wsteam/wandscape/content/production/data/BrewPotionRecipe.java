package com.wsteam.wandscape.content.production.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.content.element.internal.ElementMaps;
import com.wsteam.wandscape.content.element.data.ElementType;
import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record BrewPotionRecipe(
    String id,
    String craftStation,
    String outputItem,
    CompoundTag outputNbt,
    Map<ElementType, Long> cost,
    List<String> inputItems,
    RecipeUnlockRequirement unlockRequirement
) {
    public BrewPotionRecipe(
            String id,
            String craftStation,
            String outputItem,
            Map<ElementType, Long> cost,
            List<String> inputItems,
            RecipeUnlockRequirement unlockRequirement
    ) {
        this(id, craftStation, outputItem, null, cost, inputItems, unlockRequirement);
    }

    public static BrewPotionRecipe fromJson(String id, JsonElement json) {
        JsonObject obj = json.getAsJsonObject();

        String craftStation = obj.has("craft_station")
                ? obj.get("craft_station").getAsString() : "crafting_station";

        JsonObject outputObj = obj.getAsJsonObject("output");
        String outputItem = outputObj.get("item").getAsString();

        CompoundTag outputNbt = null;
        if (outputObj.has("nbt") && outputObj.get("nbt").isJsonObject()) {
            outputNbt = parseNbt(outputObj.getAsJsonObject("nbt"));
        } else if (obj.has("output_nbt") && obj.get("output_nbt").isJsonObject()) {
            outputNbt = parseNbt(obj.getAsJsonObject("output_nbt"));
        }

        Map<ElementType, Long> cost = ElementMaps.parse(obj, "cost");
        List<String> inputItems = parseInputItems(obj);

        RecipeUnlockRequirement req = obj.has("unlock_requirement")
                ? RecipeUnlockRequirement.fromJson(obj.getAsJsonObject("unlock_requirement"))
                : RecipeUnlockRequirement.NONE;

        return new BrewPotionRecipe(id, craftStation, outputItem, outputNbt, cost, inputItems, req);
    }

    public static CompoundTag parseNbt(JsonObject nbtObj) {
        if (nbtObj == null) return null;
        CompoundTag tag = new CompoundTag();
        for (var entry : nbtObj.entrySet()) {
            JsonElement val = entry.getValue();
            if (val.isJsonPrimitive()) {
                var prim = val.getAsJsonPrimitive();
                if (prim.isString()) {
                    tag.putString(entry.getKey(), prim.getAsString());
                } else if (prim.isBoolean()) {
                    tag.putBoolean(entry.getKey(), prim.getAsBoolean());
                } else if (prim.isNumber()) {
                    Number num = prim.getAsNumber();
                    if (num.doubleValue() == (double) num.longValue()) {
                        tag.putLong(entry.getKey(), num.longValue());
                    } else {
                        tag.putDouble(entry.getKey(), num.doubleValue());
                    }
                }
            } else if (val.isJsonObject()) {
                CompoundTag child = parseNbt(val.getAsJsonObject());
                if (child != null) tag.put(entry.getKey(), child);
            }
        }
        return tag;
    }

    private static List<String> parseInputItems(JsonObject obj) {
        List<String> items = new ArrayList<>();
        if (!obj.has("input_items")) return items;
        for (JsonElement e : obj.getAsJsonArray("input_items")) {
            items.add(e.getAsString());
        }
        return items;
    }
}
