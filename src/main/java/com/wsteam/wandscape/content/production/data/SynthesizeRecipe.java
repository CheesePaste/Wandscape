package com.wsteam.wandscape.content.production.data;
import com.wsteam.wandscape.foundation.registry.WandscapeConstants;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.content.element.internal.ElementMappingConfig;
import com.wsteam.wandscape.content.element.internal.ElementMaps;
import com.wsteam.wandscape.content.element.data.ElementType;

import java.util.Map;
public record SynthesizeRecipe(
    String id,
    String outputItem,
    Map<ElementType, Long> cost,
    RecipeUnlockRequirement unlockRequirement
) {
    /** Total element cost / value of 1 output item. */
    public long totalCost() {
        if (cost == null || cost.isEmpty()) return 0L;
        long sum = 0L;
        for (Long v : cost.values()) {
            if (v != null) sum += v;
        }
        return sum;
    }

    /**
     * Compute channel ticks for synthesize task.
     * Items with total element value <= 2 are synthesized instantly (0 ticks).
     * Higher-tier items take WORKSTATION_CRAFT_TICKS_PER_UNIT (5 ticks) per unit.
     */
    public int calculateChannelTicks(int quantity) {
        if (totalCost() <= 2) {
            return 0; // 价值 <= 2 秒合成
        }
        return com.wsteam.wandscape.foundation.registry.WandscapeConstants.WORKSTATION_CRAFT_TICKS_PER_UNIT * quantity;
    }

    /** Build a SynthesizeRecipe from an ElementMappingConfig that has a non-empty build cost. */
    public static SynthesizeRecipe fromElementMapping(ElementMappingConfig config) {
        String id = config.itemId() != null ? config.itemId() : config.blockId();
        String outputItem = config.itemId() != null ? config.itemId() : config.blockId();
        return new SynthesizeRecipe(
            id,
            outputItem,
            config.buildCost(),
            RecipeUnlockRequirement.NONE
        );
    }

    public static SynthesizeRecipe fromJson(String id, JsonElement json) {
        JsonObject obj = json.getAsJsonObject();
        String outputItem = obj.getAsJsonObject("output").get("item").getAsString();
        Map<ElementType, Long> cost = ElementMaps.parse(obj, "cost");

        RecipeUnlockRequirement req = obj.has("unlock_requirement")
                ? RecipeUnlockRequirement.fromJson(obj.getAsJsonObject("unlock_requirement"))
                : RecipeUnlockRequirement.NONE;

        return new SynthesizeRecipe(id, outputItem, cost, req);
    }
}
