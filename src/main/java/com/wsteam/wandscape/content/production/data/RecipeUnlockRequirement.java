package com.wsteam.wandscape.content.production.data;

import com.google.gson.JsonObject;
/**
 * Unlock requirement for a production recipe, expressed as a minimum colony level.
 *
 * <p>A recipe is unlocked when the colony's level meets or exceeds the minimum.
 * The default minimum is 1, meaning available from the start.
 */
public record RecipeUnlockRequirement(
        int minColonyLevel
) {
    public static final RecipeUnlockRequirement NONE = new RecipeUnlockRequirement(1);

    public static RecipeUnlockRequirement fromJson(JsonObject obj) {
        int level = obj.has("min_colony_level") ? obj.get("min_colony_level").getAsInt() : 1;
        return new RecipeUnlockRequirement(level);
    }
}
