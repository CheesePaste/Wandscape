package com.wsteam.wandscape.element.internal;

import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.shared.data.ElementType;

record ElementMappingConfig(
    String blockId,
    Map<ElementType, Long> buildCost,
    Map<ElementType, Long> decomposeYield,
    boolean decomposable
) {
    static ElementMappingConfig fromJson(String id, JsonElement json) {
        JsonObject obj = json.getAsJsonObject();
        String blockId = obj.get("block").getAsString();

        Map<ElementType, Long> buildCost = parseElementMap(obj, "build_cost");
        Map<ElementType, Long> decomposeYield = parseElementMap(obj, "decompose_yield");
        boolean decomposable = obj.has("decomposable") && obj.get("decomposable").getAsBoolean();

        return new ElementMappingConfig(blockId, buildCost, decomposeYield, decomposable);
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
