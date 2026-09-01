package com.wsteam.wandscape.production.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.content.element.internal.ElementMaps;
import com.wsteam.wandscape.shared.data.ElementType;
import net.minecraft.nbt.CompoundTag;

import java.util.Map;
public record CraftWandRecipe(
    String id,
    String craftStation,
    String displayName,
    String outputItem,
    CompoundTag outputNbt,
    Map<ElementType, Long> cost,
    RecipeUnlockRequirement unlockRequirement
) {
    public static CraftWandRecipe fromJson(String id, JsonElement json) {
        JsonObject obj = json.getAsJsonObject();

        String craftStation = obj.has("craft_station")
                ? obj.get("craft_station").getAsString() : "crafting_station";
        String displayName = obj.has("display_name")
                ? obj.get("display_name").getAsString() : id;

        String outputItem = obj.getAsJsonObject("output").get("item").getAsString();

        CompoundTag nbt = new CompoundTag();
        nbt.putString("preset_id", id);
        if (obj.has("wand_color")) {
            nbt.putString("wand_color", obj.get("wand_color").getAsString());
        }

        Map<ElementType, Long> cost = ElementMaps.parse(obj, "cost");

        RecipeUnlockRequirement req = obj.has("unlock_requirement")
                ? RecipeUnlockRequirement.fromJson(obj.getAsJsonObject("unlock_requirement"))
                : RecipeUnlockRequirement.NONE;

        return new CraftWandRecipe(id, craftStation, displayName, outputItem, nbt, cost, req);
    }
}
