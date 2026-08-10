package com.wsteam.wandscape.imgui;

import imgui.ImFont;
import imgui.ImFontConfig;
import imgui.ImGui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that CJK glyphs actually land in the ImGui font atlas for the
 * Chinese font loading path used by {@link ImGuiManager}.
 *
 * <p>Background: {@code ImGui.getIO().getFonts().getGlyphRangesChineseSimplifiedCommon()}
 * is broken in imgui-java 1.86.10 (issue #70) — CJK codepoints exceed signed
 * short, the JNI return truncates them, and the array carries no CJK ranges,
 * so Chinese renders as "?????" regardless of font. The production code now
 * builds the range array by hand with explicit (short) casts. This test
 * proves that path produces real glyphs, before and after GC pressure.
 *
 * <p>Windows-only: it loads the hard-coded {@code C:\Windows\Fonts\simhei.ttf} and
 * requires the Windows ImGui native library (natives-windows jar). On Linux/macOS
 * CI this test cannot pass (no simhei, no .dll), so it is skipped there — the
 * Windows dev machine keeps full coverage.</p>
 */
@EnabledOnOs(OS.WINDOWS)
public class ImGuiFontGlyphTest {

    private static final String SIMHEI = "C:\\Windows\\Fonts\\simhei.ttf";

    /** Same array construction as {@link ImGuiManager#init}. */
    private static short[] buildCjkRanges() {
        return new short[]{
                (short) 0x0020, (short) 0x00FF,
                (short) 0x2000, (short) 0x206F,
                (short) 0x3000, (short) 0x30FF,
                (short) 0x31F0, (short) 0x31FF,
                (short) 0xFF00, (short) 0xFFEF,
                (short) 0xFFFD, (short) 0xFFFD,
                (short) 0x4E00, (short) 0x9FFF,
                0,
        };
    }

    private static boolean hasGlyph(ImFont font, int codepoint) {
        if (font == null || font.ptr == 0) return false;
        return font.findGlyphNoFallback(codepoint).ptr != 0;
    }

    private static boolean hasGlyphs(ImFont font) {
        return hasGlyph(font, 0x9053)   // 道
                && hasGlyph(font, 0x8DEF) // 路
                && hasGlyph(font, 0x7F16) // 编
                && hasGlyph(font, 0x8F91) // 辑
                && hasGlyph(font, 0x5236) // 制
                && hasGlyph(font, 0x4F5C) // 作
                && hasGlyph(font, 0x9635) // 阵
                && hasGlyph(font, 0x5217); // 列
    }

    /** Allocate garbage + full GC to make sure the ranges array is not
     *  silently reclaimed between addFont and build (or later probes). */
    private static void pressureGC() {
        byte[][] junk = new byte[512][];
        for (int i = 0; i < junk.length; i++) {
            junk[i] = new byte[64 * 1024];
        }
        for (int round = 0; round < 3; round++) {
            System.gc();
            try { Thread.sleep(30); } catch (InterruptedException ignored) {}
        }
    }

    @Test
    void explicitCastRangesProduceCjkGlyphs() {
        ImGui.createContext();
        try {
            short[] ranges = buildCjkRanges();
            ImFontConfig cfg = new ImFontConfig();
            cfg.setOversampleH(2);
            cfg.setOversampleV(2);
            cfg.setGlyphRanges(ranges);
            ImFont font = ImGui.getIO().getFonts().addFontFromFileTTF(SIMHEI, 17.0f, cfg);
            assertNotNull(font, "simhei.ttf should load");
            assertNotEquals(0, font.ptr, "font native ptr should be non-zero");
            ImGui.getIO().getFonts().build();

            boolean beforeGc = hasGlyphs(font);
            System.out.println("[GlyphTest] explicitRanges beforeGC=" + beforeGc);

            pressureGC();
            boolean afterGc = hasGlyphs(font);
            System.out.println("[GlyphTest] explicitRanges afterGC =" + afterGc);

            assertTrue(beforeGc, "explicit-cast ranges must produce CJK glyphs");
            assertTrue(afterGc, "CJK glyphs must survive GC");
        } finally {
            // Do NOT call destroyContext here — it crashes the JVM in this
            // headless test env (native teardown needs a real context).
        }
    }
}
