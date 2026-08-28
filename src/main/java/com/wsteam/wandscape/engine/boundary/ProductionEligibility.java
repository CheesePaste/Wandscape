package com.wsteam.wandscape.engine.boundary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.production.ProductionRecipeLoader;
import com.wsteam.wandscape.production.data.CraftRecipeView;
import com.wsteam.wandscape.production.data.CraftSpellRecipe;
import com.wsteam.wandscape.production.data.SynthesizeRecipe;
import com.wsteam.wandscape.shared.data.ElementType;

/**
 * Shared affordability decision for production WorkItems. All three places that
 * need to know "does this queued craft task have enough elements" — the
 * publish-time scan ({@link BuildingTaskSource}), the queue-panel UI marker
 * ({@link com.wsteam.wandscape.building.network.TaskQueueModifyPacket}) and the
 * auto-supply scan ({@link com.wsteam.wandscape.engine.system.ResourceSupplySystem})
 * — use this class so they always agree.
 */
public final class ProductionEligibility {

    private ProductionEligibility() {
    }

    /** Recipe loader, wired at mod init (null → element-costing tasks are treated as short). */
    @javax.annotation.Nullable
    private static ProductionRecipeLoader recipeLoader;

    public static void setProductionRecipeLoader(@javax.annotation.Nullable ProductionRecipeLoader loader) {
        recipeLoader = loader;
    }

    /** Blueprints that consume elements (synthesize / craft crafting-station / craft_spell magic-station). */
    public static boolean isElementCosting(String blueprintId) {
        if (blueprintId == null) return false;
        return switch (blueprintId) {
            case "production:synthesize", "production:craft",
                 "production:craft_spell" -> true;
            default -> false;
        };
    }

    /** Element requirements of a production task with JSON params (a WorkItem). */
    public static Map<ElementType, Long> requiredElements(String blueprintId, Map<String, JsonElement> params) {
        return requiredElements(blueprintId, paramStr(params, "recipe_id"), paramInt(params, "count", 1));
    }

    /** Element requirements of a production task with string params (a compiled BlockInteractOp). */
    public static Map<ElementType, Long> requiredElementsFromStrings(String blueprintId, Map<String, String> params) {
        return requiredElements(blueprintId, paramStrOp(params, "recipe_id"), paramIntOp(params, "count", 1));
    }

    /**
     * Total elements required for {@code count} units: recipe unit cost × count
     * × the config craft-cost multiplier, ceil-rounded per element so we never
     * under-pay. Empty map for non-element blueprints or unknown recipes.
     */
    private static Map<ElementType, Long> requiredElements(String blueprintId, @Nullable String recipeId, int count) {
        Map<ElementType, Long> unitCost = recipeCost(blueprintId, recipeId);
        if (unitCost.isEmpty() || count <= 0) return unitCost;

        double multiplier = Config.ELEMENT_CRAFT_COST_MULTIPLIER.get();
        Map<ElementType, Long> required = new LinkedHashMap<>();
        for (var e : unitCost.entrySet()) {
            long scaled = (long) Math.ceil(e.getValue() * count * multiplier);
            if (scaled > 0) required.put(e.getKey(), scaled);
        }
        return required;
    }

    /** Element unit cost of a production recipe, or empty for non-element blueprints / unknown recipes. */
    private static Map<ElementType, Long> recipeCost(String blueprintId, @Nullable String recipeId) {
        if (recipeId == null) return Map.of();
        ProductionRecipeLoader loader = recipeLoader;
        if (loader == null) return Map.of();
        return switch (blueprintId) {
            case "production:synthesize" -> {
                SynthesizeRecipe r = loader.getSynthesizeRecipe(recipeId);
                yield r != null ? r.cost() : Map.of();
            }
            case "production:craft" -> {
                CraftRecipeView r = CraftRecipeView.resolve(loader, recipeId);
                yield r != null ? r.cost() : Map.of();
            }
            case "production:craft_spell" -> {
                CraftSpellRecipe r = loader.getSpellRecipes().get(recipeId);
                yield r != null ? r.cost() : Map.of();
            }
            default -> Map.of();
        };
    }

    /** Elements whose required amount exceeds the available balance, in required-key order. */
    public static List<ElementType> missingElements(Map<ElementType, Long> required,
                                                    @Nullable Map<ElementType, Long> available) {
        List<ElementType> missing = new ArrayList<>();
        if (required == null || required.isEmpty()) return missing;
        for (var e : required.entrySet()) {
            long avail = available != null ? available.getOrDefault(e.getKey(), 0L) : 0L;
            if (avail < e.getValue()) missing.add(e.getKey());
        }
        return missing;
    }

    /** True when no required element exceeds the available balance. */
    public static boolean isAffordable(Map<ElementType, Long> required, @Nullable Map<ElementType, Long> available) {
        return missingElements(required, available).isEmpty();
    }

    @Nullable
    private static String paramStr(Map<String, JsonElement> params, String key) {
        if (params == null) return null;
        JsonElement el = params.get(key);
        return (el instanceof JsonPrimitive p && p.isString()) ? p.getAsString() : null;
    }

    private static int paramInt(Map<String, JsonElement> params, String key, int fallback) {
        if (params == null) return fallback;
        JsonElement el = params.get(key);
        return (el instanceof JsonPrimitive p && p.isNumber()) ? p.getAsInt() : fallback;
    }

    @Nullable
    private static String paramStrOp(Map<String, String> params, String key) {
        return params != null ? params.get(key) : null;
    }

    private static int paramIntOp(Map<String, String> params, String key, int fallback) {
        if (params == null) return fallback;
        try {
            String raw = params.get(key);
            return raw != null ? Integer.parseInt(raw) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
