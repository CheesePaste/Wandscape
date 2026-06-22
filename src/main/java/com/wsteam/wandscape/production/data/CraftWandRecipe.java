package com.wsteam.wandscape.production.data;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.shared.data.ElementType;

import net.minecraft.nbt.CompoundTag;

public record CraftWandRecipe(
    String id,
    String outputItem,
    CompoundTag outputNbt,
    Map<ElementType, Long> cost,
    int requiredLevel,
    int unlockMagicValue
) {
    public static CraftWandRecipe fromJson(String id, JsonElement json) {
        JsonObject obj = json.getAsJsonObject();
        JsonObject output = obj.getAsJsonObject("output");
        String outputItem = output.get("item").getAsString();
        CompoundTag nbt = parseNbt(output);
        Map<ElementType, Long> cost = parseElementMap(obj, "cost");
        int requiredLevel = obj.has("required_level") ? obj.get("required_level").getAsInt() : 1;
        int unlockMagicValue = obj.has("unlock_magic_value") ? obj.get("unlock_magic_value").getAsInt() : 0;
        return new CraftWandRecipe(id, outputItem, nbt, cost, requiredLevel, unlockMagicValue);
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
