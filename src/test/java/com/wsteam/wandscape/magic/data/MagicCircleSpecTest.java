package com.wsteam.wandscape.magic.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.wsteam.wandscape.content.magic.data.MagicCircleSpec;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

class MagicCircleSpecTest {

    @Test
    void parsesArcaneHexagram() throws Exception {
        try (var is = getClass().getResourceAsStream("/data/wandscape/magic_circles/arcane_hexagram.json")) {
            assertNotNull(is, "arcane_hexagram.json should be on classpath");
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            MagicCircleSpec spec = MagicCircleSpec.fromJson("arcane_hexagram",
                    JsonParser.parseString(json));

            assertEquals("arcane_hexagram", spec.id);
            assertEquals(200, spec.durationTicks);
            assertEquals(8, spec.elements.size());

            // 外环 continuous
            MagicCircleSpec.Element outer = spec.elements.get(0);
            assertEquals(MagicCircleSpec.ElementType.RING, outer.type());
            assertTrue(outer.continuous());
            assertEquals(8.0, outer.radius(), 1e-9);
            assertEquals(1.2, outer.density(), 1e-9);
            assertEquals(12, outer.trailTicks());
            assertEquals("#3f8fff", outer.color());
            assertEquals(MagicCircleSpec.Easing.SMOOTHSTEP, outer.anim().easing());

            // glyph 默认值（normalizeSpec 语义）
            MagicCircleSpec.Element glyph = spec.elements.get(4);
            assertEquals(MagicCircleSpec.ElementType.GLYPH, glyph.type());
            assertEquals(6, glyph.count());
            assertEquals(0.32, glyph.glyphScale(), 1e-9);
            assertEquals(1.35, glyph.headScale(), 1e-9);
            assertEquals(0.35, glyph.tailScale(), 1e-9);

            // polygon 默认模式 beads
            MagicCircleSpec.Element poly = spec.elements.get(5);
            assertEquals(MagicCircleSpec.ElementType.POLYGON, poly.type());
            assertEquals(6, poly.sides());
            assertFalse(poly.continuous());
            assertEquals(30.0, poly.rotationOffsetDeg(), 1e-9);
        }
    }

    @Test
    void parsesReviveRitual() throws Exception {
        try (var is = getClass().getResourceAsStream("/data/wandscape/magic_circles/revive_ritual.json")) {
            assertNotNull(is, "revive_ritual.json should be on classpath");
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            MagicCircleSpec spec = MagicCircleSpec.fromJson("revive_ritual",
                    JsonParser.parseString(json));

            assertEquals("revive_ritual", spec.id);
            assertEquals(600, spec.durationTicks);
            assertEquals(0.1, spec.height, 1e-9);
            assertEquals(8, spec.elements.size());

            // 六芒星骨架：beads、密度 1.8、深绿
            MagicCircleSpec.Element star = spec.elements.get(0);
            assertEquals(MagicCircleSpec.ElementType.STAR, star.type());
            assertEquals(6, star.points());
            assertEquals(0.5, star.innerRatio(), 1e-9);
            assertEquals(1.8, star.density(), 1e-9);
            assertFalse(star.continuous());
            assertEquals("#15803d", star.color());
            assertEquals(10.0, star.rotateSpeed(), 1e-9);

            // 凹口符文：ember 粒子、6 枚、offset 30 对齐缺口；拖尾等比 ×3.75（600t/160t）
            MagicCircleSpec.Element gapRunes = spec.elements.get(1);
            assertEquals(MagicCircleSpec.ElementType.GLYPH, gapRunes.type());
            assertEquals("ember", gapRunes.particle());
            assertEquals(6, gapRunes.count());
            assertEquals(30.0, gapRunes.rotationOffsetDeg(), 1e-9);
            assertEquals(30, gapRunes.trailTicks());

            // 大符文环：enchant 粒子、12 枚（P18 独立符文随 R 疏密）
            MagicCircleSpec.Element runeRing = spec.elements.get(3);
            assertEquals("enchant", runeRing.particle());
            assertEquals(12, runeRing.count());
            assertEquals(60, runeRing.trailTicks());

            // 外环三层均为 240° 圆弧（beads 旋转无 continuous）
            for (int i = 5; i <= 7; i++) {
                MagicCircleSpec.Element arc = spec.elements.get(i);
                assertEquals(MagicCircleSpec.ElementType.ARC, arc.type(), "element " + i + " 应为 arc");
                assertEquals(240.0, arc.arcSweepDeg(), 1e-9);
                assertFalse(arc.continuous());
            }
            assertEquals(13.0, spec.elements.get(7).radius(), 1e-9);
        }
    }

