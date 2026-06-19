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
              "build_cost": { "earth": 4, "fire": 2 },
              "decompose_yield": { "earth": 3 },
              "decomposable": true
            }""";
        ElementMappingConfig cfg = ElementMappingConfig.fromJson("stone", JsonParser.parseString(json));
        assertEquals("minecraft:stone", cfg.blockId());
        assertEquals(Map.of(ElementType.EARTH, 4L, ElementType.FIRE, 2L), cfg.buildCost());
        assertEquals(Map.of(ElementType.EARTH, 3L), cfg.decomposeYield());
        assertTrue(cfg.decomposable());
    }

    @Test
    void fromJson_missingBuildCost_returnsEmptyMap() {
        String json = """
            {
              "block": "minecraft:dirt",
              "decompose_yield": { "earth": 1 },
              "decomposable": true
            }""";
        ElementMappingConfig cfg = ElementMappingConfig.fromJson("dirt", JsonParser.parseString(json));
        assertTrue(cfg.buildCost().isEmpty());
    }

    @Test
    void fromJson_missingDecomposeYield_returnsEmptyMap() {
        String json = """
            {
              "block": "minecraft:dirt",
              "build_cost": { "earth": 2 },
              "decomposable": false
            }""";
        ElementMappingConfig cfg = ElementMappingConfig.fromJson("dirt", JsonParser.parseString(json));
        assertTrue(cfg.decomposeYield().isEmpty());
    }

    @Test
    void fromJson_decomposableTrue() {
        String json = """
            {
              "block": "minecraft:oak_log",
              "build_cost": { "wood": 8 },
              "decompose_yield": { "wood": 4 },
              "decomposable": true
            }""";
        ElementMappingConfig cfg = ElementMappingConfig.fromJson("log", JsonParser.parseString(json));
        assertTrue(cfg.decomposable());
    }

    @Test
    void fromJson_decomposableFalse() {
        String json = """
            {
              "block": "minecraft:stone_bricks",
              "build_cost": { "earth": 4 },
              "decompose_yield": {},
              "decomposable": false
            }""";
        ElementMappingConfig cfg = ElementMappingConfig.fromJson("brick", JsonParser.parseString(json));
        assertFalse(cfg.decomposable());
    }

    @Test
    void fromJson_decomposableMissing_defaultsFalse() {
        String json = """
            {
              "block": "minecraft:glass",
              "build_cost": { "fire": 1 },
              "decompose_yield": {}
            }""";
        ElementMappingConfig cfg = ElementMappingConfig.fromJson("glass", JsonParser.parseString(json));
        assertFalse(cfg.decomposable());
    }

    @Test
    void fromJson_emptyCostMaps_bothEmpty() {
        String json = """
            {
              "block": "minecraft:air",
              "build_cost": {},
              "decompose_yield": {},
              "decomposable": false
            }""";
        ElementMappingConfig cfg = ElementMappingConfig.fromJson("air", JsonParser.parseString(json));
        assertTrue(cfg.buildCost().isEmpty());
        assertTrue(cfg.decomposeYield().isEmpty());
    }

    @Test
    void fromJson_singleElementCost() {
        String json = """
            {
              "block": "minecraft:stone",
              "build_cost": { "earth": 5 },
              "decompose_yield": {},
              "decomposable": false
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
              "build_cost": { "earth": 2, "wood": 4, "water": 6 },
              "decompose_yield": {},
              "decomposable": false
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
              "build_cost": { "invalid_element": 5 },
              "decompose_yield": {},
              "decomposable": false
            }""";
        assertThrows(IllegalArgumentException.class,
            () -> ElementMappingConfig.fromJson("bad", JsonParser.parseString(json)));
    }

    @Test
    void fromJson_blockIdCorrect() {
        String json = """
            {
              "block": "minecraft:stone",
              "build_cost": {},
              "decompose_yield": {},
              "decomposable": false
            }""";
        ElementMappingConfig cfg = ElementMappingConfig.fromJson("s", JsonParser.parseString(json));
        assertEquals("minecraft:stone", cfg.blockId());
    }

    @Test
    void fromJson_largeLongValues() {
        String json = """
            {
              "block": "minecraft:test",
              "build_cost": { "earth": 9223372036854775807 },
              "decompose_yield": {},
              "decomposable": false
            }""";
        ElementMappingConfig cfg = ElementMappingConfig.fromJson("t", JsonParser.parseString(json));
        assertEquals(Long.MAX_VALUE, cfg.buildCost().get(ElementType.EARTH));
    }
}
