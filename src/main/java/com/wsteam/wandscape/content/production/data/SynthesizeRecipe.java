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
    /**
     * 合成 channel 时长：每件统一 WORKSTATION_CRAFT_TICKS_PER_UNIT（默认 2 tick/件，可被 BalanceValues
     * 覆盖）；取消低价值物品 0 tick 秒合成的特判，合成与分解一律按件计 channel。
     */
    public int calculateChannelTicks(int quantity) {
        return com.wsteam.wandscape.foundation.util.BalanceValues.workstationCraftTicksPerUnit() * quantity;
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
