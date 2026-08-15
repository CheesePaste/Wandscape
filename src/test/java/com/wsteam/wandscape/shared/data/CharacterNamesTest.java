package com.wsteam.wandscape.shared.data;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic tests for {@link CharacterNames} — key generation formats, key
 * parsing and pool integrity. Localized resolution (getString) touches the MC
 * Language runtime and is left to integration tests.
 */
class CharacterNamesTest {

    // ── Generation key formats ──

    @Test
    void fantasyKeyUsesFantasyPrefix() {
        assertTrue(CharacterNames.generateRandomNameKey(NameStyle.FANTASY)
                .startsWith("wandscape.character_name.fantasy."));
    }

    @Test
    void chineseKeyIsComposite() {
        assertTrue(CharacterNames.generateRandomNameKey(NameStyle.CHINESE)
                .matches("wandscape\\.character_name\\.zh\\.s\\d+\\.g\\d+"));
    }

    @Test
    void englishKeyIsComposite() {
        assertTrue(CharacterNames.generateRandomNameKey(NameStyle.ENGLISH)
                .matches("wandscape\\.character_name\\.en\\.f\\d+\\.l\\d+"));
    }

    @Test
    void noArgDefaultsToFantasy() {
        assertTrue(CharacterNames.generateRandomNameKey()
                .startsWith("wandscape.character_name.fantasy."));
    }

    @Test
    void generatedCompositeIndicesStayInPoolRange() {
        for (int i = 0; i < 200; i++) {
            int[] zh = CharacterNames.parseChineseComposite(CharacterNames.generateRandomNameKey(NameStyle.CHINESE));
            assertNotNull(zh);
            assertTrue(zh[0] >= 0 && zh[0] < 50, "surname index out of range: " + zh[0]);
            assertTrue(zh[1] >= 0 && zh[1] < 50, "given index out of range: " + zh[1]);

            int[] en = CharacterNames.parseEnglishComposite(CharacterNames.generateRandomNameKey(NameStyle.ENGLISH));
            assertNotNull(en);
            assertTrue(en[0] >= 0 && en[0] < 50, "first index out of range: " + en[0]);
            assertTrue(en[1] >= 0 && en[1] < 50, "last index out of range: " + en[1]);
        }
    }

    // ── Pure key parsing ──

    @Test
    void chineseCompositeParsesBothIndices() {
        assertArrayEquals(new int[]{3, 17},
                CharacterNames.parseChineseComposite("wandscape.character_name.zh.s3.g17"));
    }

    @Test
    void englishCompositeParsesBothIndices() {
        assertArrayEquals(new int[]{12, 4},
                CharacterNames.parseEnglishComposite("wandscape.character_name.en.f12.l4"));
    }

    @Test
    void fantasyIndexParsesNumber() {
        assertEquals(42, CharacterNames.parseFantasyIndex("wandscape.character_name.fantasy.42"));
        assertEquals(-1, CharacterNames.parseFantasyIndex("wandscape.character_name.fantasy.x"));
    }

    @Test
    void crossParsingRejectsWrongPool() {
        assertNull(CharacterNames.parseChineseComposite("wandscape.character_name.en.f1.l2"));
        assertNull(CharacterNames.parseEnglishComposite("wandscape.character_name.zh.s1.g2"));
        assertNull(CharacterNames.parseChineseComposite("wandscape.character_name.0"));
    }

    // ── Pool integrity ──

    @Test
    void fantasyPoolHasExpectedSizeAndUniqueNames() {
        assertEquals(413, CharacterNames.fantasyPoolSize());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < CharacterNames.fantasyPoolSize(); i++) {
            String name = CharacterNames.fantasyName(i);
            assertNotNull(name);
            assertFalse(name.isEmpty());
            assertTrue(seen.add(name), "duplicate fantasy name: " + name);
        }
        assertNull(CharacterNames.fantasyName(CharacterNames.fantasyPoolSize()));
    }

    @Test
    void embeddedPoolsMatchLangKeyCounts() {
        // zh.s0..zh.s49 / zh.g0..zh.g49 / en.f0..49 / en.l0..49 — 50 entries each.
        for (int i = 0; i < 50; i++) {
            assertNotNull(CharacterNames.parseChineseComposite("wandscape.character_name.zh.s" + i + ".g0"));
            assertNotNull(CharacterNames.parseEnglishComposite("wandscape.character_name.en.f0.l" + i));
        }
    }

    // ── displayComponent structure ──

    @Test
    void chineseCompositeDisplayHasTwoSiblingParts() {
        assertEquals(2, CharacterNames.displayComponent("wandscape.character_name.zh.s0.g0")
                .getSiblings().size());
    }

    @Test
    void englishCompositeDisplayHasTwoSiblingParts() {
        assertEquals(2, CharacterNames.displayComponent("wandscape.character_name.en.f0.l0")
                .getSiblings().size());
    }

    @Test
    void fantasyDisplayHasNoSiblings() {
        assertEquals(0, CharacterNames.displayComponent("wandscape.character_name.fantasy.0")
                .getSiblings().size());
    }

    @Test
    void legacyFlatKeyDisplayHasNoSiblings() {
        assertEquals(0, CharacterNames.displayComponent("wandscape.character_name.0")
                .getSiblings().size());
    }

    @Test
    void literalNameDisplayHasNoSiblings() {
        assertEquals(0, CharacterNames.displayComponent("张三").getSiblings().size());
    }
}
