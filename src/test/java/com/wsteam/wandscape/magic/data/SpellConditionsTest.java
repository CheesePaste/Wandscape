package com.wsteam.wandscape.magic.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

class SpellConditionsTest {

    private static SpellConditions cond(String json) {
        return SpellConditions.fromJson(JsonParser.parseString(json));
    }

    @Test
    void parsesAllFields() {
        SpellConditions c = cond("{"
                + "\"self_hp_max\": 0.6,"
                + "\"ally_hp_max\": 0.5,"
                + "\"no_effect\": \"minecraft:absorption\""
                + "}");
        assertEquals(0.6f, c.selfHpMax(), 1e-6f);
        assertEquals(0.5f, c.allyHpMax(), 1e-6f);
        assertEquals("minecraft:absorption", c.noEffect());
    }

    @Test
    void absentJsonIsNone() {
        assertSame(SpellConditions.NONE, SpellConditions.fromJson(null));
        assertSame(SpellConditions.NONE, SpellConditions.fromJson(JsonParser.parseString("null")));
        SpellConditions empty = cond("{}");
        assertNull(empty.selfHpMax());
        assertNull(empty.allyHpMax());
        assertNull(empty.noEffect());
    }

    @Test
    void noneAlwaysMatches() {
        assertTrue(SpellConditions.NONE.matches(new WorldSnapshot(0, 1f, 1f, Set.of())));
    }

    @Test
    void selfHpMaxGate() {
        SpellConditions c = cond("{\"self_hp_max\": 0.6}");
        assertFalse(c.matches(new WorldSnapshot(5, 0.6f, 1f, Set.of())), "满血/超阈值不开盾");
        assertTrue(c.matches(new WorldSnapshot(5, 0.59f, 1f, Set.of())));
    }

    @Test
    void allyHpMaxGate() {
        SpellConditions c = cond("{\"ally_hp_max\": 0.5}");
        assertFalse(c.matches(new WorldSnapshot(5, 1f, 0.5f, Set.of())));
        assertTrue(c.matches(new WorldSnapshot(5, 1f, 0.49f, Set.of())));
    }

    @Test
    void noEffectGate() {
        SpellConditions c = cond("{\"no_effect\": \"minecraft:absorption\"}");
        assertFalse(c.matches(new WorldSnapshot(5, 1f, 1f, Set.of("minecraft:absorption"))));
        assertTrue(c.matches(new WorldSnapshot(5, 1f, 1f, Set.of("minecraft:speed"))));
    }

    @Test
    void allConditionsTogether() {
        SpellConditions c = cond("{\"self_hp_max\": 0.8, \"no_effect\": \"minecraft:absorption\"}");
        assertTrue(c.matches(new WorldSnapshot(2, 0.5f, 1f, Set.of("minecraft:speed"))));
        assertFalse(c.matches(new WorldSnapshot(2, 0.9f, 1f, Set.of("minecraft:speed"))), "自身血过高");
        assertFalse(c.matches(new WorldSnapshot(2, 0.5f, 1f, Set.of("minecraft:absorption"))), "已有护盾");
    }

    @Test
    void nullSnapshotFallsBackToEmpty() {
        SpellConditions c = cond("{\"self_hp_max\": 0.8}");
        assertFalse(c.matches(null), "空快照满血(1.0) 不满足 self_hp_max");
        assertTrue(SpellConditions.NONE.matches(null));
    }
}
