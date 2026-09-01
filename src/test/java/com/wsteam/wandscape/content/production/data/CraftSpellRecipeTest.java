package com.wsteam.wandscape.content.production.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.wsteam.wandscape.shared.data.ElementType;

import org.junit.jupiter.api.Test;

class CraftSpellRecipeTest {

    @Test
    void fromJson_parsesMagicIdAndCost() {
        String json = """
                {
                  "type": "spell",
                  "craft_station": "magic_station",
                  "id": "scroll_beam",
                  "display_name": "火焰光束卷轴",
                  "output": { "item": "wandscape:spell_scroll", "magic_id": "beam" },
                  "cost": { "fire": 16, "earth": 8 },
                  "unlock_requirement": { "min_colony_level": 1 }
                }
                """;

        CraftSpellRecipe r = CraftSpellRecipe.fromJson("scroll_beam", JsonParser.parseString(json));

        assertEquals("magic_station", r.craftStation());
        assertEquals("scroll_beam", r.id());
        assertEquals("火焰光束卷轴", r.displayName());
        assertEquals("wandscape:spell_scroll", r.outputItem());
        assertEquals("beam", r.magicId());
        assertEquals(16L, r.cost().get(ElementType.FIRE));
        assertEquals(8L, r.cost().get(ElementType.EARTH));
        assertEquals(1, r.unlockRequirement().minColonyLevel());
    }

    @Test
    void fromJson_defaultsCraftStationAndUnlock() {
        String json = """
                {
                  "type": "spell",
                  "id": "scroll_heal",
                  "output": { "item": "wandscape:spell_scroll", "magic_id": "heal" },
                  "cost": { "water": 16 }
                }
                """;

        CraftSpellRecipe r = CraftSpellRecipe.fromJson("scroll_heal", JsonParser.parseString(json));

        assertEquals("magic_station", r.craftStation());
        assertEquals("heal", r.magicId());
        assertEquals(1, r.unlockRequirement().minColonyLevel());
    }

    @Test
    void fromJson_magicAbsentCostParsesEmptyAndMagicFallsBackToId() {
        String json = """
                {"type": "spell", "id": "scroll_x", "output": {"item": "wandscape:spell_scroll"}}
                """;

        CraftSpellRecipe r = CraftSpellRecipe.fromJson("scroll_x", JsonParser.parseString(json));

        assertEquals("scroll_x", r.magicId());
        assertTrue(r.cost().isEmpty());
    }
}