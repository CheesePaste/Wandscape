package com.wsteam.wandscape.element.internal;

import java.util.List;
import java.util.Set;

import com.wsteam.wandscape.content.element.internal.ElementAuditor;
import com.wsteam.wandscape.content.element.internal.ElementAuditor.AuditReport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for pure logic in {@link ElementAuditor} that does NOT require
 * Minecraft runtime. Integration-level audit (scanning all registered items)
 * runs via {@code ./gradlew runGameTestServer} → {@code ElementAuditGameTest}.
 */
class ElementAuditorTest {

    private static final String SAMPLE_SEEDS = """
        {
            "seeds": [
                {"item": "minecraft:dirt",      "values": {"earth": 1}},
                {"item": "minecraft:iron_ingot","values": {"metal": 64}},
                {"item": "minecraft:copper_ingot","values": {"metal": 8}}
            ]
        }
        """;

    @Test
    void parseSeedIds_extractsAllItems() {
        Set<String> ids = ElementAuditor.parseSeedIds(SAMPLE_SEEDS);
        assertEquals(3, ids.size());
        assertTrue(ids.contains("minecraft:dirt"));
        assertTrue(ids.contains("minecraft:iron_ingot"));
        assertTrue(ids.contains("minecraft:copper_ingot"));
    }

    @Test
    void parseSeedIds_ignoresValues() {
        Set<String> ids = ElementAuditor.parseSeedIds(SAMPLE_SEEDS);
        // All entries are items, not values
        assertFalse(ids.contains("earth"));
        assertFalse(ids.contains("metal"));
    }

    @Test
    void parseSeedIds_emptyArray() {
        String json = "{\"seeds\": []}";
        Set<String> ids = ElementAuditor.parseSeedIds(json);
        assertTrue(ids.isEmpty());
    }

    @Test
    void auditReport_missingCount() {
        var report = new AuditReport(10, 20, 1000,
            List.of("a", "b"), List.of("c"));
        assertEquals(3, report.missingCount());
    }

    @Test
    void auditReport_missingCount_zero() {
        var report = new AuditReport(10, 20, 1000,
            List.of(), List.of());
        assertEquals(0, report.missingCount());
    }

    @Test
    void auditReport_formattedString_containsSections() {
        var report = new AuditReport(5, 10, 500,
            List.of("minecraft:stone"), List.of("minecraft:stick"));

        String s = report.toFormattedString();
        assertTrue(s.contains("Seeds: 5"));
        assertTrue(s.contains("Mapped: 10"));
        assertTrue(s.contains("Total registered items: 500"));
        assertTrue(s.contains("Missing: 2"));
        assertTrue(s.contains("minecraft:stone"));
        assertTrue(s.contains("minecraft:stick"));
    }

    @Test
    void auditReport_formattedString_emptyNoCrash() {
        var report = new AuditReport(0, 0, 0, List.of(), List.of());
        String s = report.toFormattedString();
        assertTrue(s.contains("Missing: 0"));
    }

    @Test
    void auditReport_formattedString_truncatesLongItemList() {
        // Generate 250 fake missing items — should truncate at 200
        var manyItems = new java.util.ArrayList<String>();
        for (int i = 0; i < 250; i++) {
            manyItems.add("minecraft:item_" + i);
        }
        var report = new AuditReport(0, 0, 500, List.of(), manyItems);
        String s = report.toFormattedString();
        assertTrue(s.contains("showing 200/250"));
        assertTrue(s.contains("... and 50 more"));
    }
}
