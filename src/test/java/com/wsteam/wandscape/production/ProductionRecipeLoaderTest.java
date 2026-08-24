package com.wsteam.wandscape.production;

import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.element.internal.ElementMappingConfig;
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
                false);
    }

    private static ElementMappingConfig nonSynthesizable() {
        return new ElementMappingConfig(
                null, "minecraft:dirt",
                Map.of(), false);
    }

    private static ElementMappingConfig synthesizableDirt() {
        return new ElementMappingConfig(
                null, "minecraft:dirt",
                Map.of(ElementType.EARTH, 1L),
                false);
    }

    private static ElementMappingConfig synthesizablePlanks() {
        return new ElementMappingConfig(
                null, "minecraft:oak_planks",
                Map.of(ElementType.EARTH, 1L, ElementType.WOOD, 1L),
                false);
    }

    private static ElementMappingConfig synthesizableIron() {
        return new ElementMappingConfig(
                null, "minecraft:iron_ingot",
                Map.of(ElementType.METAL, 3L),
                false);
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

    @Test
    void synthesizeChannelTicks_instantForValueLessOrEqualTo2() {
        // Value = 1 (Dirt)
        SynthesizeRecipe dirtRecipe = SynthesizeRecipe.fromElementMapping(synthesizableDirt());
        assertEquals(1L, dirtRecipe.totalCost());
        assertEquals(0, dirtRecipe.calculateChannelTicks(1));
        assertEquals(0, dirtRecipe.calculateChannelTicks(64));

        // Value = 2 (Oak Planks: 1 Earth + 1 Wood)
        SynthesizeRecipe planksRecipe = SynthesizeRecipe.fromElementMapping(synthesizablePlanks());
        assertEquals(2L, planksRecipe.totalCost());
        assertEquals(0, planksRecipe.calculateChannelTicks(1));
        assertEquals(0, planksRecipe.calculateChannelTicks(100));
    }

    @Test
    void synthesizeChannelTicks_fiveTicksPerUnitForValueGreaterThan2() {
        // Value = 3 (Iron Ingot: 3 Metal)
        SynthesizeRecipe ironRecipe = SynthesizeRecipe.fromElementMapping(synthesizableIron());
        assertEquals(3L, ironRecipe.totalCost());
        assertEquals(5, ironRecipe.calculateChannelTicks(1), "1 unit should take 5 ticks");
        assertEquals(25, ironRecipe.calculateChannelTicks(5), "5 units should take 25 ticks");

        // Value = 12 (Bread: 12 Wood)
        SynthesizeRecipe breadRecipe = SynthesizeRecipe.fromElementMapping(synthesizableBread());
        assertEquals(12L, breadRecipe.totalCost());
        assertEquals(50, breadRecipe.calculateChannelTicks(10), "10 units should take 50 ticks");
    }
}
