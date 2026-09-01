package com.wsteam.wandscape.integration.jei;

import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.compat.jei.ElementRecipe;
import com.wsteam.wandscape.compat.jei.ElementRecipeCollector;
import com.wsteam.wandscape.compat.jei.ElementRecipeKind;
import com.wsteam.wandscape.content.element.internal.ElementMappingConfig;
import com.wsteam.wandscape.content.production.data.BrewPotionRecipe;
import com.wsteam.wandscape.content.production.data.CraftSpellRecipe;
import com.wsteam.wandscape.content.production.data.CraftWandRecipe;
import com.wsteam.wandscape.content.production.data.RecipeUnlockRequirement;
import com.wsteam.wandscape.shared.data.ElementType;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ElementRecipeCollectorTest {

    private static ElementMappingConfig cfg(String blockId, String itemId,
                                            boolean disabled, Map<ElementType, Long> cost) {
        return new ElementMappingConfig(blockId, itemId, cost, disabled);
    }

    @Test
    void fromElementMappings_validConfig_producesSynthesizeAndDecomposeWithWorkstation() {
        Map<ElementType, Long> cost = Map.of(ElementType.EARTH, 4L, ElementType.FIRE, 2L);
        List<ElementRecipe> recipes =
                ElementRecipeCollector.fromElementMappings(List.of(cfg("minecraft:stone", "minecraft:stone", false, cost)));

        assertEquals(2, recipes.size());

        ElementRecipe syn = recipes.get(0);
        assertEquals(ElementRecipeKind.SYNTHESIZE, syn.kind());
        assertEquals(ElementRecipeCollector.STATION_WORKSTATION, syn.stationKey());
        assertEquals("minecraft:stone", syn.itemId());
        assertEquals(cost, syn.elements());
        assertTrue(syn.extraInputs().isEmpty());
        assertEquals(0, syn.value());

        ElementRecipe dec = recipes.get(1);
        assertEquals(ElementRecipeKind.DECOMPOSE, dec.kind());
        assertEquals(ElementRecipeCollector.STATION_WORKSTATION, dec.stationKey());
        assertEquals(cost, dec.elements());
        assertEquals(6, dec.value()); // 4 + 2
    }

    @Test
    void fromElementMappings_disabledConfig_skipped() {
        List<ElementRecipe> recipes = ElementRecipeCollector.fromElementMappings(
                List.of(cfg("minecraft:no", "minecraft:no", true, Map.of(ElementType.EARTH, 4L))));
        assertTrue(recipes.isEmpty());
    }

    @Test
    void fromElementMappings_emptyBuildCost_skipped() {
        List<ElementRecipe> recipes = ElementRecipeCollector.fromElementMappings(
                List.of(cfg("minecraft:empty", "minecraft:empty", false, Map.of())));
        assertTrue(recipes.isEmpty());
    }

    @Test
    void fromElementMappings_itemMapping_resolvesItemId() {
        List<ElementRecipe> recipes = ElementRecipeCollector.fromElementMappings(
                List.of(cfg(null, "minecraft:diamond", false, Map.of(ElementType.METAL, 1024L))));
        assertEquals(2, recipes.size());
        assertTrue(recipes.stream().allMatch(r -> r.itemId().equals("minecraft:diamond")));
    }

    @Test
    void fromElementMappings_decomposeValue_sumsAllElements() {
        List<ElementRecipe> recipes = ElementRecipeCollector.fromElementMappings(
                List.of(cfg("minecraft:m", "minecraft:m", false,
                        Map.of(ElementType.EARTH, 2L, ElementType.WOOD, 3L, ElementType.WATER, 5L))));
        ElementRecipe dec = recipes.get(1);
        assertEquals(10, dec.value()); // 2+3+5
    }

    @Test
    void fromBrewPotionRecipes_producesOnlySynthesize_withExtraInputs() {
        BrewPotionRecipe potion = new BrewPotionRecipe(
                "mana_potion", "crafting_station", "wandscape:mana_potion",
                Map.of(ElementType.WATER, 16L, ElementType.WOOD, 4L),
                List.of("minecraft:glass_bottle"), RecipeUnlockRequirement.NONE);

        List<ElementRecipe> recipes = ElementRecipeCollector.fromBrewPotionRecipes(List.of(potion));

        // 药剂只应生成合成，不生成分解
        assertEquals(1, recipes.size());
        ElementRecipe r = recipes.get(0);
        assertEquals(ElementRecipeKind.SYNTHESIZE, r.kind());
        assertEquals(ElementRecipeCollector.STATION_CRAFTING, r.stationKey());
        assertEquals("wandscape:mana_potion", r.itemId());
        assertEquals(Map.of(ElementType.WATER, 16L, ElementType.WOOD, 4L), r.elements());
        assertEquals(List.of("minecraft:glass_bottle"), r.extraInputs());
        assertEquals(0, r.value());
    }

    @Test
    void fromCraftSpellRecipes_producesOnlySynthesize_atMagicStation() {
        CraftSpellRecipe spell = new CraftSpellRecipe(
                "scroll_beam", "magic_station", "火焰光束卷轴", "wandscape:spell_scroll",
                "beam", Map.of(ElementType.EARTH, 12L, ElementType.FIRE, 8L),
                RecipeUnlockRequirement.NONE);

        List<ElementRecipe> recipes = ElementRecipeCollector.fromCraftSpellRecipes(List.of(spell));

        assertEquals(1, recipes.size());
        ElementRecipe r = recipes.get(0);
        assertEquals(ElementRecipeKind.SYNTHESIZE, r.kind());
        assertEquals(ElementRecipeCollector.STATION_MAGIC, r.stationKey());
        assertEquals("wandscape:spell_scroll", r.itemId());
        assertEquals(Map.of(ElementType.EARTH, 12L, ElementType.FIRE, 8L), r.elements());
        assertTrue(r.extraInputs().isEmpty());
        assertEquals(0, r.value());
        // 卷轴携带 magic_id NBT，JEI 才能显示绑定魔法而非"未绑定"
        assertNotNull(r.outputNbt());
        assertEquals("beam", r.outputNbt().getString("magic_id"));
    }

    @Test
    void fromCraftWandRecipes_carriesPresetNbt() {
        CompoundTag nbt = new CompoundTag();
        nbt.putString("preset_id", "craftsman_wand");
        nbt.putString("wand_color", "#C67B30");
        CraftWandRecipe wand = new CraftWandRecipe(
                "craftsman_wand", "crafting_station", "工匠法杖", "wandscape:wand", nbt,
                Map.of(ElementType.EARTH, 25000L, ElementType.WOOD, 25000L),
                RecipeUnlockRequirement.NONE);

        List<ElementRecipe> recipes = ElementRecipeCollector.fromCraftWandRecipes(List.of(wand));

        assertEquals(1, recipes.size());
        ElementRecipe r = recipes.get(0);
        assertEquals(ElementRecipeKind.SYNTHESIZE, r.kind());
        assertEquals(ElementRecipeCollector.STATION_CRAFTING, r.stationKey());
        assertEquals("wandscape:wand", r.itemId());
        // 法杖携带 preset NBT，JEI 才能显示具体变体与属性 tooltip
        assertNotNull(r.outputNbt());
        assertEquals("craftsman_wand", r.outputNbt().getString("preset_id"));
        assertEquals("#C67B30", r.outputNbt().getString("wand_color"));
        assertEquals(Map.of(ElementType.EARTH, 25000L, ElementType.WOOD, 25000L), r.elements());
    }

    @Test
    void fromBrewPotionRecipes_emptyCost_skipped() {
        BrewPotionRecipe potion = new BrewPotionRecipe(
                "empty", "crafting_station", "wandscape:empty",
                Map.of(), List.of(), RecipeUnlockRequirement.NONE);
        assertTrue(ElementRecipeCollector.fromBrewPotionRecipes(List.of(potion)).isEmpty());
    }

    @Test
    void itemIdEquals_ignoresMcPrefix() {
        assertTrue(ElementRecipeCollector.itemIdEquals("minecraft:bread", "bread"));
        assertTrue(ElementRecipeCollector.itemIdEquals("bread", "minecraft:bread"));
        assertTrue(ElementRecipeCollector.itemIdEquals("minecraft:bread", "minecraft:bread"));
        assertFalse(ElementRecipeCollector.itemIdEquals("minecraft:bread", "minecraft:diamond"));
        assertFalse(ElementRecipeCollector.itemIdEquals(null, "bread"));
        assertFalse(ElementRecipeCollector.itemIdEquals("bread", null));
    }
}
