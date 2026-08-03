package com.wsteam.wandscape.shared.ui.skin;

import net.minecraft.resources.ResourceLocation;
import com.wsteam.wandscape.Wandscape;
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
    public static final ResourceLocation BUTTON_A   = skinTex("button_a.png");
    public static final ResourceLocation TAB_C      = skinTex("tab_c.png");
    public static final ResourceLocation CLOSE_BTN  = skinTex("close_button.png");
    public static final ResourceLocation BAR_A      = skinTex("bar_a.png");
    public static final ResourceLocation LEFT_ARROW = skinTex("left_arrow.png");
    public static final ResourceLocation RIGHT_ARROW = skinTex("right_arrow.png");
    public static final ResourceLocation LESS_BTN   = skinTex("less_button.png");
    public static final ResourceLocation MORE_BTN   = skinTex("more_button.png");
    public static final ResourceLocation HELP_BTN   = skinTex("help_button.png");
    public static final ResourceLocation OPTION_BTN = skinTex("options_button.png");
    public static final ResourceLocation EXIT_BTN   = skinTex("exit_button.png");
    public static final ResourceLocation UP_ARROW   = skinTex("up_arrow.png");
    public static final ResourceLocation DOWN_ARROW = skinTex("down_arrow.png");

    // ── Sprite definitions — button_a (384×22, 4 states) ──

    public static final SkinSprite[] BTN_A_STATES = {
        at(2, 0).size(93, 22),    // normal
        at(96, 0).size(96, 22),   // hover
        at(197, 0).size(86, 22),  // pressed
        at(295, 0).size(82, 22),  // disabled
    };

    public static final int BUTTON_A_SHEET_W = 384;
    public static final int BUTTON_A_SHEET_H = 22;

    // ── Sprite definitions — tab_c (384×32, 3 segments: left / center / right) ──

    public static final SkinSprite TAB_C_LEFT   = at(0, 0).size(91, 32);
    public static final SkinSprite TAB_C_CENTER = at(96, 0).size(190, 32);
    public static final SkinSprite TAB_C_RIGHT  = at(288, 0).size(90, 32);

    public static final int TAB_C_SHEET_W = 384;
    public static final int TAB_C_SHEET_H = 32;

    // ── Sprite definitions — close_button (128×32, 4 states: 30×32 each) ──

    public static final SkinSprite[] CLOSE_STATES = {
        at(0, 0).size(30, 32),
        at(32, 0).size(30, 32),
        at(64, 0).size(30, 32),
        at(96, 0).size(30, 32),
    };

    public static final int CLOSE_SHEET_W = 128;
    public static final int CLOSE_SHEET_H = 32;

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

    // ── Sprite definitions — less_button (96×24, 4 states) ──

    public static final SkinSprite[] LESS_STATES = {
        at(1, 0).size(22, 24),
        at(24, 0).size(24, 24),
        at(49, 0).size(22, 24),
        at(73, 0).size(22, 24),
    };

    public static final int LESS_SHEET_W = 96;
    public static final int LESS_SHEET_H = 24;

    // ── Sprite definitions — more_button (96×24, 4 states) ──

    public static final SkinSprite[] MORE_STATES = {
        at(1, 0).size(22, 24),
        at(24, 0).size(24, 24),
        at(49, 0).size(22, 24),
        at(73, 0).size(22, 24),
    };

    public static final int MORE_SHEET_W = 96;
    public static final int MORE_SHEET_H = 24;

    // ── Sprite definitions — help_button (128×32, 4 states: 30×32 each) ──

    public static final SkinSprite[] HELP_STATES = {
        at(0, 0).size(30, 32),
        at(32, 0).size(30, 32),
        at(64, 0).size(30, 32),
        at(96, 0).size(30, 32),
    };

    public static final int HELP_SHEET_W = 128;
    public static final int HELP_SHEET_H = 32;

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

    // ── Sprite definitions — left_arrow (84×14, 3 states) ──

    public static final SkinSprite[] LEFT_ARROW_STATES = {
        at(2, 0).size(40, 14),
        at(44, 0).size(19, 14),
        at(70, 0).size(14, 14),
    };

    public static final int LEFT_ARROW_SHEET_W = 84;
    public static final int LEFT_ARROW_SHEET_H = 14;

    // ── Sprite definitions — right_arrow (84×14, 3 states) ──

    public static final SkinSprite[] RIGHT_ARROW_STATES = {
        at(2, 0).size(40, 14),
        at(44, 0).size(19, 14),
        at(70, 0).size(14, 14),
    };

    public static final int RIGHT_ARROW_SHEET_W = 84;
    public static final int RIGHT_ARROW_SHEET_H = 14;

    // ── Sprite definitions — up_arrow (56×21) ──
    // Sheet's "normal" cell spans two arrow icons; all states share the single clean arrow.

    public static final SkinSprite[] UP_ARROW_STATES = {
        at(27, 0).size(15, 21),
        at(27, 0).size(15, 21),
        at(27, 0).size(15, 21),
    };

    public static final int UP_ARROW_SHEET_W = 56;
    public static final int UP_ARROW_SHEET_H = 21;

    // ── Sprite definitions — down_arrow (56×21) ──
    // Sheet's "normal" cell spans two arrow icons; all states share the single clean arrow.

    public static final SkinSprite[] DOWN_ARROW_STATES = {
        at(27, 0).size(15, 21),
        at(27, 0).size(15, 21),
        at(27, 0).size(15, 21),
    };

    public static final int DOWN_ARROW_SHEET_W = 56;
    public static final int DOWN_ARROW_SHEET_H = 21;

    // ── 9-slice panel parameters ──
    // Each panel sheet is 96×96 with 32px 9-slice borders

    public static final int PANEL_SHEET_SIZE = 96;
    public static final int PANEL_BORDER = 32;
}
