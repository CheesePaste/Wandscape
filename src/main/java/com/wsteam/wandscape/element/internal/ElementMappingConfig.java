package com.wsteam.wandscape.element.internal;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.production.data.RecipeUnlockRequirement;
import com.wsteam.wandscape.shared.data.ElementType;
public record ElementMappingConfig(
    @Nullable String blockId,
    @Nullable String itemId,
    Map<ElementType, Long> buildCost,
    Map<ElementType, Long> decomposeYield,
    boolean decomposable,
    @Nullable SynthesizeMeta synthesize,
    boolean disabled
) {
    static ElementMappingConfig fromJson(String id, JsonElement json) {
        JsonObject obj = json.getAsJsonObject();
        String blockId = obj.has("block") ? obj.get("block").getAsString() : null;
        String itemId = obj.has("item") ? obj.get("item").getAsString() : null;

        Map<ElementType, Long> buildCost = parseElementMap(obj, "build_cost");
        Map<ElementType, Long> decomposeYield = parseElementMap(obj, "decompose_yield");
        boolean decomposable = obj.has("decomposable") && obj.get("decomposable").getAsBoolean();
        boolean disabled = obj.has("disabled") && obj.get("disabled").getAsBoolean();

        SynthesizeMeta synthesize = null;
        if (obj.has("synthesize")) {
            synthesize = SynthesizeMeta.fromJson(obj.getAsJsonObject("synthesize"));
        }

        return new ElementMappingConfig(blockId, itemId, buildCost, decomposeYield, decomposable, synthesize, disabled);
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

    public record SynthesizeMeta(
        RecipeUnlockRequirement unlockRequirement
    ) {
        public static SynthesizeMeta fromJson(JsonObject obj) {
            RecipeUnlockRequirement req = obj.has("unlock_requirement")
                    ? RecipeUnlockRequirement.fromJson(obj.getAsJsonObject("unlock_requirement"))
                    : RecipeUnlockRequirement.NONE;

            return new SynthesizeMeta(req);
        }
    }
}
