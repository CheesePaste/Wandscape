package com.wsteam.wandscape.shared.ui.skin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
/**
 * Renders UI sprites from skin sheets via {@link GuiGraphics#blit}.
 * All coordinates are in screen pixels; UVs come from {@link SkinSprite}.
 */
public final class SkinRender {

    private SkinRender() {}

    // ── 9-slice panel ──

    /**
     * Renders a 9-slice panel at the given position and size.
     * Uses the specified panel sheet with border thickness from {@link SkinSprite#PANEL_BORDER}.
     */
    public static void drawPanel9Slice(GuiGraphics g, ResourceLocation sheet,
                                        int x, int y, int targetW, int targetH) {
        int S = SkinSprite.PANEL_SHEET_SIZE;
        int B = SkinSprite.PANEL_BORDER;
        int innerS = S - 2 * B;

        // Quadrant UV origins within the sheet
        int tlU = 0,       tlV = 0;         // top-left
        int tcU = B,       tcV = 0;          // top-center
        int trU = S - B,   trV = 0;          // top-right
        int mlU = 0,       mlV = B;           // middle-left
        int mcU = B,       mcV = B;           // middle-center
        int mrU = S - B,   mrV = B;           // middle-right
        int blU = 0,       blV = S - B;       // bottom-left
        int bcU = B,       bcV = S - B;       // bottom-center
        int brU = S - B,   brV = S - B;       // bottom-right

        int innerW = targetW - 2 * B;
        int innerH = targetH - 2 * B;

        // Top row
        blit(g, sheet, x,           y,           tlU, tlV, B,  B,  S, S); // TL corner
        blit(g, sheet, x + B,       y,           tcU, tcV, innerS, B,  S, S, innerW, B);       // T edge
        blit(g, sheet, x + B + innerW, y,        trU, trV, B,  B,  S, S); // TR corner

        // Middle row
        blit(g, sheet, x,           y + B,       mlU, mlV, B,  innerS, S, S, B, innerH);        // L edge
        blit(g, sheet, x + B,       y + B,       mcU, mcV, innerS, innerS, S, S, innerW, innerH); // Center
        blit(g, sheet, x + B + innerW, y + B,    mrU, mrV, B,  innerS, S, S, B, innerH);        // R edge

        // Bottom row
        blit(g, sheet, x,           y + B + innerH, blU, blV, B,  B,  S, S); // BL corner
        blit(g, sheet, x + B,       y + B + innerH, bcU, bcV, innerS, B,  S, S, innerW, B);       // B edge
        blit(g, sheet, x + B + innerW, y + B + innerH, brU, brV, B,  B,  S, S); // BR corner
    }

    // ── Simple sprite blit (no scaling — 1:1 pixel mapping) ──

    public static void drawSprite(GuiGraphics g, ResourceLocation sheet,
                                   int x, int y, SkinSprite sprite,
                                   int sheetW, int sheetH) {
        blit(g, sheet, x, y, sprite.u(), sprite.v(),
             sprite.width(), sprite.height(), sheetW, sheetH);
    }

    // ── Scaled sprite blit (stretch to target dimensions) ──

    public static void drawSprite(GuiGraphics g, ResourceLocation sheet,
                                   int x, int y, int targetW, int targetH,
                                   SkinSprite sprite, int sheetW, int sheetH) {
        blit(g, sheet, x, y, sprite.u(), sprite.v(),
             sprite.width(), sprite.height(), sheetW, sheetH,
             targetW, targetH);
    }

    // ── Button rendering ──

    public static void drawButton(GuiGraphics g, int x, int y, int w, int h, int state) {
        SkinSprite sprite = SkinSprite.BTN_A_STATES[state];
        drawSprite(g, SkinSprite.BUTTON_A, x, y, w, h,
                   sprite, SkinSprite.BUTTON_A_SHEET_W, SkinSprite.BUTTON_A_SHEET_H);
    }

    // ── Close / icon button ──

    public static void drawCloseButton(GuiGraphics g, int x, int y, int w, int h, int state) {
        SkinSprite sprite = SkinSprite.CLOSE_STATES[state];
        drawSprite(g, SkinSprite.CLOSE_BTN, x, y, w, h, sprite,
                   SkinSprite.CLOSE_SHEET_W, SkinSprite.CLOSE_SHEET_H);
    }

