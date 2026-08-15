package com.wsteam.wandscape.shared.ui.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuildingSortTest {

    @Test
    void categoryRankPutsTutorialInfrastructureFirst() {
        assertTrue(BuildingSort.categoryRank("government") < BuildingSort.categoryRank("storage"));
        assertTrue(BuildingSort.categoryRank("storage") < BuildingSort.categoryRank("workstation"));
        assertTrue(BuildingSort.categoryRank("workstation") < BuildingSort.categoryRank("crafting_station"));
        assertTrue(BuildingSort.categoryRank("crafting_station") < BuildingSort.categoryRank("potion_station"));
        assertTrue(BuildingSort.categoryRank("potion_station") < BuildingSort.categoryRank("tavern"));
        assertTrue(BuildingSort.categoryRank("tavern") < BuildingSort.categoryRank("altar"));
        assertTrue(BuildingSort.categoryRank("altar") < BuildingSort.categoryRank("node"));
        assertTrue(BuildingSort.categoryRank("node") < BuildingSort.categoryRank("decoration"));
        assertTrue(BuildingSort.categoryRank("decoration") < BuildingSort.categoryRank("shop"));
        assertTrue(BuildingSort.categoryRank("shop") < BuildingSort.categoryRank("service"));
        assertTrue(BuildingSort.categoryRank("service") < BuildingSort.categoryRank("relax"));
        assertTrue(BuildingSort.categoryRank("relax") < BuildingSort.categoryRank("atm"));
    }

    @Test
    void tabOfMapsSpecificCategoriesToOwnTabAndOthersToInfrastructure() {
        assertEquals("node", BuildingSort.tabOf("node"));
        assertEquals("decoration", BuildingSort.tabOf("decoration"));
        assertEquals("shop", BuildingSort.tabOf("shop"));
        assertEquals("service", BuildingSort.tabOf("service"));
        assertEquals("relax", BuildingSort.tabOf("relax"));
        assertEquals("atm", BuildingSort.tabOf("atm"));
        assertEquals("infrastructure", BuildingSort.tabOf("government"));
        assertEquals("infrastructure", BuildingSort.tabOf("storage"));
        assertEquals("infrastructure", BuildingSort.tabOf("workstation"));
        assertEquals("infrastructure", BuildingSort.tabOf("crafting_station"));
        assertEquals("infrastructure", BuildingSort.tabOf("tavern"));
        assertEquals("infrastructure", BuildingSort.tabOf("altar"));
        assertEquals("infrastructure", BuildingSort.tabOf("basic"));
        assertEquals("infrastructure", BuildingSort.tabOf(null));
    }

    @Test
    void unknownCategoryCountsAsInfrastructureAndSortsBeforeNode() {
        assertEquals("infrastructure", BuildingSort.tabOf("wonder"));
        assertTrue(BuildingSort.categoryRank("wonder") < BuildingSort.categoryRank("node"));
        assertTrue(BuildingSort.categoryRank(null) < BuildingSort.categoryRank("node"));
    }

    @Test
    void compareSortsByUnlockLevelFirst() {
        assertTrue(BuildingSort.compare(1, "shop", "Bakery", 2, "government", "Town Hall") < 0);
        assertTrue(BuildingSort.compare(5, "shop", "Bakery", 1, "government", "Town Hall") > 0);
        assertEquals(0, BuildingSort.compare(1, "shop", "Bakery", 1, "shop", "Bakery"));
    }

    @Test
    void compareSortsByCategoryWithinSameLevel() {
        assertTrue(BuildingSort.compare(1, "government", "Town Hall", 1, "node", "Earth Node") < 0);
        assertTrue(BuildingSort.compare(1, "shop", "Bakery", 1, "relax", "Long Chair") < 0);
        assertTrue(BuildingSort.compare(1, "service", "Farm", 1, "shop", "Bakery") > 0);
    }

    @Test
    void compareSortsChineseNamesByPinyin() {
        // 箭铺 (jian) 排在 面包店 (mian) 前
        assertTrue(BuildingSort.compare(1, "shop", "箭铺", 1, "shop", "面包店") < 0);
        // 仓库 (cang) 排在 市政厅 (shi) 前
        assertTrue(BuildingSort.compare(1, "infrastructure", "仓库", 1, "infrastructure", "市政厅") < 0);
        // 英文名按字母序
        assertTrue(BuildingSort.compare(1, "shop", "Arrow Store", 1, "shop", "Bakery") < 0);
        assertTrue(BuildingSort.compare(1, "shop", "ancient store", 1, "shop", "Bakery") < 0);
    }
}
