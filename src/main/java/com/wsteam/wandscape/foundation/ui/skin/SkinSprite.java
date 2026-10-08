package com.wsteam.wandscape.foundation.ui.skin;

import com.wsteam.wandscape.Wandscape;
import net.minecraft.resources.ResourceLocation;
/**
 * Sprite coordinate within a sprite sheet.
 *
 * @param u       left offset in the sheet (pixels)
 * @param v       top offset in the sheet (pixels)
 * @param width   sprite width (pixels)
 * @param height  sprite height (pixels)
 */
public record SkinSprite(int u, int v, int width, int height) {

    public static Builder at(int u, int v) {
        return new Builder(u, v);
    }

    public static class Builder {
        private final int u, v;
        Builder(int u, int v) { this.u = u; this.v = v; }
        public SkinSprite size(int w, int h) { return new SkinSprite(u, v, w, h); }
    }

    // ── Convenience constants for common skin sheets ──

    private static final String SKIN = "textures/gui/skin/";

    public static ResourceLocation skinTex(String name) {
        return ResourceLocation.fromNamespaceAndPath(Wandscape.MODID, SKIN + name);
    }

    // ── Sheet references ──

    public static final ResourceLocation PANEL_A    = skinTex("panel_9slice_a.png");
    public static final ResourceLocation PANEL_B    = skinTex("panel_9slice_b.png");
    public static final ResourceLocation HEADER_A   = skinTex("header_a.png");
    public static final ResourceLocation TAB_C      = skinTex("tab_c.png");
    public static final ResourceLocation BAR_A      = skinTex("bar_a.png");
    /** Retained for IconButton sheet comparison marker. */
    public static final ResourceLocation CLOSE_BTN  = skinTex("close_button.png");
    public static final ResourceLocation OPTION_BTN = skinTex("options_button.png");
    public static final ResourceLocation EXIT_BTN   = skinTex("exit_button.png");

    // ── Sprite definitions — tab_c (384×32, 3 segments: left / center / right) ──

    public static final SkinSprite TAB_C_LEFT   = at(0, 0).size(91, 32);
    public static final SkinSprite TAB_C_CENTER = at(96, 0).size(190, 32);
    public static final SkinSprite TAB_C_RIGHT  = at(288, 0).size(90, 32);

    public static final int TAB_C_SHEET_W = 384;
    public static final int TAB_C_SHEET_H = 32;

    // ── Sprite definitions — header_a (96×32, 1 sprite, split into 3 parts) ──

    public static final SkinSprite HEADER_A_SPRITE = at(1, 0).size(95, 32);
    public static final int HEADER_A_SHEET_W = 96;
    public static final int HEADER_A_SHEET_H = 32;

    /** 3-part segments: left cap / stretchable center / right cap */
    public static final SkinSprite HEADER_A_LEFT   = at(1, 0).size(32, 32);
    public static final SkinSprite HEADER_A_CENTER = at(33, 0).size(30, 32);
    public static final SkinSprite HEADER_A_RIGHT  = at(63, 0).size(33, 32);

    // ── Sprite definitions — bars (95×17 and 95×15, 1 sprite each) ──

    public static final SkinSprite BAR_A_SPRITE = at(0, 0).size(95, 17);
    public static final int BAR_A_SHEET_W = 95;
    public static final int BAR_A_SHEET_H = 17;

    // ── Sprite definitions — options_button (128×32, 4 states: 30×32 each) ──

    public static final SkinSprite[] OPTION_STATES = {
        at(0, 0).size(30, 32),
        at(32, 0).size(30, 32),
        at(64, 0).size(30, 32),
        at(96, 0).size(30, 32),
    };

    public static final int OPTION_SHEET_W = 128;
    public static final int OPTION_SHEET_H = 32;

    // ── Sprite definitions — exit_button (128×32, 4 states: 30×32 each) ──

    public static final SkinSprite[] EXIT_STATES = {
        at(0, 0).size(30, 32),
        at(32, 0).size(30, 32),
        at(64, 0).size(30, 32),
        at(96, 0).size(30, 32),
    };

    public static final int EXIT_SHEET_W = 128;
    public static final int EXIT_SHEET_H = 32;

    // ── 9-slice panel parameters ──
    // Each panel sheet is 96×96 with 32px 9-slice borders

    public static final int PANEL_SHEET_SIZE = 96;
    public static final int PANEL_BORDER = 32;
}
