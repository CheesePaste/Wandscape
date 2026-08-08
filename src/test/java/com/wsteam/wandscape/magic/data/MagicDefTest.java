package com.wsteam.wandscape.magic.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

class MagicDefTest {

    @Test
    void parsesBeam() throws Exception {
        MagicDef beam = loadSpec("/data/wandscape/magic_spells/beam.json");
        assertNotNull(beam, "beam.json should be on classpath");
        assertEquals("beam", beam.id());
        assertEquals(MagicDef.Category.SINGLE_TARGET, beam.category());
        assertEquals(50, beam.manaCost());
        assertEquals(400, beam.baseCooldown());
        assertEquals(32, beam.range(), 1e-9);
        assertEquals(MagicDef.TargetMode.HOSTILE_NEAREST, beam.targetMode());
        assertEquals("arcane_hexagram", beam.effectCircleId());
        assertEquals(0xFFA8E0FF, beam.effectColor());
    }

    @Test
    void parsesTeleport() throws Exception {
        MagicDef tp = loadSpec("/data/wandscape/magic_spells/teleport.json");
        assertNotNull(tp, "teleport.json should be on classpath");
        assertEquals("teleport", tp.id());
        assertEquals(MagicDef.Category.UTILITY, tp.category());
        assertEquals(30, tp.manaCost());
        assertEquals(300, tp.baseCooldown());
        assertEquals(MagicDef.TargetMode.NONE, tp.targetMode());
        assertNull(tp.effectCircleId(), "utility spell has no circle visual");
        assertNull(tp.effectColor());
    }

    @Test
    void parsesRevive() throws Exception {
        MagicDef revive = loadSpec("/data/wandscape/magic_spells/revive.json");
        assertNotNull(revive, "revive.json should be on classpath");
        assertEquals("revive", revive.id());
        assertEquals(MagicDef.Category.UTILITY, revive.category());
        assertEquals(80, revive.manaCost());
        assertEquals(600, revive.baseCooldown());
        assertEquals(32, revive.range(), 1e-9);
        assertEquals(MagicDef.TargetMode.DEAD_ALLY, revive.targetMode());
        assertEquals("revive_ritual", revive.effectCircleId());
        assertNull(revive.effectColor());
        assertTrue(revive.altarOnly(), "复活是祭坛专属魔法");
        assertEquals(600, revive.altarCooldown());
        assertEquals(600, revive.altarDuration());
    }

    @Test
    void parsesAltarFields() {
        MagicDef def = MagicDef.fromJson("altar", JsonParser.parseString(
                "{\"altar_only\": true, \"altar_cooldown\": 600, \"altar_duration\": 160}"));
        assertTrue(def.altarOnly());
        assertEquals(600, def.altarCooldown());
        assertEquals(160, def.altarDuration());
    }

    @Test
    void altarFieldsDefaultAndClamp() {
        MagicDef d = MagicDef.fromJson("d", JsonParser.parseString("{}"));
        assertFalse(d.altarOnly());
        assertEquals(0, d.altarCooldown());
        assertEquals(0, d.altarDuration());
        MagicDef neg = MagicDef.fromJson("neg", JsonParser.parseString(
                "{\"altar_cooldown\": -5, \"altar_duration\": -1}"));
        assertEquals(0, neg.altarCooldown());
        assertEquals(0, neg.altarDuration());
    }

    @Test
    void appliesNormalizeDefaults() {
        MagicDef def = MagicDef.fromJson("minimal", JsonParser.parseString("{}"));
        assertEquals("minimal", def.id());
        assertEquals(MagicDef.Category.SINGLE_TARGET, def.category());
        assertEquals(0, def.manaCost());
        assertEquals(0, def.baseCooldown());
        assertEquals(0, def.range(), 1e-9);
        assertEquals(MagicDef.TargetMode.NONE, def.targetMode());
        assertNull(def.effectCircleId());
        assertNull(def.effectColor());
    }

    @Test
    void invalidEnumFallsBack() {
        MagicDef def = MagicDef.fromJson("bad", JsonParser.parseString("{"
                + "\"category\": \"not_a_category\","
                + "\"target_mode\": \"also_bad\""
                + "}"));
        assertEquals(MagicDef.Category.SINGLE_TARGET, def.category());
        assertEquals(MagicDef.TargetMode.NONE, def.targetMode());
    }

    @Test
    void invalidColorIsIgnored() {
        MagicDef def = MagicDef.fromJson("c", JsonParser.parseString(
                "{\"effect\": {\"circle_id\": \"x\", \"color\": \"#GGGGGG\"}}"));
        assertEquals("x", def.effectCircleId());
        assertNull(def.effectColor(), "非法 hex 颜色应忽略");
    }

    @Test
    void clampsNegatives() {
        MagicDef def = MagicDef.fromJson("neg", JsonParser.parseString(
                "{\"mana_cost\": -5, \"base_cooldown\": -1, \"range\": -10}"));
        assertEquals(0, def.manaCost());
        assertEquals(0, def.baseCooldown());
        assertEquals(0, def.range(), 1e-9);
    }

    @Test
    void parsesConditions() {
        MagicDef def = MagicDef.fromJson("aoe", JsonParser.parseString(
                "{\"conditions\": {\"min_enemies\": 3, \"self_hp_max\": 0.6, \"no_effect\": \"minecraft:absorption\"}}"));
        assertEquals(3, def.conditions().minEnemies());
        assertEquals(0.6f, def.conditions().selfHpMax(), 1e-6f);
        assertEquals("minecraft:absorption", def.conditions().noEffect());
    }

    @Test
    void conditionsDefaultNone() {
        MagicDef def = MagicDef.fromJson("plain", JsonParser.parseString("{}"));
        assertEquals(SpellConditions.NONE, def.conditions());
    }

    private static MagicDef loadSpec(String path) throws Exception {
        try (var is = MagicDefTest.class.getResourceAsStream(path)) {
            if (is == null) return null;
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String id = path.substring(path.lastIndexOf('/') + 1, path.lastIndexOf('.'));
            return MagicDef.fromJson(id, JsonParser.parseString(json));
        }
    }
}
