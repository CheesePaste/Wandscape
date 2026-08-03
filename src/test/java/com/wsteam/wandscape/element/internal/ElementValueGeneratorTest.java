package com.wsteam.wandscape.element.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.element.internal.ElementValueGenerator.IngredientSlot;
import com.wsteam.wandscape.element.internal.ElementValueGenerator.RecipeKind;
import com.wsteam.wandscape.element.internal.ElementValueGenerator.RecipeNode;
import com.wsteam.wandscape.element.internal.ElementValueGenerator.Resolution;
import com.wsteam.wandscape.shared.data.ElementType;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ElementValueGeneratorTest {

    private static final int MAX_ITERATIONS = 50;

    private static Map<ElementType, Long> el(Object... pairs) {
        Map<ElementType, Long> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            ElementType type = (ElementType) pairs[i];
            map.put(type, ((Number) pairs[i + 1]).longValue());
        }
        return map;
    }

    private static IngredientSlot slot(String item) {
        return new IngredientSlot(List.of(item), List.of(""));
    }

    private static IngredientSlot slot(String item, String remaining) {
        return new IngredientSlot(List.of(item), List.of(remaining));
    }

    private static RecipeNode recipe(String outputId, int outputCount, IngredientSlot... slots) {
        return new RecipeNode(outputId, outputCount, RecipeKind.CRAFTING, List.of(slots));
    }

    @Test
    void remainingItem_subtracted_whenRemainingResolvesInLaterPass() {
        // Mirrors the cake recipe: 3 milk buckets whose returned buckets (3 iron
        // ingots each) must NOT be charged. bucket's own recipe (3 iron ingots)
        // is registered AFTER cake, so a single sweep would resolve cake before
        // bucket exists — reproducing the metal:576 over-count.
        Map<String, Map<ElementType, Long>> seeds = new LinkedHashMap<>();
        seeds.put("minecraft:milk_bucket", el(ElementType.WATER, 4, ElementType.METAL, 192));
        seeds.put("minecraft:iron_ingot", el(ElementType.METAL, 64));

        Map<String, List<RecipeNode>> index = new LinkedHashMap<>();
        index.put("minecraft:cake", List.of(recipe("minecraft:cake", 1,
                slot("minecraft:milk_bucket", "minecraft:bucket"),
                slot("minecraft:milk_bucket", "minecraft:bucket"),
                slot("minecraft:milk_bucket", "minecraft:bucket"))));
        index.put("minecraft:bucket", List.of(recipe("minecraft:bucket", 1,
                slot("minecraft:iron_ingot"), slot("minecraft:iron_ingot"), slot("minecraft:iron_ingot"))));

        Resolution res = ElementValueGenerator.resolve(seeds, index, MAX_ITERATIONS);
        Map<String, Map<ElementType, Long>> known = res.values();

        assertEquals(el(ElementType.METAL, 192), known.get("minecraft:bucket"));
        // bucket (192) subtracted from each milk bucket: cake must keep only the
        // milk (water 4 × 3) and carry no metal
        assertEquals(el(ElementType.WATER, 12), known.get("minecraft:cake"));
        assertFalse(known.get("minecraft:cake").containsKey(ElementType.METAL));
        assertTrue(res.iterations() > 1, "fixed-point must revisit cake after bucket resolves");
    }

    @Test
    void seedValue_notOverwrittenByRecipe() {
        Map<String, Map<ElementType, Long>> seeds = new LinkedHashMap<>();
        seeds.put("minecraft:paper", el(ElementType.WOOD, 4)); // authoritative manual value
        seeds.put("minecraft:sugar_cane", el(ElementType.WOOD, 2));

        Map<String, List<RecipeNode>> index = new LinkedHashMap<>();
        index.put("minecraft:paper", List.of(recipe("minecraft:paper", 1,
                slot("minecraft:sugar_cane"), slot("minecraft:sugar_cane"), slot("minecraft:sugar_cane"))));

        Map<String, Map<ElementType, Long>> known =
                ElementValueGenerator.resolve(seeds, index, MAX_ITERATIONS).values();

        // the recipe alone would compute wood 6, but the seed's wood 4 must win
        assertEquals(el(ElementType.WOOD, 4), known.get("minecraft:paper"));
    }
}
