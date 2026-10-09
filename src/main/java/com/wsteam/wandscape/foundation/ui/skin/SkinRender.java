package com.wsteam.wandscape.foundation.ui.skin;

import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;

import net.minecraft.client.Minecraft;
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

    // ── Button rendering (Code-driven Medieval Aesthetic) ──

    public static void drawButton(GuiGraphics g, int x, int y, int w, int h, int state) {
        if (w <= 0 || h <= 0) return;

        boolean disabled = (state == 3);
        boolean hovered = (state == 1);
        boolean pressed = (state == 2);

        int bgTop, bgBottom, borderColor, innerHighlight, cornerAccent;

        if (disabled) {
            bgTop = 0xAA141722;
            bgBottom = 0xAA0C0E16;
            borderColor = 0x443C4458;
            innerHighlight = 0;
            cornerAccent = 0;
        } else if (pressed) {
            bgTop = 0xF410121A;
            bgBottom = 0xF4080A0E;
            borderColor = MedievalColors.BORDER_GOLD_DARK;
            innerHighlight = 0x20000000;
            cornerAccent = 0x88C8A040;
        } else if (hovered) {
            bgTop = 0xF6252C3E; // 曜石暮晶
            bgBottom = 0xF6161B28;
            borderColor = MedievalColors.BORDER_GOLD_BRIGHT; // 0xFFFFE082 亮金
            innerHighlight = 0x40FFE8A0; // 顶部淡金高光
            cornerAccent = 0xFFFFE8A0;   // 亮金四角铆钉
        } else {
            bgTop = 0xF0181B26; // 深邃曜石板岩
            bgBottom = 0xF010121A;
            borderColor = MedievalColors.BORDER_GOLD_DARK; // 0xFF6E5628 暗金
            innerHighlight = 0x1EFFFFFF;
            cornerAccent = 0x77C8A040;
        }

        // 1. 底色渐变与倒角填充（防直角呆板）
        g.fillGradient(x + 1, y + 1, x + w - 1, y + h - 1, bgTop, bgBottom);
        g.fill(x + 2, y, x + w - 2, y + 1, bgTop);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, bgBottom);
        g.fill(x, y + 2, x + 1, y + h - 2, bgTop);
        g.fill(x + w - 1, y + 2, x + w, y + h - 2, bgBottom);

        // 2. 切角边框（45度切角）
        g.fill(x + 2, y, x + w - 2, y + 1, borderColor);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, borderColor);
        g.fill(x, y + 2, x + 1, y + h - 2, borderColor);
        g.fill(x + w - 1, y + 2, x + w, y + h - 2, borderColor);
        g.fill(x + 1, y + 1, x + 2, y + 2, borderColor);
        g.fill(x + w - 2, y + 1, x + w - 1, y + 2, borderColor);
        g.fill(x + 1, y + h - 2, x + 2, y + h - 1, borderColor);
        g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, borderColor);

        // 3. 悬停外发光晕（1px 微光）
        if (hovered) {
            int glow = 0x44C8A040;
            g.fill(x + 2, y - 1, x + w - 2, y, glow);
            g.fill(x + 2, y + h, x + w - 2, y + h + 1, glow);
            g.fill(x - 1, y + 2, x, y + h - 2, glow);
            g.fill(x + w, y + 2, x + w + 1, y + h - 2, glow);
        }

        // 4. 顶部内高光微光带
        if (innerHighlight != 0 && w >= 6) {
            g.fill(x + 3, y + 1, x + w - 3, y + 2, innerHighlight);
        }

        // 5. 四角金铆钉晶体点缀
        if (cornerAccent != 0 && w >= 10 && h >= 10) {
            g.fill(x + 2, y + 2, x + 3, y + 3, cornerAccent);
            g.fill(x + w - 3, y + 2, x + w - 2, y + 3, cornerAccent);
            g.fill(x + 2, y + h - 3, x + 3, y + h - 2, cornerAccent);
            g.fill(x + w - 3, y + h - 3, x + w - 2, y + h - 2, cornerAccent);
        }

        // 6. 两端精致的微型金色符文折线刻印（Chevron Accent ⟨ ⟩）
        if (!disabled && w >= 44 && h >= 12) {
            int cy = y + h / 2;
            int chevColor = hovered ? 0xFFFFDF7A : 0x669A7A38;
            // 左折角 ⟨
            g.fill(x + 6, cy - 2, x + 7, cy - 1, chevColor);
            g.fill(x + 5, cy - 1, x + 6, cy + 1, chevColor);
            g.fill(x + 6, cy + 1, x + 7, cy + 2, chevColor);
            // 右折角 ⟩
            g.fill(x + w - 7, cy - 2, x + w - 6, cy - 1, chevColor);
            g.fill(x + w - 6, cy - 1, x + w - 5, cy + 1, chevColor);
            g.fill(x + w - 7, cy + 1, x + w - 6, cy + 2, chevColor);
        }
    }

    // ── Close / icon button (Code-driven Gem Badge) ──

    public static void drawCloseButton(GuiGraphics g, int x, int y, int w, int h, int state) {
        if (w <= 0 || h <= 0) return;

        boolean disabled = (state == 3);
        boolean hovered = (state == 1);

        int bgTop, bgBottom, borderColor, innerHighlight;
        if (disabled) {
            bgTop = 0xAA141722;
            bgBottom = 0xAA0C0E16;
            borderColor = 0x443C4458;
            innerHighlight = 0;
        } else if (hovered) {
            // 悬停：典雅石榴石/血珀晶石深红微光
            bgTop = 0xF28A1828;
            bgBottom = 0xF24E0C16;
            borderColor = 0xFFFF8A80;
            innerHighlight = 0x50FFFFFF;
        } else {
            bgTop = 0xDD161924;
            bgBottom = 0xDD0E1018;
            borderColor = 0xFF6E5628;
            innerHighlight = 0x1AFFFFFF;
        }

        // 1. 底色与切角几何体
        g.fillGradient(x + 1, y + 1, x + w - 1, y + h - 1, bgTop, bgBottom);
        g.fill(x + 2, y, x + w - 2, y + 1, bgTop);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, bgBottom);
        g.fill(x, y + 2, x + 1, y + h - 2, bgTop);
        g.fill(x + w - 1, y + 2, x + w, y + h - 2, bgBottom);

        // 2. 切角边框
        g.fill(x + 2, y, x + w - 2, y + 1, borderColor);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, borderColor);
        g.fill(x, y + 2, x + 1, y + h - 2, borderColor);
        g.fill(x + w - 1, y + 2, x + w, y + h - 2, borderColor);
        g.fill(x + 1, y + 1, x + 2, y + 2, borderColor);
        g.fill(x + w - 2, y + 1, x + w - 1, y + 2, borderColor);
        g.fill(x + 1, y + h - 2, x + 2, y + h - 1, borderColor);
        g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, borderColor);

        // 3. 悬停外发光
        if (hovered) {
            int glow = 0x44E53935;
            g.fill(x + 2, y - 1, x + w - 2, y, glow);
            g.fill(x + 2, y + h, x + w - 2, y + h + 1, glow);
            g.fill(x - 1, y + 2, x, y + h - 2, glow);
            g.fill(x + w, y + 2, x + w + 1, y + h - 2, glow);
        }

        // 4. 顶部微光线
        if (innerHighlight != 0 && w >= 6) {
            g.fill(x + 2, y + 1, x + w - 2, y + 2, innerHighlight);
        }

        // 5. 中间十字交叉 "×"（像素对称几何绘制）
        int cx = x + w / 2;
        int cy = y + h / 2;
        int crossColor = disabled ? 0xFF545C6C : (hovered ? 0xFFFFFFFF : 0xFFC8A040);
        for (int i = -2; i <= 2; i++) {
            g.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, crossColor);
            g.fill(cx + i, cy - i, cx + i + 1, cy - i + 1, crossColor);
        }
    }

    public static void drawCloseButton(GuiGraphics g, int x, int y, int state) {
        drawCloseButton(g, x, y, 14, 14, state);
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

    // ── Code-driven Badge Frame (Shared by gem tools, arrows, steppers) ──

    public static void drawBadgeFrame(GuiGraphics g, int x, int y, int w, int h, int state) {
        if (w <= 0 || h <= 0) return;

        boolean disabled = (state == 3 || state == 2);
        boolean hovered = (state == 1);

        int bgTop, bgBottom, borderColor, innerHighlight;
        if (disabled) {
            bgTop = 0xAA141722;
            bgBottom = 0xAA0C0E16;
            borderColor = 0x443C4458;
            innerHighlight = 0;
        } else if (hovered) {
            bgTop = 0xF0252C3E; // 曜石暮晶
            bgBottom = 0xF0161B28;
            borderColor = MedievalColors.BORDER_GOLD_BRIGHT; // 亮金
            innerHighlight = 0x40FFE8A0;
        } else {
            bgTop = 0xDD161924; // 深邃板岩
            bgBottom = 0xDD0E1018;
            borderColor = MedievalColors.BORDER_GOLD_DARK; // 0xFF6E5628
            innerHighlight = 0x1AFFFFFF;
        }

        // 1. 底色渐变与切角几何体
        g.fillGradient(x + 1, y + 1, x + w - 1, y + h - 1, bgTop, bgBottom);
        g.fill(x + 2, y, x + w - 2, y + 1, bgTop);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, bgBottom);
        g.fill(x, y + 2, x + 1, y + h - 2, bgTop);
        g.fill(x + w - 1, y + 2, x + w, y + h - 2, bgBottom);

        // 2. 切角边框
        g.fill(x + 2, y, x + w - 2, y + 1, borderColor);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, borderColor);
        g.fill(x, y + 2, x + 1, y + h - 2, borderColor);
        g.fill(x + w - 1, y + 2, x + w, y + h - 2, borderColor);
        g.fill(x + 1, y + 1, x + 2, y + 2, borderColor);
        g.fill(x + w - 2, y + 1, x + w - 1, y + 2, borderColor);
        g.fill(x + 1, y + h - 2, x + 2, y + h - 1, borderColor);
        g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, borderColor);

        // 3. 悬停外发光
        if (hovered) {
            int glow = 0x44C8A040;
            g.fill(x + 2, y - 1, x + w - 2, y, glow);
            g.fill(x + 2, y + h, x + w - 2, y + h + 1, glow);
            g.fill(x - 1, y + 2, x, y + h - 2, glow);
            g.fill(x + w, y + 2, x + w + 1, y + h - 2, glow);
        }

        // 4. 顶部微高光线
        if (innerHighlight != 0 && w >= 6) {
            g.fill(x + 2, y + 1, x + w - 2, y + 2, innerHighlight);
        }
    }

    // ── Less / More buttons (Code-driven Steppers) ──

    public static void drawLessButton(GuiGraphics g, int x, int y, int w, int h, int state) {
        drawBadgeFrame(g, x, y, w, h, state);
        boolean disabled = (state == 3 || state == 2);
        boolean hovered = (state == 1);
        int color = disabled ? 0xFF545C6C : (hovered ? 0xFFFFDF7A : 0xFFC8A040);
        int cx = x + w / 2;
        int cy = y + h / 2;
        g.fill(cx - 2, cy - 1, cx + 3, cy + 1, color);
    }

    public static void drawLessButton(GuiGraphics g, int x, int y, int state) {
        drawLessButton(g, x, y, 14, 14, state);
    }

    public static void drawMoreButton(GuiGraphics g, int x, int y, int w, int h, int state) {
        drawBadgeFrame(g, x, y, w, h, state);
        boolean disabled = (state == 3 || state == 2);
        boolean hovered = (state == 1);
        int color = disabled ? 0xFF545C6C : (hovered ? 0xFFFFDF7A : 0xFFC8A040);
        int cx = x + w / 2;
        int cy = y + h / 2;
        g.fill(cx - 2, cy - 1, cx + 3, cy + 1, color);
        g.fill(cx - 1, cy - 2, cx + 1, cy + 3, color);
    }

    public static void drawMoreButton(GuiGraphics g, int x, int y, int state) {
        drawMoreButton(g, x, y, 14, 14, state);
    }

    // ── Left / Right arrows (Code-driven Precision Geometric Accents) ──

    public static void drawLeftArrow(GuiGraphics g, int x, int y, int w, int h, int state) {
        drawBadgeFrame(g, x, y, w, h, state);
        boolean disabled = (state == 3 || state == 2);
        boolean hovered = (state == 1);
        int color = disabled ? 0xFF545C6C : (hovered ? 0xFFFFDF7A : 0xFFC8A040);
        int cx = x + w / 2;
        int cy = y + h / 2;
        g.fill(cx - 2, cy, cx - 1, cy + 1, color);
        g.fill(cx - 1, cy - 1, cx, cy + 2, color);
        g.fill(cx, cy - 2, cx + 1, cy + 3, color);
        g.fill(cx + 1, cy - 3, cx + 2, cy - 1, color);
        g.fill(cx + 1, cy + 2, cx + 2, cy + 4, color);
    }

    public static void drawLeftArrow(GuiGraphics g, int x, int y, int state) {
        drawLeftArrow(g, x, y, 20, 14, state);
    }

    public static void drawRightArrow(GuiGraphics g, int x, int y, int w, int h, int state) {
        drawBadgeFrame(g, x, y, w, h, state);
        boolean disabled = (state == 3 || state == 2);
        boolean hovered = (state == 1);
        int color = disabled ? 0xFF545C6C : (hovered ? 0xFFFFDF7A : 0xFFC8A040);
        int cx = x + w / 2;
        int cy = y + h / 2;
        g.fill(cx + 2, cy, cx + 3, cy + 1, color);
        g.fill(cx + 1, cy - 1, cx + 2, cy + 2, color);
        g.fill(cx, cy - 2, cx + 1, cy + 3, color);
        g.fill(cx - 1, cy - 3, cx, cy - 1, color);
        g.fill(cx - 1, cy + 2, cx, cy + 4, color);
    }

    public static void drawRightArrow(GuiGraphics g, int x, int y, int state) {
        drawRightArrow(g, x, y, 20, 14, state);
    }

    // ── Help button (Code-driven Gem Badge) ──

    public static void drawHelpButton(GuiGraphics g, int x, int y, int w, int h, int state) {
        if (w <= 0 || h <= 0) return;

        boolean disabled = (state == 3);
        boolean hovered = (state == 1);

        int bgTop, bgBottom, borderColor, innerHighlight;
        if (disabled) {
            bgTop = 0xAA141722;
            bgBottom = 0xAA0C0E16;
            borderColor = 0x443C4458;
            innerHighlight = 0;
        } else if (hovered) {
            // 悬停：奥术星空蓝晶石深邃微光
            bgTop = 0xF2184880;
            bgBottom = 0xF20C2850;
            borderColor = 0xFF80D8FF;
            innerHighlight = 0x50FFFFFF;
        } else {
            bgTop = 0xDD161924;
            bgBottom = 0xDD0E1018;
            borderColor = 0xFF6E5628;
            innerHighlight = 0x1AFFFFFF;
        }

        // 1. 底色与切角几何体
        g.fillGradient(x + 1, y + 1, x + w - 1, y + h - 1, bgTop, bgBottom);
        g.fill(x + 2, y, x + w - 2, y + 1, bgTop);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, bgBottom);
        g.fill(x, y + 2, x + 1, y + h - 2, bgTop);
        g.fill(x + w - 1, y + 2, x + w, y + h - 2, bgBottom);

        // 2. 切角边框
        g.fill(x + 2, y, x + w - 2, y + 1, borderColor);
        g.fill(x + 2, y + h - 1, x + w - 2, y + h, borderColor);
        g.fill(x, y + 2, x + 1, y + h - 2, borderColor);
        g.fill(x + w - 1, y + 2, x + w, y + h - 2, borderColor);
        g.fill(x + 1, y + 1, x + 2, y + 2, borderColor);
        g.fill(x + w - 2, y + 1, x + w - 1, y + 2, borderColor);
        g.fill(x + 1, y + h - 2, x + 2, y + h - 1, borderColor);
        g.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, borderColor);

        // 3. 悬停外发光
        if (hovered) {
            int glow = 0x44389BFF;
            g.fill(x + 2, y - 1, x + w - 2, y, glow);
            g.fill(x + 2, y + h, x + w - 2, y + h + 1, glow);
            g.fill(x - 1, y + 2, x, y + h - 2, glow);
            g.fill(x + w, y + 2, x + w + 1, y + h - 2, glow);
        }

        // 4. 顶部微光线
        if (innerHighlight != 0 && w >= 6) {
            g.fill(x + 2, y + 1, x + w - 2, y + 2, innerHighlight);
        }

        // 5. 中间 "?" 居中绘制
        var font = Minecraft.getInstance().font;
        int textColor = disabled ? 0xFF545C6C : (hovered ? 0xFFFFFFFF : 0xFFC8A040);
        int textY = y + (h - font.lineHeight) / 2 + 1;
        g.drawCenteredString(font, "?", x + w / 2, textY, textColor);
    }

    public static void drawHelpButton(GuiGraphics g, int x, int y, int state) {
        drawHelpButton(g, x, y, 14, 14, state);
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

    // ── Up / Down arrows (Code-driven Precision Geometric Accents) ──

    public static void drawUpArrow(GuiGraphics g, int x, int y, int w, int h, int state) {
        drawBadgeFrame(g, x, y, w, h, state);
        boolean disabled = (state == 3 || state == 2);
        boolean hovered = (state == 1);
        int color = disabled ? 0xFF545C6C : (hovered ? 0xFFFFDF7A : 0xFFC8A040);
        int cx = x + w / 2;
        int cy = y + h / 2;
        g.fill(cx, cy - 2, cx + 1, cy - 1, color);
        g.fill(cx - 1, cy - 1, cx + 2, cy, color);
        g.fill(cx - 2, cy, cx + 3, cy + 1, color);
        g.fill(cx - 3, cy + 1, cx - 1, cy + 2, color);
        g.fill(cx + 2, cy + 1, cx + 4, cy + 2, color);
    }

    public static void drawUpArrow(GuiGraphics g, int x, int y, int state) {
        drawUpArrow(g, x, y, 14, 14, state);
    }

    public static void drawDownArrow(GuiGraphics g, int x, int y, int w, int h, int state) {
        drawBadgeFrame(g, x, y, w, h, state);
        boolean disabled = (state == 3 || state == 2);
        boolean hovered = (state == 1);
        int color = disabled ? 0xFF545C6C : (hovered ? 0xFFFFDF7A : 0xFFC8A040);
        int cx = x + w / 2;
        int cy = y + h / 2;
        g.fill(cx - 3, cy - 2, cx - 1, cy - 1, color);
        g.fill(cx + 2, cy - 2, cx + 4, cy - 1, color);
        g.fill(cx - 2, cy - 1, cx + 3, cy, color);
        g.fill(cx - 1, cy, cx + 2, cy + 1, color);
        g.fill(cx, cy + 1, cx + 1, cy + 2, color);
    }

    public static void drawDownArrow(GuiGraphics g, int x, int y, int state) {
        drawDownArrow(g, x, y, 14, 14, state);
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
