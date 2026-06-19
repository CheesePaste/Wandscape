package com.wsteam.wandscape.element.internal;

import java.util.List;

import com.wsteam.wandscape.shared.data.ElementType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ElementApiImplTest {

    /**
     * Construct with null mappingLoader — only pure-logic methods are tested here.
     * Methods requiring BlockState (getBuildCost, getDecomposeYield, isDecomposable)
     * are not tested because they need a Minecraft runtime.
     */
    private ElementApiImpl api;

    @BeforeEach
    void setUp() {
        api = new ElementApiImpl(null);
    }

    @Test
    void fromId_lowercase_returnsElement() {
        assertEquals(ElementType.EARTH, api.fromId("earth"));
    }

    @Test
    void fromId_uppercase_returnsElement() {
        assertEquals(ElementType.EARTH, api.fromId("EARTH"));
    }

    @Test
    void fromId_mixedCase_returnsElement() {
        assertEquals(ElementType.EARTH, api.fromId("eArTh"));
    }

    @Test
    void fromId_invalid_returnsNull() {
        assertNull(api.fromId("void"));
    }

    @Test
    void fromId_empty_returnsNull() {
        assertNull(api.fromId(""));
    }

    @Test
    void fromId_allNineElements() {
        for (ElementType type : ElementType.values()) {
            assertEquals(type, api.fromId(type.getId().toLowerCase()),
                "fromId failed for " + type.name());
        }
    }

    @Test
    void getTier_returnsCorrectTiers() {
        assertEquals(1, api.getTier(ElementType.EARTH));
        assertEquals(1, api.getTier(ElementType.WOOD));
        assertEquals(1, api.getTier(ElementType.WATER));
        assertEquals(2, api.getTier(ElementType.FIRE));
        assertEquals(2, api.getTier(ElementType.IRON));
        assertEquals(2, api.getTier(ElementType.WIND));
        assertEquals(3, api.getTier(ElementType.GOLD));
        assertEquals(3, api.getTier(ElementType.DIAMOND));
        assertEquals(3, api.getTier(ElementType.ENDER));
    }

    @Test
    void getByTier_tier1_returnsThreeElements() {
        List<ElementType> result = api.getByTier(1);
        assertEquals(3, result.size());
        assertTrue(result.contains(ElementType.EARTH));
        assertTrue(result.contains(ElementType.WOOD));
        assertTrue(result.contains(ElementType.WATER));
    }

    @Test
    void getByTier_tier2_returnsThreeElements() {
        List<ElementType> result = api.getByTier(2);
        assertEquals(3, result.size());
        assertTrue(result.contains(ElementType.FIRE));
        assertTrue(result.contains(ElementType.IRON));
        assertTrue(result.contains(ElementType.WIND));
    }

    @Test
    void getByTier_tier3_returnsThreeElements() {
        List<ElementType> result = api.getByTier(3);
        assertEquals(3, result.size());
        assertTrue(result.contains(ElementType.GOLD));
        assertTrue(result.contains(ElementType.DIAMOND));
        assertTrue(result.contains(ElementType.ENDER));
    }
}
