package com.wsteam.wandscape.compat.jei;

import java.util.Arrays;
import java.util.List;

import com.google.gson.JsonParser;
import com.wsteam.wandscape.magic.data.MagicDef;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SpellInfoCollector 纯收集逻辑契约：描述存在的魔法生成卷轴信息条目（保序）；
 * ALTAR 祭坛专属、缺失/空白描述、null 定义一律跳过——支撑 JEI 卷轴信息页的数据筛选。
 */
class SpellInfoCollectorTest {

    private static MagicDef def(String id, String jsonExtra) {
        return MagicDef.fromJson(id, JsonParser.parseString("{" + jsonExtra + "}"));
    }

    @Test
    void collectsDefsWithDescriptionInOrder() {
        MagicDef beam = def("beam", "\"description\": \"发射一道光束。\"");
        MagicDef heal = def("heal", "\"description\": \"持续治疗友方。\"");
        List<SpellInfoEntry> entries = SpellInfoCollector.fromDefs(List.of(beam, heal));

        assertEquals(2, entries.size());
        assertEquals("beam", entries.get(0).magicId());
        assertEquals("发射一道光束。", entries.get(0).description());
        assertEquals("heal", entries.get(1).magicId());
        assertEquals("持续治疗友方。", entries.get(1).description());
    }

    @Test
    void skipsAltarSpells() {
        MagicDef revive = def("revive", "\"category\": \"altar\", \"description\": \"祭坛复活。\"");
        assertTrue(SpellInfoCollector.fromDefs(List.of(revive)).isEmpty(),
                "ALTAR 祭坛专属魔法无卷轴物品形态，不进 JEI 信息");
    }

    @Test
    void skipsMissingOrBlankDescription() {
        MagicDef noDesc = def("teleport", "");
        MagicDef blank = def("desperation", "\"description\": \"\"");
        assertTrue(SpellInfoCollector.fromDefs(List.of(noDesc, blank)).isEmpty());
    }

    @Test
    void emptyInputProducesEmptyResult() {
        assertTrue(SpellInfoCollector.fromDefs(List.of()).isEmpty());
    }

    @Test
    void nullDefIsSkipped() {
        assertTrue(SpellInfoCollector.fromDefs(Arrays.asList((MagicDef) null)).isEmpty());
    }
}