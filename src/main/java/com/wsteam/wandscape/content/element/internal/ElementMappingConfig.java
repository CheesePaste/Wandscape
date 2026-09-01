package com.wsteam.wandscape.content.element.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.shared.data.ElementType;

import javax.annotation.Nullable;
import java.util.Map;
public record ElementMappingConfig(
    @Nullable String blockId,
    @Nullable String itemId,
    Map<ElementType, Long> buildCost,
    boolean disabled
) {
    static ElementMappingConfig fromJson(String id, JsonElement json) {
        JsonObject obj = json.getAsJsonObject();
        String blockId = obj.has("block") ? obj.get("block").getAsString() : null;
        String itemId = obj.has("item") ? obj.get("item").getAsString() : null;

        Map<ElementType, Long> buildCost = ElementMaps.parse(obj, "build_cost");
        boolean disabled = obj.has("disabled") && obj.get("disabled").getAsBoolean();

        return new ElementMappingConfig(blockId, itemId, buildCost, disabled);
    }
}
