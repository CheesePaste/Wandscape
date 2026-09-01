package com.wsteam.wandscape.production.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.content.element.internal.ElementMaps;
import com.wsteam.wandscape.shared.data.ElementType;

import java.util.Map;

/**
 * 合成站「杂项物品」配方（{@code data/wandscape/craft_recipes/*.json}，JSON {@code type=="misc"}）。
 *
 * <p>产物是独立注册物品（{@code wandscape:peace_wand} / {@code wandscape:magic_compass} /
 * {@code wandscape:warehouse_terminal} / {@code wandscape:oath_ring} 等），不带 preset 属性 NBT——
 * 是右键行为法器/功能物品，不是属性法杖。同构于法杖 {@code type=="wand"} 但产物无 preset。合成站按
 * 配方 {@code craft_station} + {@code min_colony_level} 展示/解锁。
 */
public record MiscRecipe(
    String id,
    String craftStation,
    String displayName,
    String outputItem,
    Map<ElementType, Long> cost,
    RecipeUnlockRequirement unlockRequirement
) {
    public static MiscRecipe fromJson(String id, JsonElement json) {
        JsonObject obj = json.getAsJsonObject();

        String craftStation = obj.has("craft_station")
                ? obj.get("craft_station").getAsString() : "crafting_station";
        String displayName = obj.has("display_name")
                ? obj.get("display_name").getAsString() : id;

        JsonObject output = obj.getAsJsonObject("output");
        String outputItem = output.get("item").getAsString();

        Map<ElementType, Long> cost = ElementMaps.parse(obj, "cost");

        RecipeUnlockRequirement req = obj.has("unlock_requirement")
                ? RecipeUnlockRequirement.fromJson(obj.getAsJsonObject("unlock_requirement"))
                : RecipeUnlockRequirement.NONE;

        return new MiscRecipe(id, craftStation, displayName, outputItem, cost, req);
    }
}
