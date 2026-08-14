package com.wsteam.wandscape.element.internal;

import java.util.Map;

import com.google.gson.JsonParser;
import com.wsteam.wandscape.shared.data.ElementType;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ElementMappingConfigTest {

    @Test
    void fromJson_validCompleteJson_parsesAllFields() {
        String json = """
            {
              "block": "minecraft:stone",
              "build_cost": { "earth": 4, "fire": 2 }
            }""";
        ElementMappingConfig cfg = ElementMappingConfig.fromJson("stone", JsonParser.parseString(json));
        assertEquals("minecraft:stone", cfg.blockId());
        assertEquals(Map.of(ElementType.EARTH, 4L, ElementType.FIRE, 2L), cfg.buildCost());
        assertFalse(cfg.disabled());
    }

    @Test
    void fromJson_missingBuildCost_returnsEmptyMap() {
        String json = """
            {
              "block": "minecraft:dirt"
            }""";
        ElementMappingConfig cfg = ElementMappingConfig.fromJson("dirt", JsonParser.parseString(json));
        assertTrue(cfg.buildCost().isEmpty());
    }

    @Test
    void fromJson_itemMapping_parsesItemId() {
        String json = """
            {
              "item": "minecraft:diamond",
              "build_cost": { "metal": 1024 }
            }""";
        ElementMappingConfig cfg = ElementMappingConfig.fromJson("d", JsonParser.parseString(json));
        assertEquals("minecraft:diamond", cfg.itemId());
        assertNull(cfg.blockId());
    }

    @Test
    void fromJson_singleElementCost() {
        String json = """
            {
              "block": "minecraft:stone",
              "build_cost": { "earth": 5 }
            }""";
        ElementMappingConfig cfg = ElementMappingConfig.fromJson("s", JsonParser.parseString(json));
        assertEquals(1, cfg.buildCost().size());
        assertEquals(5L, cfg.buildCost().get(ElementType.EARTH));
    }

    @Test
    void fromJson_multipleElementCosts() {
        String json = """
            {
              "block": "minecraft:multi",
              "build_cost": { "earth": 2, "wood": 4, "water": 6 }
            }""";
        ElementMappingConfig cfg = ElementMappingConfig.fromJson("m", JsonParser.parseString(json));
        assertEquals(3, cfg.buildCost().size());
        assertEquals(2L, cfg.buildCost().get(ElementType.EARTH));
        assertEquals(4L, cfg.buildCost().get(ElementType.WOOD));
        assertEquals(6L, cfg.buildCost().get(ElementType.WATER));
    }

    @Test
    void fromJson_invalidElementType_throws() {
        String json = """
            {
              "block": "minecraft:stone",
              "build_cost": { "invalid_element": 5 }
            }""";
        assertThrows(IllegalArgumentException.class,
            () -> ElementMappingConfig.fromJson("bad", JsonParser.parseString(json)));
    }

    @Test
    void fromJson_largeLongValues() {
        String json = """
            {
              "block": "minecraft:test",
              "build_cost": { "earth": 9223372036854775807 }
            }""";
        ElementMappingConfig cfg = ElementMappingConfig.fromJson("t", JsonParser.parseString(json));
        assertEquals(Long.MAX_VALUE, cfg.buildCost().get(ElementType.EARTH));
    }

    @Test
    void fromJson_disabledTrue() {
        String json = """
            {
              "block": "minecraft:oak_log",
              "build_cost": { "wood": 8 },
              "disabled": true
            }""";
        ElementMappingConfig cfg = ElementMappingConfig.fromJson("log", JsonParser.parseString(json));
        assertTrue(cfg.disabled());
    }

    @Test
    void fromJson_disabledMissing_defaultsFalse() {
        String json = """
            {
              "block": "minecraft:oak_log",
              "build_cost": { "wood": 8 }
            }""";
        ElementMappingConfig cfg = ElementMappingConfig.fromJson("log", JsonParser.parseString(json));
        assertFalse(cfg.disabled());
    }

    /** Removed/dead keys (decompose_yield/decomposable/synthesize/source) are tolerated. */
    @Test
    void fromJson_unknownKeys_ignored() {
        String json = """
            {
              "block": "minecraft:acacia_fence",
              "build_cost": { "wood": 4 },
              "decompose_yield": {},
              "decomposable": false,
              "synthesize": {},
              "source": "auto_generated"
            }""";
        ElementMappingConfig cfg = ElementMappingConfig.fromJson("fence", JsonParser.parseString(json));
        assertEquals(Map.of(ElementType.WOOD, 4L), cfg.buildCost());
        assertFalse(cfg.disabled());
    }
}
