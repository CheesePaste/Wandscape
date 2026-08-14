package com.wsteam.wandscape.production;

import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.element.internal.ElementMappingConfig;
import com.wsteam.wandscape.production.data.RecipeUnlockRequirement;
import com.wsteam.wandscape.production.data.SynthesizeRecipe;
import com.wsteam.wandscape.shared.data.ElementType;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the pure recipe-matching logic in {@link ProductionRecipeLoader} —
 * specifically that id matching is insensitive to the "minecraft:" prefix
 * (auto-supply paths pass bare ids like "bread", player/executor paths pass
 * full ids like "minecraft:bread").
 */
class ProductionRecipeLoaderTest {

    private static ElementMappingConfig synthesizableBread() {
        return new ElementMappingConfig(
                null, "minecraft:bread",
                Map.of(ElementType.WOOD, 12L),
                Map.of(), false,
                new ElementMappingConfig.SynthesizeMeta(RecipeUnlockRequirement.NONE),
                false);
    }

    private static ElementMappingConfig nonSynthesizable() {
        return new ElementMappingConfig(
                null, "minecraft:dirt",
                Map.of(ElementType.EARTH, 1L),
                Map.of(), false, null, false);
    }

    @Test
    void findSynthesizeRecipe_matchesFullPrefixedId() {
        var configs = List.of(synthesizableBread());
        SynthesizeRecipe recipe = ProductionRecipeLoader.findSynthesizeRecipe(configs, "minecraft:bread");
        assertNotNull(recipe, "full id 'minecraft:bread' should match");
        assertEquals("minecraft:bread", recipe.outputItem());
    }

    @Test
    void findSynthesizeRecipe_matchesBareId() {
        var configs = List.of(synthesizableBread());
        SynthesizeRecipe recipe = ProductionRecipeLoader.findSynthesizeRecipe(configs, "bread");
        assertNotNull(recipe, "bare id 'bread' should match after prefix normalization");
        assertEquals("minecraft:bread", recipe.outputItem());
    }

    @Test
    void findSynthesizeRecipe_returnsNullForNonSynthesizable() {
        var configs = List.of(nonSynthesizable());
        assertNull(ProductionRecipeLoader.findSynthesizeRecipe(configs, "minecraft:dirt"));
        assertNull(ProductionRecipeLoader.findSynthesizeRecipe(configs, "dirt"));
    }

    @Test
    void findSynthesizeRecipe_returnsNullForUnknownId() {
        var configs = List.of(synthesizableBread());
        assertNull(ProductionRecipeLoader.findSynthesizeRecipe(configs, "minecraft:golden_apple"));
        assertNull(ProductionRecipeLoader.findSynthesizeRecipe(configs, "wood"));
    }
}
