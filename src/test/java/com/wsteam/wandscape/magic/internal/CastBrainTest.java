package com.wsteam.wandscape.magic.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;
import com.wsteam.wandscape.magic.data.MagicDef;

class CastBrainTest {

    private static MagicDef spell(String id, String targetMode) {
        return MagicDef.fromJson(id, JsonParser.parseString("{\"id\": \"" + id + "\", \"target_mode\": \"" + targetMode + "\"}"));
    }

    @Test
    void picksFirstCastableInOrder() {
        MagicDef beam = spell("beam", "hostile_nearest");
        MagicDef fireball = spell("fireball", "hostile_lowest_hp");
        MagicDef chosen = CastBrain.select(List.of(beam, fireball),
                def -> true, true);
        assertEquals("beam", chosen.id(), "列表顺序决定优先级");
    }

    @Test
    void skipsUncastableAndFallsToNext() {
        MagicDef beam = spell("beam", "hostile_nearest");
        MagicDef fireball = spell("fireball", "hostile_lowest_hp");
        MagicDef chosen = CastBrain.select(List.of(beam, fireball),
                def -> def.id().equals("fireball"), true);
        assertEquals("fireball", chosen.id(), "CD/蓝不过的魔法应跳过");
    }

    @Test
    void hostileModesRequireTarget() {
        MagicDef beam = spell("beam", "hostile_nearest");
        MagicDef fireball = spell("fireball", "hostile_lowest_hp");
        assertNull(CastBrain.select(List.of(beam, fireball), def -> true, false),
                "无目标时 HOSTILE 系魔法不可选");
        // SELF 魔法插在 HOSTILE 之后、无目标时照常可选（SELF/NONE 不需要目标）
        MagicDef buff = spell("buff", "self");
        MagicDef chosen = CastBrain.select(List.of(beam, buff), def -> true, false);
        assertEquals("buff", chosen.id(), "SELF 魔法无目标也可施放");
    }

    @Test
    void selfAndNoneCastableWithoutTarget() {
        MagicDef self = spell("shield", "self");
        MagicDef none = spell("rain", "none");
        assertEquals("shield", CastBrain.select(List.of(self), def -> true, false).id());
        assertEquals("rain", CastBrain.select(List.of(none), def -> true, false).id());
    }

    @Test
    void emptyKnownReturnsNull() {
        assertNull(CastBrain.select(List.of(), def -> true, true));
    }

    @Test
    void requiresTargetByMode() {
        assertTrue(CastBrain.requiresTarget(spell("a", "hostile_nearest")));
        assertTrue(CastBrain.requiresTarget(spell("b", "hostile_lowest_hp")));
        assertTrue(CastBrain.requiresTarget(spell("c", "ally_lowest_hp")));
        assertTrue(CastBrain.requiresTarget(spell("f", "dead_ally")), "复活需要死者目标");
        assertFalse(CastBrain.requiresTarget(spell("d", "self")));
        assertFalse(CastBrain.requiresTarget(spell("e", "none")));
    }
}