    public static void drawCloseButton(GuiGraphics g, int x, int y, int state) {
        SkinSprite sprite = SkinSprite.CLOSE_STATES[state];
        drawSprite(g, SkinSprite.CLOSE_BTN, x, y, sprite,
                   SkinSprite.CLOSE_SHEET_W, SkinSprite.CLOSE_SHEET_H);
    }

    // ── Header bar (3-part: left cap + stretched center + right cap) ──

    /**
     * Draws the header using 3-part rendering so the decorative end caps
     * stay at their native width while the center stretches to fill.
     */
    public static void drawHeader3Part(GuiGraphics g, int x, int y, int totalW, int h) {
        SkinSprite left = SkinSprite.HEADER_A_LEFT;
        SkinSprite center = SkinSprite.HEADER_A_CENTER;
        SkinSprite right = SkinSprite.HEADER_A_RIGHT;
        int sheetW = SkinSprite.HEADER_A_SHEET_W;
        int sheetH = SkinSprite.HEADER_A_SHEET_H;

        int leftW = left.width();
        int rightW = right.width();
        int centerW = totalW - leftW - rightW;

        // Left cap (fixed width)
        drawSprite(g, SkinSprite.HEADER_A, x, y, leftW, h, left, sheetW, sheetH);
        // Center (stretched)
        if (centerW > 0) {
            drawSprite(g, SkinSprite.HEADER_A, x + leftW, y, centerW, h, center, sheetW, sheetH);
        }
        // Right cap (fixed width)
        drawSprite(g, SkinSprite.HEADER_A, x + leftW + centerW, y, rightW, h, right, sheetW, sheetH);
    }

    /** Simple stretched header (legacy, may look distorted on wide panels). */
    public static void drawHeader(GuiGraphics g, int x, int y, int w, int h) {
        drawHeader3Part(g, x, y, w, h);
    }

    public static void drawHeader(GuiGraphics g, int x, int y, int w) {
        drawHeader3Part(g, x, y, w, SkinSprite.HEADER_A_SPRITE.height());
    }

    // ── Bar (progress / slider track) ──

    public static void drawBar(GuiGraphics g, int x, int y, int w, int h) {
        drawSprite(g, SkinSprite.BAR_A, x, y, w, h,
                   SkinSprite.BAR_A_SPRITE,
                   SkinSprite.BAR_A_SHEET_W, SkinSprite.BAR_A_SHEET_H);
    }

    public static void drawBar(GuiGraphics g, int x, int y, int w) {
        drawBar(g, x, y, w, SkinSprite.BAR_A_SPRITE.height());
    }

    // ── Less / More buttons ──

    public static void drawLessButton(GuiGraphics g, int x, int y, int state) {
        SkinSprite sprite = SkinSprite.LESS_STATES[state];
        drawSprite(g, SkinSprite.LESS_BTN, x, y, sprite,
                   SkinSprite.LESS_SHEET_W, SkinSprite.LESS_SHEET_H);
    }

    public static void drawMoreButton(GuiGraphics g, int x, int y, int state) {
        SkinSprite sprite = SkinSprite.MORE_STATES[state];
        drawSprite(g, SkinSprite.MORE_BTN, x, y, sprite,
                   SkinSprite.MORE_SHEET_W, SkinSprite.MORE_SHEET_H);
    }

    // ── Left / Right arrows ──

    public static void drawLeftArrow(GuiGraphics g, int x, int y, int state) {
        SkinSprite sprite = SkinSprite.LEFT_ARROW_STATES[state];
        drawSprite(g, SkinSprite.LEFT_ARROW, x, y, sprite,
                   SkinSprite.LEFT_ARROW_SHEET_W, SkinSprite.LEFT_ARROW_SHEET_H);
    }

    public static void drawRightArrow(GuiGraphics g, int x, int y, int state) {
        SkinSprite sprite = SkinSprite.RIGHT_ARROW_STATES[state];
        drawSprite(g, SkinSprite.RIGHT_ARROW, x, y, sprite,
                   SkinSprite.RIGHT_ARROW_SHEET_W, SkinSprite.RIGHT_ARROW_SHEET_H);
    }

    // ── Help / Option / Exit buttons ──

    public static void drawHelpButton(GuiGraphics g, int x, int y, int state) {
        SkinSprite sprite = SkinSprite.HELP_STATES[state];
        drawSprite(g, SkinSprite.HELP_BTN, x, y, sprite,
                   SkinSprite.HELP_SHEET_W, SkinSprite.HELP_SHEET_H);
    }

