package com.wsteam.wandscape.shared.ui.guidance;

import java.util.List;

import com.wsteam.wandscape.shared.ui.panel.BuildingSelectionOverlay;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;

/**
 * Renders the onboarding guidance box (dark background, gold border) at the
 * bottom-right corner of the screen. Supports clicking to collapse/expand.
 */
public final class GuideRenderer {

    private GuideRenderer() {}

    private static final int BOX_BG = 0xEE14161B;
    private static final int BOX_BORDER = 0xFFD4A338;
    private static final int BOX_TITLE = 0xFFFFD700;
    private static final int BOX_LINE = 0xFFFFFFFF;
    private static final int BOX_HINT = 0xFFFFD84A;
    private static final int BOX_DIVIDER = 0x44D4A338;
    private static final int BTN_HOVER_BG = 0x55FF4444;
    private static final int BTN_TOGGLE_HOVER_BG = 0x554488FF;
    private static final int BTN_IDLE = 0xAA888888;
    private static final int BTN_HOVER = 0xFFFFFFFF;

    private record Box(int x, int y, int w, int h,
                       int closeX, int closeY, int closeS,
                       int toggleX, int toggleY, int toggleS,
                       String title, List<String> lines, String hint, boolean collapsed) {}

    private static Box layout(Font font, int screenW, int screenH, GuideStep step,
                              boolean buildMode, boolean isPlacing, boolean isBar, boolean isPinned) {
        int pad = 6;
        int lineH = font.lineHeight;
        boolean collapsed = GuideSession.isCollapsed();
        List<String> lines = step.linesFor(buildMode, isPlacing, isBar, isPinned);
        String hint = step.hint();

        String titleStr = step.title() + (collapsed ? " §e[点击展开]" : "");
        int maxW = font.width(titleStr);
        if (!collapsed) {
            for (String l : lines) {
                maxW = Math.max(maxW, font.width(l));
            }
            maxW = Math.max(maxW, font.width(hint));
        }

        int boxW = maxW + pad * 2 + 24;
        int boxH = collapsed ? (pad * 2 + lineH) : (pad * 2 + lineH * (lines.size() + 2) + 6);

        // Right side of the screen (bottom-right, above the build bar when open).
        int margin = 8;
        int x = screenW - boxW - margin;
        int bottomMargin = (buildMode && isBar) ? BuildingSelectionOverlay.BAR_HEIGHT + 8 : 8;
        int y = screenH - bottomMargin - boxH;

        int btnS = 9;
        int closeX = x + boxW - btnS - 7;
        int closeY = y + 6;

        int toggleX = closeX - btnS - 6;
        int toggleY = y + 6;

        return new Box(x, y, boxW, boxH, closeX, closeY, btnS, toggleX, toggleY, btnS, step.title(), lines, hint, collapsed);
    }

    public static boolean isCloseClicked(Font font, double mx, double my, int screenW, int screenH,
                                         GuideStep step, boolean buildMode, boolean isPlacing, boolean isBar, boolean isPinned) {
        Box b = layout(font, screenW, screenH, step, buildMode, isPlacing, isBar, isPinned);
        return mx >= b.closeX - 2 && mx <= b.closeX + b.closeS + 2
                && my >= b.closeY - 2 && my <= b.closeY + b.closeS + 2;
    }

    public static boolean isCollapseClicked(Font font, double mx, double my, int screenW, int screenH,
                                            GuideStep step, boolean buildMode, boolean isPlacing, boolean isBar, boolean isPinned) {
        Box b = layout(font, screenW, screenH, step, buildMode, isPlacing, isBar, isPinned);
        if (b.collapsed) {
            boolean overBox = mx >= b.x && mx <= b.x + b.w && my >= b.y && my <= b.y + b.h;
            boolean overClose = mx >= b.closeX - 2 && mx <= b.closeX + b.closeS + 2 && my >= b.closeY - 2 && my <= b.closeY + b.closeS + 2;
            return overBox && !overClose;
        }
        return mx >= b.toggleX - 2 && mx <= b.toggleX + b.toggleS + 2
                && my >= b.toggleY - 2 && my <= b.toggleY + b.toggleS + 2;
    }

    public static void render(GuiGraphics g, Font font, int screenW, int screenH, double mx, double my,
                              GuideStep step,
                              boolean buildMode, boolean isPlacing, boolean isBar, boolean isPinned) {
        Box b = layout(font, screenW, screenH, step, buildMode, isPlacing, isBar, isPinned);
        int pad = 6;
        int lineH = font.lineHeight;

        // Background
        g.fill(RenderType.guiOverlay(), b.x, b.y, b.x + b.w, b.y + b.h, 0, BOX_BG);
        // Border
        g.fill(RenderType.guiOverlay(), b.x, b.y, b.x + b.w, b.y + 1, 0, BOX_BORDER);
        g.fill(RenderType.guiOverlay(), b.x, b.y + b.h - 1, b.x + b.w, b.y + b.h, 0, BOX_BORDER);
        g.fill(RenderType.guiOverlay(), b.x, b.y, b.x + 1, b.y + b.h, 0, BOX_BORDER);
        g.fill(RenderType.guiOverlay(), b.x + b.w - 1, b.y, b.x + b.w, b.y + b.h, 0, BOX_BORDER);

        int tx = b.x + pad;
        int ty = b.y + pad;

        if (b.collapsed) {
            drawText(g, font, b.title + " §e[点击展开]", tx, ty, BOX_TITLE);
        } else {
            drawText(g, font, b.title, tx, ty, BOX_TITLE);
            ty += lineH + 2;
            g.fill(RenderType.guiOverlay(), tx, ty - 2, b.x + b.w - pad * 2, ty - 1, 0, BOX_DIVIDER);

            for (String line : b.lines) {
                drawText(g, font, line, tx, ty, BOX_LINE);
                ty += lineH + 1;
            }

            ty += 2;
            drawText(g, font, b.hint, tx, ty, BOX_HINT);
        }

        // Toggle (▼ / ▲) button
        boolean hoverToggle = isCollapseClicked(font, mx, my, screenW, screenH, step, buildMode, isPlacing, isBar, isPinned);
        if (hoverToggle) {
            g.fill(RenderType.guiOverlay(), b.toggleX - 2, b.toggleY - 2,
                    b.toggleX + b.toggleS + 2, b.toggleY + b.toggleS + 2, 0, BTN_TOGGLE_HOVER_BG);
        }
        String toggleIcon = b.collapsed ? "▲" : "▼";
        drawText(g, font, toggleIcon, b.toggleX + 1, b.toggleY, hoverToggle ? BTN_HOVER : BTN_IDLE);

        // Close (×) button
        boolean hoverClose = isCloseClicked(font, mx, my, screenW, screenH, step, buildMode, isPlacing, isBar, isPinned);
        if (hoverClose) {
            g.fill(RenderType.guiOverlay(), b.closeX - 2, b.closeY - 2,
                    b.closeX + b.closeS + 2, b.closeY + b.closeS + 2, 0, BTN_HOVER_BG);
        }
        drawText(g, font, "×", b.closeX + 1, b.closeY, hoverClose ? BTN_HOVER : BTN_IDLE);
    }

    private static void drawText(GuiGraphics g, Font font, String text, float x, float y, int color) {
        font.drawInBatch(text, x, y, color, false,
                g.pose().last().pose(), g.bufferSource(),
                Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
    }
}
