package com.wsteam.wandscape.production.data;

import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Unlock requirement for a production recipe, expressed as minimum thresholds
 * for the three colony evaluation values.
 *
 * <p>A recipe is unlocked when the colony's comfort / magic / wonder all meet
 * or exceed their respective minima.  All three must be satisfied simultaneously.
 * If only one dimension matters, set the other two to 0.
 */
public record RecipeUnlockRequirement(
        int minComfort,
        int minMagic,
        int minWonder
) {
    public static final RecipeUnlockRequirement NONE = new RecipeUnlockRequirement(0, 0, 0);

    public static RecipeUnlockRequirement fromJson(JsonObject obj) {
        int mc = obj.has("min_comfort") ? obj.get("min_comfort").getAsInt() : 0;
        int mm = obj.has("min_magic")   ? obj.get("min_magic").getAsInt()   : 0;
        int mw = obj.has("min_wonder")  ? obj.get("min_wonder").getAsInt()  : 0;
        return new RecipeUnlockRequirement(mc, mm, mw);
    }
}