    public static void drawHelpButton(GuiGraphics g, int x, int y, int w, int h, int state) {
        SkinSprite sprite = SkinSprite.HELP_STATES[state];
        drawSprite(g, SkinSprite.HELP_BTN, x, y, w, h, sprite,
                   SkinSprite.HELP_SHEET_W, SkinSprite.HELP_SHEET_H);
    }

    public static void drawOptionButton(GuiGraphics g, int x, int y, int state) {
        SkinSprite sprite = SkinSprite.OPTION_STATES[state];
        drawSprite(g, SkinSprite.OPTION_BTN, x, y, sprite,
                   SkinSprite.OPTION_SHEET_W, SkinSprite.OPTION_SHEET_H);
    }

    public static void drawOptionButton(GuiGraphics g, int x, int y, int w, int h, int state) {
        SkinSprite sprite = SkinSprite.OPTION_STATES[state];
        drawSprite(g, SkinSprite.OPTION_BTN, x, y, w, h, sprite,
                   SkinSprite.OPTION_SHEET_W, SkinSprite.OPTION_SHEET_H);
    }

    public static void drawExitButton(GuiGraphics g, int x, int y, int state) {
        SkinSprite sprite = SkinSprite.EXIT_STATES[state];
        drawSprite(g, SkinSprite.EXIT_BTN, x, y, sprite,
                   SkinSprite.EXIT_SHEET_W, SkinSprite.EXIT_SHEET_H);
    }

    public static void drawExitButton(GuiGraphics g, int x, int y, int w, int h, int state) {
        SkinSprite sprite = SkinSprite.EXIT_STATES[state];
        drawSprite(g, SkinSprite.EXIT_BTN, x, y, w, h, sprite,
                   SkinSprite.EXIT_SHEET_W, SkinSprite.EXIT_SHEET_H);
    }

    // ── Up / Down arrows ──

    public static void drawUpArrow(GuiGraphics g, int x, int y, int state) {
        SkinSprite sprite = SkinSprite.UP_ARROW_STATES[state];
        drawSprite(g, SkinSprite.UP_ARROW, x, y, sprite,
                   SkinSprite.UP_ARROW_SHEET_W, SkinSprite.UP_ARROW_SHEET_H);
    }

    public static void drawUpArrow(GuiGraphics g, int x, int y, int w, int h, int state) {
        SkinSprite sprite = SkinSprite.UP_ARROW_STATES[state];
        drawSprite(g, SkinSprite.UP_ARROW, x, y, w, h, sprite,
                   SkinSprite.UP_ARROW_SHEET_W, SkinSprite.UP_ARROW_SHEET_H);
    }

    public static void drawDownArrow(GuiGraphics g, int x, int y, int state) {
        SkinSprite sprite = SkinSprite.DOWN_ARROW_STATES[state];
        drawSprite(g, SkinSprite.DOWN_ARROW, x, y, sprite,
                   SkinSprite.DOWN_ARROW_SHEET_W, SkinSprite.DOWN_ARROW_SHEET_H);
    }

    public static void drawDownArrow(GuiGraphics g, int x, int y, int w, int h, int state) {
        SkinSprite sprite = SkinSprite.DOWN_ARROW_STATES[state];
        drawSprite(g, SkinSprite.DOWN_ARROW, x, y, w, h, sprite,
                   SkinSprite.DOWN_ARROW_SHEET_W, SkinSprite.DOWN_ARROW_SHEET_H);
    }

    // ── Internal blit helpers ──

    /** Blit a sprite rect at 1:1 scale. */
    private static void blit(GuiGraphics g, ResourceLocation tex,
                             int x, int y,
                             int u, int v, int w, int h,
                             int sheetW, int sheetH) {
        g.blit(tex, x, y, (float) u, (float) v, w, h, sheetW, sheetH);
    }

    /** Blit a sprite rect scaled to {@code targetW × targetH}. */
    private static void blit(GuiGraphics g, ResourceLocation tex,
                             int x, int y,
                             int u, int v, int srcW, int srcH,
                             int sheetW, int sheetH,
                             int targetW, int targetH) {
        g.blit(tex, x, y, targetW, targetH,
               (float) u, (float) v,
               srcW, srcH, sheetW, sheetH);
    }
}
