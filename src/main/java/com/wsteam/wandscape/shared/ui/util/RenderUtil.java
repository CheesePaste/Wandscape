package com.wsteam.wandscape.shared.ui.util;

import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Stateless rendering helpers used by themed widgets and screens.
 */
public final class RenderUtil {

    private RenderUtil() {}

    /** Border width in pixels. */
    public static final int BORDER_WIDTH = 2;

    /** Corner decoration size in pixels. */
    public static final int CORNER_SIZE = 4;

    /**
     * Draw a two-tone panel background with parchment gradient and gold border.
     * Call from {@code Screen.renderBackground} or widget {@code renderWidget}.
     */
    public static void drawPanelBg(GuiGraphics g, int x, int y, int width, int height) {
        // Vertical gradient: lighter center → darker edges
        int midY = y + height / 2;
        int halfH = height / 2;

        g.fillGradient(x, y, x + width, y + halfH,
                MedievalColors.PARCHMENT_DARK, MedievalColors.PARCHMENT_MID);
        g.fillGradient(x, y + halfH, x + width, y + height,
                MedievalColors.PARCHMENT_MID, MedievalColors.PARCHMENT_DARK);

        // Additional horizontal gradient overlay for 4-corner darkening
        int midX = x + width / 2;
        int halfW = width / 2;

        // Left fade
        g.fillGradient(x, y, x + halfW, y + halfH,
                MedievalColors.PARCHMENT_DEEPEST, MedievalColors.PARCHMENT_DARK);
        g.fillGradient(x, y + halfH, x + halfW, y + height,
                MedievalColors.PARCHMENT_DARK, MedievalColors.PARCHMENT_DEEPEST);

        // Right fade
        g.fillGradient(x + halfW, y, x + width, y + halfH,
                MedievalColors.PARCHMENT_DARK, MedievalColors.PARCHMENT_DEEPEST);
        g.fillGradient(x + halfW, y + halfH, x + width, y + height,
                MedievalColors.PARCHMENT_DEEPEST, MedievalColors.PARCHMENT_DARK);
    }

    /**
     * Draw a two-layer decorative gold border around the panel.
     * Outer border: dark gold, inner border: bright gold.
     */
    public static void drawPanelBorder(GuiGraphics g, int x, int y, int width, int height) {
        // Outer border (dark gold)
        g.fill(x, y, x + width, y + 1, MedievalColors.BORDER_GOLD_DARK);
        g.fill(x, y + height - 1, x + width, y + height, MedievalColors.BORDER_GOLD_DARK);
        g.fill(x, y, x + 1, y + height, MedievalColors.BORDER_GOLD_DARK);
        g.fill(x + width - 1, y, x + width, y + height, MedievalColors.BORDER_GOLD_DARK);

        // Inner border (bright gold) — inset by 1px
        int inner = 1;
        g.fill(x + inner, y + inner, x + width - inner, y + inner + 1, MedievalColors.BORDER_GOLD);
        g.fill(x + inner, y + height - inner - 1, x + width - inner, y + height - inner, MedievalColors.BORDER_GOLD);
        g.fill(x + inner, y + inner, x + inner + 1, y + height - inner, MedievalColors.BORDER_GOLD);
        g.fill(x + width - inner - 1, y + inner, x + width - inner, y + height - inner, MedievalColors.BORDER_GOLD);
    }

    /**
     * Draw small gold squares at the four corners of the panel as decorations.
     */
    public static void drawCornerDecorations(GuiGraphics g, int x, int y, int width, int height) {
        int r = CORNER_SIZE;
        int color = MedievalColors.CORNER_DECORATION;
        // Top-left
        g.fill(x + 3, y + 3, x + 3 + r, y + 3 + r, color);
        // Top-right
        g.fill(x + width - 3 - r, y + 3, x + width - 3, y + 3 + r, color);
        // Bottom-left
        g.fill(x + 3, y + height - 3 - r, x + 3 + r, y + height - 3, color);
        // Bottom-right
        g.fill(x + width - 3 - r, y + height - 3 - r, x + width - 3, y + height - 3, color);
    }

    /**
     * Draw a decorative horizontal line with small end caps.
     * Useful for section dividers.
     */
    public static void drawHLineDecorative(GuiGraphics g, int x, int y, int width) {
        // End caps
        g.fill(x, y - 1, x + 3, y + 2, MedievalColors.BORDER_GOLD);
        g.fill(x + width - 3, y - 1, x + width, y + 2, MedievalColors.BORDER_GOLD);
        // Line
        g.fill(x + 3, y, x + width - 3, y + 1, MedievalColors.BORDER_GOLD_DARK);
    }

    /**
     * Draw the title bar background at the top of a panel.
     */
    public static void drawTitleBar(GuiGraphics g, net.minecraft.client.gui.Font font,
                                    int x, int y, int width, int height, String title) {
        g.fill(x, y, x + width, y + height, MedievalColors.PANEL_TITLE_BG);
        g.fill(x, y + height - 1, x + width, y + height, MedievalColors.PURPLE_BORDER);
        g.drawCenteredString(font, title,
                x + width / 2, y + (height - 9) / 2, MedievalColors.ACCENT_GOLD);
    }

    /**
     * Draw a scrollbar at the right edge of a content area.
     *
     * @param contentHeight total height of all content
     * @param viewHeight    visible area height
     * @param scrollOffset  current scroll position
     */
    public static void drawScrollbar(GuiGraphics g, int x, int y,
                                      int barWidth, int viewHeight,
                                      int contentHeight, int scrollOffset) {
        if (contentHeight <= viewHeight) return;

        // Track
        g.fill(x, y, x + barWidth, y + viewHeight, MedievalColors.SCROLLBAR_TRACK);

        // Thumb
        int thumbHeight = Math.max(8, viewHeight * viewHeight / contentHeight);
        int maxScroll = contentHeight - viewHeight;
        int thumbY = y + (maxScroll == 0 ? 0 : scrollOffset * (viewHeight - thumbHeight) / maxScroll);
        g.fill(x + 1, thumbY, x + barWidth - 1, thumbY + thumbHeight, MedievalColors.SCROLLBAR_THUMB);
    }

    /**
     * Format a large number for display. E.g. 15200 → "15.2K", 1500000 → "1.5M".
     */
    public static String formatLargeNumber(long value) {
        if (value < 1_000) return String.valueOf(value);
        if (value < 1_000_000) return String.format("%.1fK", value / 1_000.0);
        return String.format("%.1fM", value / 1_000_000.0);
    }
}
