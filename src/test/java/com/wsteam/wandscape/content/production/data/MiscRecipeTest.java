package com.wsteam.wandscape.production.data;

import java.util.Map;

import com.google.gson.JsonParser;
import com.wsteam.wandscape.shared.data.ElementType;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MiscRecipe} — fromJson 解析：成本/解锁缺省/字段读入。
 * 成本用自选字面量，不钉死平衡数值。
 */
class MiscRecipeTest {

    @Test
    void parsesOutputAndCost() {
        MiscRecipe r = MiscRecipe.fromJson("peace_wand", JsonParser.parseString(
                "{\"type\":\"misc\",\"id\":\"peace_wand\",\"display_name\":\"和平权杖\","
                        + "\"output\":{\"item\":\"wandscape:peace_wand\"},"
                        + "\"cost\":{\"earth\":1100,\"water\":1300},"
                        + "\"unlock_requirement\":{\"min_colony_level\":1}}"));
        assertEquals("peace_wand", r.id());
        assertEquals("wandscape:peace_wand", r.outputItem());
        assertEquals(1100L, r.cost().get(ElementType.EARTH));
        assertEquals(1300L, r.cost().get(ElementType.WATER));
        assertEquals(1, r.unlockRequirement().minColonyLevel());
    }

    @Test
    void defaultsCraftStationToCraftingStationAndUnlockToLevelOne() {
        MiscRecipe r = MiscRecipe.fromJson("follow_wand", JsonParser.parseString(
                "{\"type\":\"misc\",\"id\":\"follow_wand\",\"output\":{\"item\":\"wandscape:follow_wand\"}}"));
        assertEquals("crafting_station", r.craftStation());
        assertEquals(1, r.unlockRequirement().minColonyLevel()); // 缺省 = NONE 语义（1 级可用）
        assertTrue(r.cost().isEmpty());
    }

    @Test
    void costMapIsKeyedByElementType() {
        MiscRecipe r = MiscRecipe.fromJson("hostile_wand", JsonParser.parseString(
                "{\"type\":\"misc\",\"id\":\"hostile_wand\",\"output\":{\"item\":\"wandscape:hostile_wand\"},"
                        + "\"cost\":{\"fire\":1300,\"dark\":1100}}"));
        assertEquals(Map.of(ElementType.FIRE, 1300L, ElementType.DARK, 1100L), r.cost());
    }
}
