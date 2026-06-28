package com.wsteam.wandscape.production.data;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.element.internal.ElementMappingConfig;
import com.wsteam.wandscape.shared.data.ElementType;
public record SynthesizeRecipe(
    String id,
    String outputItem,
    Map<ElementType, Long> cost,
    RecipeUnlockRequirement unlockRequirement,
    @Nullable Map<String, Integer> wandLevel
) {
    /** Build a SynthesizeRecipe from an ElementMappingConfig that has synthesize metadata. */
    public static SynthesizeRecipe fromElementMapping(ElementMappingConfig config) {
        String id = config.itemId() != null ? config.itemId() : config.blockId();
        String outputItem = config.itemId() != null ? config.itemId() : config.blockId();
        var meta = config.synthesize();
        return new SynthesizeRecipe(
            id,
            outputItem,
            config.buildCost(),
            meta != null ? meta.unlockRequirement() : RecipeUnlockRequirement.NONE,
            meta != null ? meta.wandLevel() : null
        );
    }

    public static SynthesizeRecipe fromJson(String id, JsonElement json) {
        JsonObject obj = json.getAsJsonObject();
        String outputItem = obj.getAsJsonObject("output").get("item").getAsString();
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

        return new SynthesizeRecipe(id, outputItem, cost, req, wandLevel);
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
