package com.wsteam.wandscape.production;

import java.util.List;

import com.google.gson.JsonParser;
import com.wsteam.wandscape.content.production.data.BrewPotionRecipe;
import com.wsteam.wandscape.shared.data.ElementType;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BrewPotionRecipeNbtTest {

    @Test
    void parseRecipe_withOutputNbt() {
        String json = """
        {
          "craft_station": "crafting_station",
          "output": {
            "item": "wandscape_ae:warehouse_cell",
            "nbt": {
              "bound_colony": "$colony_id",
              "capacity": 10000,
              "active": true
            }
          },
          "cost": {
            "wood": 10
          },
          "input_items": [
            "minecraft:iron_ingot"
          ]
        }
        """;

        BrewPotionRecipe recipe = BrewPotionRecipe.fromJson("test_cell", JsonParser.parseString(json));
        assertEquals("test_cell", recipe.id());
        assertEquals("crafting_station", recipe.craftStation());
        assertEquals("wandscape_ae:warehouse_cell", recipe.outputItem());
        assertNotNull(recipe.outputNbt());
        assertEquals("$colony_id", recipe.outputNbt().getString("bound_colony"));
        assertEquals(10000, recipe.outputNbt().getLong("capacity"));
        assertTrue(recipe.outputNbt().getBoolean("active"));
        assertEquals(10L, recipe.cost().get(ElementType.WOOD));
        assertEquals(List.of("minecraft:iron_ingot"), recipe.inputItems());
    }

    @Test
    void parseRecipe_withoutOutputNbt() {
        String json = """
        {
          "output": {
            "item": "minecraft:potion"
          },
          "cost": {
            "water": 5
          }
        }
        """;

        BrewPotionRecipe recipe = BrewPotionRecipe.fromJson("heal_pot", JsonParser.parseString(json));
        assertEquals("heal_pot", recipe.id());
        assertEquals("minecraft:potion", recipe.outputItem());
        assertNull(recipe.outputNbt());
    }
}
