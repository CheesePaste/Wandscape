package com.wsteam.wandscape.shared.data;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ElementTypeTest {

    @Test
    void values_countIsSeven() {
        assertEquals(7, ElementType.values().length);
    }

    @Test
    void containsEarthWoodWater() {
        List<ElementType> all = Arrays.asList(ElementType.values());
        assertTrue(all.contains(ElementType.EARTH));
        assertTrue(all.contains(ElementType.WOOD));
        assertTrue(all.contains(ElementType.WATER));
    }

    @Test
    void containsFireMetalWind() {
        List<ElementType> all = Arrays.asList(ElementType.values());
        assertTrue(all.contains(ElementType.FIRE));
        assertTrue(all.contains(ElementType.METAL));
        assertTrue(all.contains(ElementType.WIND));
    }

    @Test
    void containsDark() {
        List<ElementType> all = Arrays.asList(ElementType.values());
        assertTrue(all.contains(ElementType.DARK));
    }

    @Test
    void noGoldDiamondOrEnder() {
        List<String> names = Arrays.stream(ElementType.values())
            .map(e -> e.name()).toList();
        assertFalse(names.contains("GOLD"), "GOLD has been removed");
        assertFalse(names.contains("DIAMOND"), "DIAMOND has been removed");
        assertFalse(names.contains("ENDER"), "ENDER has been renamed to DARK");
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
    void getId_metal_returnsMetal() {
        assertEquals("metal", ElementType.METAL.getId());
    }

    @Test
    void getId_dark_returnsDark() {
        assertEquals("dark", ElementType.DARK.getId());
    }

    @Test
    void elementIds_unique() {
        List<String> ids = Arrays.stream(ElementType.values())
            .map(ElementType::getId).toList();
        assertEquals(ids.size(), ids.stream().distinct().count(),
            "Element IDs must be unique");
    }
}
