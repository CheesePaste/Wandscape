package com.wsteam.wandscape.magic.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import com.wsteam.wandscape.content.magic.data.MagicDef;
import com.wsteam.wandscape.content.magic.data.SpellConditions;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

/**
 * MagicDef JSON 解析契约测试。
 *
 * <p>只断言**结构契约**（字段名 / category / target_mode / 视觉 id / 颜色），不断言具体平衡数值
 * （mana_cost / cooldown / range / damage / conditions 阈值 / altar 数值）——这些是数据驱动的
 * 可调参数，调平衡改 JSON 不应破坏测试（曾因「提升强度」调 meteor 数值导致 parsesMeteor 红）。
 * 解析层的钳制 / 缺省 / 枚举回退 / 非法值忽略等逻辑由下方内联 JSON 测试覆盖。
 */
class MagicDefTest {

    @Test
    void parsesBeam() throws Exception {
        MagicDef beam = loadSpec("/data/wandscape/magic_spells/beam.json");
        assertNotNull(beam, "beam.json should be on classpath");
        assertEquals("beam", beam.id());
        assertEquals(MagicDef.Category.NORMAL, beam.category());
        assertEquals("single_target", beam.defaultGroup(), "beam 默认策略组 = 单体攻击组");
        assertEquals(MagicDef.TargetMode.HOSTILE_NEAREST, beam.targetMode());
        assertEquals("arcane_hexagram", beam.effectCircleId());
        assertEquals(0xFFA8E0FF, beam.effectColor());
    }

    @Test
    void parsesTeleport() throws Exception {
        MagicDef tp = loadSpec("/data/wandscape/magic_spells/teleport.json");
        assertNotNull(tp, "teleport.json should be on classpath");
        assertEquals("teleport", tp.id());
        assertEquals(MagicDef.Category.SPECIAL, tp.category());
        assertEquals(MagicDef.TargetMode.NONE, tp.targetMode());
        assertNull(tp.effectCircleId(), "special spell has no circle visual");
        assertNull(tp.effectColor());
    }

    @Test
    void parsesRevive() throws Exception {
        MagicDef revive = loadSpec("/data/wandscape/magic_spells/revive.json");
        assertNotNull(revive, "revive.json should be on classpath");
        assertEquals("revive", revive.id());
        assertEquals(MagicDef.Category.ALTAR, revive.category());
        assertEquals(MagicDef.TargetMode.DEAD_ALLY, revive.targetMode());
        assertEquals("revive_ritual", revive.effectCircleId());
        assertNull(revive.effectColor());
        assertTrue(revive.altarOnly(), "复活是祭坛专属魔法");
    }

    @Test
    void parsesHeal() throws Exception {
        MagicDef heal = loadSpec("/data/wandscape/magic_spells/heal.json");
        assertNotNull(heal, "heal.json should be on classpath");
        assertEquals("heal", heal.id());
        assertEquals(MagicDef.Category.SPECIAL, heal.category());
        assertEquals(MagicDef.TargetMode.ALLY_LOWEST_HP, heal.targetMode());
        assertEquals("heal_magic_circle", heal.effectCircleId());
        assertEquals(0xFF2ECC71, heal.effectColor());
    }

    @Test
    void parsesMeteor() throws Exception {
        MagicDef meteor = loadSpec("/data/wandscape/magic_spells/meteor.json");
        assertNotNull(meteor, "meteor.json should be on classpath");
        assertEquals("meteor", meteor.id());
        assertEquals(MagicDef.Category.NORMAL, meteor.category());
        assertEquals("aoe", meteor.defaultGroup(), "meteor 默认策略组 = 群体攻击组");
        assertEquals(MagicDef.TargetMode.HOSTILE_NEAREST, meteor.targetMode());
        assertEquals("meteor_magic_circle", meteor.effectCircleId());
        assertEquals(0xFFE74C3C, meteor.effectColor());
    }

    @Test
    void parsesPetrification() throws Exception {
        MagicDef pet = loadSpec("/data/wandscape/magic_spells/petrification.json");
        assertNotNull(pet, "petrification.json should be on classpath");
        assertEquals("petrification", pet.id());
        assertEquals(MagicDef.Category.NORMAL, pet.category());
        assertEquals("defense", pet.defaultGroup(), "petrification 默认策略组 = 防御组");
        assertEquals(MagicDef.TargetMode.SELF, pet.targetMode());
        assertEquals("petrification_magic_circle", pet.effectCircleId());
        assertEquals(0xFF7F8C8D, pet.effectColor());
        assertEquals("wandscape:petrification", pet.conditions().noEffect());
    }

    @Test
    void parsesCastTime() {
        MagicDef def = MagicDef.fromJson("s", JsonParser.parseString("{\"cast_time\": 160}"));
        assertEquals(160, def.castTime());
    }

    @Test
    void parsesDescription() {
        MagicDef def = MagicDef.fromJson("beam", JsonParser.parseString(
                "{\"description\": \"发射一道光束，不可穿透墙壁。\"}"));
        assertEquals("发射一道光束，不可穿透墙壁。", def.description());
        // 空白 description 归一化为 null，与 default_group 同款
        MagicDef blank = MagicDef.fromJson("b", JsonParser.parseString("{\"description\": \"\"}"));
        assertNull(blank.description());
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
        assertEquals(MagicDef.Category.NORMAL, def.category());
        assertNull(def.defaultGroup(), "未配 default_group 默认 null");
        assertEquals(0, def.manaCost());
        assertEquals(0, def.baseCooldown());
        assertEquals(0, def.castTime());
        assertEquals(0, def.range(), 1e-9);
        assertEquals(MagicDef.TargetMode.NONE, def.targetMode());
        assertNull(def.effectCircleId());
        assertNull(def.effectColor());
        assertNull(def.effectDamage());
        assertNull(def.description(), "未配 description 默认 null");
    }

    @Test
    void invalidEnumFallsBack() {
        MagicDef def = MagicDef.fromJson("bad", JsonParser.parseString("{"
                + "\"category\": \"not_a_category\","
                + "\"target_mode\": \"also_bad\""
                + "}"));
        assertEquals(MagicDef.Category.NORMAL, def.category());
        assertEquals(MagicDef.TargetMode.NONE, def.targetMode());
    }

    @Test
    void parsesDefaultGroup() {
        MagicDef def = MagicDef.fromJson("g", JsonParser.parseString(
                "{\"category\": \"normal\", \"default_group\": \"aoe\"}"));
        assertEquals(MagicDef.Category.NORMAL, def.category());
        assertEquals("aoe", def.defaultGroup());
        // 非法 default_group（非策略组名）原样保留，由 equippableCategoryOf 兜底拒绝
        MagicDef bad = MagicDef.fromJson("bad", JsonParser.parseString(
                "{\"default_group\": \"not_a_group\"}"));
        assertEquals("not_a_group", bad.defaultGroup());
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
                "{\"mana_cost\": -5, \"base_cooldown\": -1, \"cast_time\": -8, \"range\": -10}"));
        assertEquals(0, def.manaCost());
        assertEquals(0, def.baseCooldown());
        assertEquals(0, def.castTime());
        assertEquals(0, def.range(), 1e-9);
    }

    @Test
    void parsesConditions() {
        MagicDef def = MagicDef.fromJson("aoe", JsonParser.parseString(
                "{\"conditions\": {\"self_hp_max\": 0.6, \"no_effect\": \"minecraft:absorption\"}}"));
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
