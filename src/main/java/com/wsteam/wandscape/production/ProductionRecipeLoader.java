package com.wsteam.wandscape.production;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

import com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader;
import com.wsteam.wandscape.element.internal.ElementMappingConfig;
import com.wsteam.wandscape.element.internal.ElementMappingLoader;
import com.wsteam.wandscape.production.data.BrewPotionRecipe;
import com.wsteam.wandscape.production.data.CraftWandRecipe;
import com.wsteam.wandscape.production.data.SynthesizeRecipe;
import com.wsteam.wandscape.shared.registry.WandscapeDataRegistry;

public class ProductionRecipeLoader {
    private final WandscapeDataRegistry<CraftWandRecipe> craftWandRecipes;
    private final WandscapeDataRegistry<BrewPotionRecipe> potionRecipes;
    private final ElementMappingLoader elementMappingLoader;

    public ProductionRecipeLoader(WandscapeDataLoader dataLoader, ElementMappingLoader elementMappingLoader) {
        this.craftWandRecipes = dataLoader.register("craft_wand_recipes", CraftWandRecipe::fromJson);
        this.potionRecipes = dataLoader.register("potion_recipes", BrewPotionRecipe::fromJson);
        this.elementMappingLoader = elementMappingLoader;
    }

    /** Returns a synthesize recipe derived from element_mappings, or null if not synthesizable. */
    @Nullable
    public SynthesizeRecipe getSynthesizeRecipe(String id) {
        for (ElementMappingConfig config : elementMappingLoader.getAllConfigs()) {
            String matchId = config.itemId() != null ? config.itemId() : config.blockId();
            if (id.equals(matchId) && config.synthesize() != null) {
                return SynthesizeRecipe.fromElementMapping(config);
            }
        }
        return null;
    }

    public Collection<SynthesizeRecipe> getAllSynthesizeRecipes() {
        List<SynthesizeRecipe> result = new ArrayList<>();
        for (ElementMappingConfig config : elementMappingLoader.getAllConfigs()) {
            if (config.synthesize() != null) {
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
}