    @Test
    void appliesNormalizeDefaults() {
        MagicCircleSpec spec = MagicCircleSpec.fromJson("minimal",
                JsonParser.parseString("{"
                        + "\"duration_ticks\": 60,"
                        + "\"elements\": [{\"type\": \"ring\", \"radius\": 3.0}]"
                        + "}"));

        assertEquals(60, spec.durationTicks);
        assertEquals(0.1, spec.height, 1e-9);
        MagicCircleSpec.Element el = spec.elements.get(0);
        assertEquals(MagicCircleSpec.ElementType.RING, el.type());
        assertEquals("glow", el.particle());
        assertEquals(0.0, el.axis()[0], 1e-9);
        assertEquals(1.0, el.axis()[1], 1e-9);
        assertEquals(0.0, el.axis()[2], 1e-9);
        assertFalse(el.continuous(), "mode 默认 beads");
        assertEquals(1.5, el.density(), 1e-9);
        assertEquals(10, el.trailTicks());
        assertEquals(0.0, el.rotateSpeed(), 1e-9);
        assertEquals(0.0, el.start(), 1e-9);
        // 默认动画曲线 scale/alpha 恒 1
        assertEquals(1.0, MagicCircleSpec.sampleCurve(el.anim().scale(), 0.5, 1, el.anim().easing()), 1e-9);
        assertEquals(1.0, MagicCircleSpec.sampleCurve(el.anim().alpha(), 0.9, 1, el.anim().easing()), 1e-9);
    }

    @Test
    void glyphDefaultsToEnchantParticle() {
        MagicCircleSpec spec = MagicCircleSpec.fromJson("g",
                JsonParser.parseString("{"
                        + "\"elements\": [{\"type\": \"glyph\", \"radius\": 2.0, \"count\": 4}]"
                        + "}"));
        MagicCircleSpec.Element glyph = spec.elements.get(0);
        assertEquals("enchant", glyph.particle(), "glyph 未显式 particle 时默认 enchant");
        assertEquals(4, glyph.count());
        assertEquals(8, glyph.trailTicks());
    }

    @Test
    void sampleCurveLinearAndSmoothstep() {
        List<MagicCircleSpec.Keyframe> curve = List.of(
                new MagicCircleSpec.Keyframe(0, 0),
                new MagicCircleSpec.Keyframe(1, 10));
        // t=0.5 线性 → 5
        assertEquals(5.0, MagicCircleSpec.sampleCurve(curve, 0.5, 0, MagicCircleSpec.Easing.LINEAR), 1e-9);
        // smoothstep 在 t=0.5 处 ease(0.5)=0.5 → 仍 5（对称）
        assertEquals(5.0, MagicCircleSpec.sampleCurve(curve, 0.5, 0, MagicCircleSpec.Easing.SMOOTHSTEP), 1e-9);
        // smoothstep 在 t=0.25 → 10*ease(0.25)=10*0.15625=1.5625
        assertEquals(1.5625, MagicCircleSpec.sampleCurve(curve, 0.25, 0, MagicCircleSpec.Easing.SMOOTHSTEP), 1e-9);
        // 越界钳位
        assertEquals(10.0, MagicCircleSpec.sampleCurve(curve, 2.0, 0, MagicCircleSpec.Easing.LINEAR), 1e-9);
        assertEquals(0.0, MagicCircleSpec.sampleCurve(curve, -1.0, 0, MagicCircleSpec.Easing.LINEAR), 1e-9);
        // 空曲线 → fallback
        assertEquals(7.0, MagicCircleSpec.sampleCurve(List.of(), 0.5, 7, MagicCircleSpec.Easing.LINEAR), 1e-9);
    }

    @Test
    void localTimeCascade() {
        assertNull(MagicCircleSpec.localTime(0.5, 0.3), "t < start 未激活");
        assertEquals(0.0, MagicCircleSpec.localTime(0.5, 0.5), 1e-9);
        assertEquals(1.0, MagicCircleSpec.localTime(0.5, 1.0), 1e-9);
        assertEquals(0.5, MagicCircleSpec.localTime(0.0, 0.5), 1e-9);
    }
}
