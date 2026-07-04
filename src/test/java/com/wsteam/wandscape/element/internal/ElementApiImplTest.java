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
    void fromId_allSevenElements() {
        for (ElementType type : ElementType.values()) {
            assertEquals(type, api.fromId(type.getId().toLowerCase()),
                "fromId failed for " + type.name());
        }
    }
}
