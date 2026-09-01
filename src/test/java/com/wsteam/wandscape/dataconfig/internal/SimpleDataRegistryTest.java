package com.wsteam.wandscape.dataconfig.internal;

import java.util.Map;

import com.google.gson.JsonParser;

import com.wsteam.wandscape.foundation.registry.dataconfig.internal.SimpleDataRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimpleDataRegistryTest {

    private SimpleDataRegistry<String> registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleDataRegistry<>((id, json) -> id + ":" + json.getAsString());
    }

    @Test
    void get_existingKey_returnsValue() {
        registry.loadEntry("a", JsonParser.parseString("\"hello\""));
        assertEquals("a:hello", registry.get("a"));
    }

    @Test
    void get_missingKey_returnsNull() {
        assertNull(registry.get("nonexistent"));
    }

    @Test
    void getAll_returnsAllEntries() {
        registry.loadEntry("a", JsonParser.parseString("\"1\""));
        registry.loadEntry("b", JsonParser.parseString("\"2\""));
        registry.loadEntry("c", JsonParser.parseString("\"3\""));
        assertEquals(3, registry.getAll().size());
    }

    @Test
    void getAll_returnsDefensiveCopy() {
        registry.loadEntry("a", JsonParser.parseString("\"1\""));
        Map<String, String> snapshot = registry.getAll();
        registry.loadEntry("b", JsonParser.parseString("\"2\""));
        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("c", "3"));
    }

    @Test
    void contains_existing_returnsTrue() {
        registry.loadEntry("key", JsonParser.parseString("\"val\""));
        assertTrue(registry.contains("key"));
    }

    @Test
    void contains_missing_returnsFalse() {
        assertFalse(registry.contains("missing"));
    }

    @Test
    void loadEntry_overwritesExistingKey() {
        registry.loadEntry("key", JsonParser.parseString("\"first\""));
        registry.loadEntry("key", JsonParser.parseString("\"second\""));
        assertEquals("key:second", registry.get("key"));
        assertEquals(1, registry.getAll().size());
    }

    @Test
    void clear_removesAllEntries() {
        registry.loadEntry("a", JsonParser.parseString("\"1\""));
        registry.loadEntry("b", JsonParser.parseString("\"2\""));
        registry.clear();
        assertTrue(registry.getAll().isEmpty());
        assertFalse(registry.contains("a"));
        assertFalse(registry.contains("b"));
    }

    @Test
    void loadEntry_clear_loadEntry_cycle() {
        registry.loadEntry("x", JsonParser.parseString("\"old\""));
        assertEquals("x:old", registry.get("x"));
        registry.clear();
        assertNull(registry.get("x"));
        registry.loadEntry("x", JsonParser.parseString("\"new\""));
        assertEquals("x:new", registry.get("x"));
    }
}
