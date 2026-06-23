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
    String outputItem,
    CompoundTag outputNbt,
    Map<ElementType, Long> cost,
    RecipeUnlockRequirement unlockRequirement,
    @Nullable Map<String, Integer> wandLevel
) {
    public static CraftWandRecipe fromJson(String id, JsonElement json) {
        JsonObject obj = json.getAsJsonObject();
        JsonObject output = obj.getAsJsonObject("output");
        String outputItem = output.get("item").getAsString();
        CompoundTag nbt = parseNbt(output);
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

        return new CraftWandRecipe(id, outputItem, nbt, cost, req, wandLevel);
    }

    private static CompoundTag parseNbt(JsonObject output) {
        CompoundTag tag = new CompoundTag();
        if (!output.has("nbt")) return tag;
        JsonObject nbtObj = output.getAsJsonObject("nbt");
        for (var entry : nbtObj.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive()) {
                var prim = value.getAsJsonPrimitive();
                if (prim.isString()) {
                    tag.putString(key, prim.getAsString());
                } else if (prim.isNumber()) {
                    Number num = prim.getAsNumber();
                    if (num.doubleValue() == num.longValue()) {
                        tag.putLong(key, num.longValue());
                    } else {
                        tag.putDouble(key, num.doubleValue());
                    }
                } else if (prim.isBoolean()) {
                    tag.putBoolean(key, prim.getAsBoolean());
                }
            } else if (value.isJsonObject()) {
                tag.put(key, parseNbtObject(value.getAsJsonObject()));
            }
        }
        return tag;
    }

    private static CompoundTag parseNbtObject(JsonObject obj) {
        CompoundTag tag = new CompoundTag();
        for (var entry : obj.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive()) {
                var prim = value.getAsJsonPrimitive();
                if (prim.isNumber()) {
                    tag.putInt(key, prim.getAsInt());
                } else if (prim.isString()) {
                    tag.putString(key, prim.getAsString());
                }
            } else if (value.isJsonObject()) {
                tag.put(key, parseNbtObject(value.getAsJsonObject()));
            }
        }
        return tag;
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
