package com.wsteam.wandscape.production;

import com.wsteam.wandscape.dataconfig.internal.WandscapeDataLoader;
import com.wsteam.wandscape.production.data.BrewPotionRecipe;
import com.wsteam.wandscape.production.data.CraftWandRecipe;
import com.wsteam.wandscape.production.data.SynthesizeRecipe;
import com.wsteam.wandscape.shared.registry.WandscapeDataRegistry;

public class ProductionRecipeLoader {
    private final WandscapeDataRegistry<SynthesizeRecipe> synthesizeRecipes;
    private final WandscapeDataRegistry<CraftWandRecipe> craftWandRecipes;
    private final WandscapeDataRegistry<BrewPotionRecipe> potionRecipes;

    public ProductionRecipeLoader(WandscapeDataLoader dataLoader) {
        this.synthesizeRecipes = dataLoader.register("synthesize_recipes", SynthesizeRecipe::fromJson);
        this.craftWandRecipes = dataLoader.register("craft_wand_recipes", CraftWandRecipe::fromJson);
        this.potionRecipes = dataLoader.register("potion_recipes", BrewPotionRecipe::fromJson);
    }

    public WandscapeDataRegistry<SynthesizeRecipe> getSynthesizeRecipes() {
        return synthesizeRecipes;
    }

    public WandscapeDataRegistry<CraftWandRecipe> getCraftWandRecipes() {
        return craftWandRecipes;
    }

    public WandscapeDataRegistry<BrewPotionRecipe> getPotionRecipes() {
        return potionRecipes;
    }
}
