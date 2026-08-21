package com.wsteam.wandscape.production;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader;
import com.wsteam.wandscape.element.internal.ElementMappingConfig;
import com.wsteam.wandscape.element.internal.ElementMappingLoader;
import com.wsteam.wandscape.production.data.BrewPotionRecipe;
import com.wsteam.wandscape.production.data.CraftSpellRecipe;
import com.wsteam.wandscape.production.data.CraftWandRecipe;
import com.wsteam.wandscape.production.data.SynthesizeRecipe;
import com.wsteam.wandscape.shared.registry.WandscapeDataRegistry;
import com.wsteam.wandscape.shared.log.Log;
public class ProductionRecipeLoader {
    private static final String TAG = "ProductionRecipeLoader";
    private static final String CATEGORY = "craft_recipes";

    private final WandscapeDataRegistry<CraftWandRecipe> craftWandRecipes;
    private final WandscapeDataRegistry<BrewPotionRecipe> potionRecipes;
    private final WandscapeDataRegistry<CraftSpellRecipe> spellRecipes;
    private final ElementMappingLoader elementMappingLoader;

    public ProductionRecipeLoader(WandscapeDataLoader dataLoader, ElementMappingLoader elementMappingLoader) {
        this.craftWandRecipes = dataLoader.register(CATEGORY, (id, json) -> {
            String type = getType(json);
            return "wand".equals(type) ? CraftWandRecipe.fromJson(id, json) : null;
        });
        this.potionRecipes = dataLoader.register(CATEGORY, (id, json) -> {
            String type = getType(json);
            return "potion".equals(type) ? BrewPotionRecipe.fromJson(id, json) : null;
        });
        this.spellRecipes = dataLoader.register(CATEGORY, (id, json) -> {
            String type = getType(json);
            return "spell".equals(type) ? CraftSpellRecipe.fromJson(id, json) : null;
        });
        this.elementMappingLoader = elementMappingLoader;
    }

    private static String getType(JsonElement json) {
        JsonObject obj = json.getAsJsonObject();
        return obj.has("type") ? obj.get("type").getAsString() : "wand";
    }

    /** Returns a synthesize recipe derived from element_mappings, or null if not synthesizable. */
    @Nullable
    public SynthesizeRecipe getSynthesizeRecipe(String id) {
        return findSynthesizeRecipe(elementMappingLoader.getAllConfigs(), id);
    }

    /**
     * Match a recipe id against element mappings. Id comparison is insensitive
     * to the "minecraft:" prefix so callers may pass either the full id
     * ("minecraft:bread") or a bare id ("bread").
     */
    @Nullable
    public static SynthesizeRecipe findSynthesizeRecipe(Collection<ElementMappingConfig> configs, String id) {
        String key = stripMcPrefix(id);
        for (ElementMappingConfig config : configs) {
            String matchId = config.itemId() != null ? config.itemId() : config.blockId();
            if (matchId != null && key.equals(stripMcPrefix(matchId)) && !config.buildCost().isEmpty()) {
                return SynthesizeRecipe.fromElementMapping(config);
            }
        }
        return null;
    }

    private static String stripMcPrefix(String id) {
        return id != null && id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    public Collection<SynthesizeRecipe> getAllSynthesizeRecipes() {
        List<SynthesizeRecipe> result = new ArrayList<>();
        for (ElementMappingConfig config : elementMappingLoader.getAllConfigs()) {
            if (!config.buildCost().isEmpty()) {
                result.add(SynthesizeRecipe.fromElementMapping(config));
            }
        }
        return result;
    }

    public WandscapeDataRegistry<CraftWandRecipe> getCraftWandRecipes() {
        return craftWandRecipes;
    }

    public WandscapeDataRegistry<BrewPotionRecipe> getPotionRecipes() {
        return potionRecipes;
    }

    public WandscapeDataRegistry<CraftSpellRecipe> getSpellRecipes() {
        return spellRecipes;
    }
}
