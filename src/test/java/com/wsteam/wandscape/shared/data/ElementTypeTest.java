package com.wsteam.wandscape.shared.data;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ElementTypeTest {

    @Test
    void values_countIsNine() {
        assertEquals(9, ElementType.values().length);
    }

    @Test
    void tier1_containsEarthWoodWater() {
        List<ElementType> tier1 = Arrays.stream(ElementType.values())
            .filter(e -> e.getTier() == 1).toList();
        assertEquals(3, tier1.size());
        assertTrue(tier1.contains(ElementType.EARTH));
        assertTrue(tier1.contains(ElementType.WOOD));
        assertTrue(tier1.contains(ElementType.WATER));
    }

    @Test
    void tier2_containsFireIronWind() {
        List<ElementType> tier2 = Arrays.stream(ElementType.values())
            .filter(e -> e.getTier() == 2).toList();
        assertEquals(3, tier2.size());
        assertTrue(tier2.contains(ElementType.FIRE));
        assertTrue(tier2.contains(ElementType.IRON));
        assertTrue(tier2.contains(ElementType.WIND));
    }

    @Test
    void tier3_containsGoldDiamondEnder() {
        List<ElementType> tier3 = Arrays.stream(ElementType.values())
            .filter(e -> e.getTier() == 3).toList();
        assertEquals(3, tier3.size());
        assertTrue(tier3.contains(ElementType.GOLD));
        assertTrue(tier3.contains(ElementType.DIAMOND));
        assertTrue(tier3.contains(ElementType.ENDER));
    }

    @Test
    void getId_earth_returnsEarth() {
        assertEquals("earth", ElementType.EARTH.getId());
    }

    @Test
    void getId_allElements_lowercaseOnly() {
        for (ElementType e : ElementType.values()) {
            assertEquals(e.getId(), e.getId().toLowerCase(),
                e.name() + " ID is not lowercase");
        }
    }

    @Test
    void getTier_earth_returnsOne() {
        assertEquals(1, ElementType.EARTH.getTier());
    }

    @Test
    void getTier_fire_returnsTwo() {
        assertEquals(2, ElementType.FIRE.getTier());
    }

    @Test
    void getTier_gold_returnsThree() {
        assertEquals(3, ElementType.GOLD.getTier());
    }

    @Test
    void allTiersAreValid() {
        for (ElementType e : ElementType.values()) {
            assertTrue(e.getTier() >= 1 && e.getTier() <= 3,
                e.name() + " has invalid tier: " + e.getTier());
        }
    }

    @Test
    void tierDistribution_threePerTier() {
        for (int tier = 1; tier <= 3; tier++) {
            int finalTier = tier;
            long count = Arrays.stream(ElementType.values())
                .filter(e -> e.getTier() == finalTier).count();
            assertEquals(3, count, "Tier " + tier + " should have exactly 3 elements");
        }
    }

    @Test
    void elementIds_unique() {
        List<String> ids = Arrays.stream(ElementType.values())
            .map(ElementType::getId).toList();
        assertEquals(ids.size(), ids.stream().distinct().count(),
            "Element IDs must be unique");
    }
}
