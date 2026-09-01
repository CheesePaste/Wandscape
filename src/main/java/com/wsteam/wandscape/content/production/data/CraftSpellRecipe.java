package com.wsteam.wandscape.content.production.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.content.element.internal.ElementMaps;
import com.wsteam.wandscape.shared.data.ElementType;

import java.util.Map;

/**
 * 魔法卷轴合成配方（{@code data/wandscape/craft_recipes/*.json}，JSON {@code type=="spell"}）。
 *
 * <p>产出 {@code wandscape:spell_scroll} 并绑定 {@code magic_id}（写入 CUSTOM_DATA），
 * 覆盖四类战斗魔法 + 特殊魔法（heal/teleport）——revive（祭坛专属，ALTAR）不物品化。
 */
public record CraftSpellRecipe(
    String id,
    String craftStation,
    String displayName,
    String outputItem,
    String magicId,
    Map<ElementType, Long> cost,
    RecipeUnlockRequirement unlockRequirement
) {
    public static CraftSpellRecipe fromJson(String id, JsonElement json) {
        JsonObject obj = json.getAsJsonObject();

        String craftStation = obj.has("craft_station")
                ? obj.get("craft_station").getAsString() : "magic_station";
        String displayName = obj.has("display_name")
                ? obj.get("display_name").getAsString() : id;

        JsonObject output = obj.getAsJsonObject("output");
        String outputItem = output.get("item").getAsString();
        String magicId = output.has("magic_id")
                ? output.get("magic_id").getAsString() : id;

        Map<ElementType, Long> cost = ElementMaps.parse(obj, "cost");

        RecipeUnlockRequirement req = obj.has("unlock_requirement")
                ? RecipeUnlockRequirement.fromJson(obj.getAsJsonObject("unlock_requirement"))
                : RecipeUnlockRequirement.NONE;

        return new CraftSpellRecipe(id, craftStation, displayName, outputItem, magicId, cost, req);
    }
}