package com.wsteam.wandscape.shared.ui.guidance;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;

/**
 * Renders the onboarding guidance box (dark background, gold border) top-right
 * of the screen. Pure layout/render + hit-test — no panel-state dependency.
 * Visual style is intentionally identical to the original V-panel guidance box.
 */
public final class GuideRenderer {

    private GuideRenderer() {}

    private static final int BOX_BG = 0xEE14161B;
    private static final int BOX_BORDER = 0xFFD4A338;
    private static final int BOX_TITLE = 0xFFFFD700;
    private static final int BOX_LINE = 0xFFFFFFFF;
    private static final int BOX_HINT = 0xFFAAAAAA;
    private static final int BOX_DIVIDER = 0x44D4A338;
    private static final int CLOSE_HOVER_BG = 0x55FF4444;
    private static final int CLOSE_IDLE = 0xAA888888;
    private static final int CLOSE_HOVER = 0xFFFFFFFF;

    /** Single source of truth for layout, shared by render + hit-test. */
    private record Box(int x, int y, int w, int h, int closeX, int closeY, int closeS,
                       String title, List<String> lines, String hint) {}

    private static Box layout(Font font, int screenW, int topBarH, GuideStep step,
                              boolean buildMode, boolean isPlacing, boolean isBar) {
        int pad = 10;
        int lineH = font.lineHeight;
        List<String> lines = step.linesFor(buildMode, isPlacing, isBar);

        int maxW = font.width(step.title());
        for (String l : lines) {
            maxW = Math.max(maxW, font.width(l));
        }
        maxW = Math.max(maxW, font.width(step.hint()));

        int boxW = maxW + pad * 2 + 12;
        int boxH = pad * 2 + lineH * (lines.size() + 2) + 12;
        int x = screenW - boxW - 8;
        int y = topBarH + 4;

        int closeS = 9;
        int closeX = x + boxW - closeS - 7;
        int closeY = y + 6;
        return new Box(x, y, boxW, boxH, closeX, closeY, closeS, step.title(), lines, step.hint());
    }

    /** @return true if the mouse is over the guidance close (×) button. */
    public static boolean isCloseClicked(Font font, double mx, double my, int screenW, int topBarH,
                                         GuideStep step, boolean buildMode, boolean isPlacing, boolean isBar) {
        Box b = layout(font, screenW, topBarH, step, buildMode, isPlacing, isBar);
        return mx >= b.closeX && mx <= b.closeX + b.closeS
                && my >= b.closeY && my <= b.closeY + b.closeS;
    }

    public static void render(GuiGraphics g, Font font, int screenW, double mx, double my,
                              int topBarH, GuideStep step,
                              boolean buildMode, boolean isPlacing, boolean isBar) {
        Box b = layout(font, screenW, topBarH, step, buildMode, isPlacing, isBar);
        int pad = 10;
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
        drawText(g, font, b.title, tx, ty, BOX_TITLE);
        ty += lineH + 5;
        g.fill(RenderType.guiOverlay(), tx, ty - 2, b.x + b.w - pad * 2, ty - 1, 0, BOX_DIVIDER);

        for (String line : b.lines) {
            drawText(g, font, line, tx, ty, BOX_LINE);
            ty += lineH + 2;
        }

        ty += 3;
        drawText(g, font, b.hint, tx, ty, BOX_HINT);

        // Close (×) button, top-right
        boolean hover = isCloseClicked(font, mx, my, screenW, topBarH, step, buildMode, isPlacing, isBar);
        if (hover) {
            g.fill(RenderType.guiOverlay(), b.closeX - 2, b.closeY - 2,
                    b.closeX + b.closeS + 2, b.closeY + b.closeS + 2, 0, CLOSE_HOVER_BG);
        }
        drawText(g, font, "×", b.closeX + 1, b.closeY, hover ? CLOSE_HOVER : CLOSE_IDLE);
    }

    private static void drawText(GuiGraphics g, Font font, String text, float x, float y, int color) {
        font.drawInBatch(text, x, y, color, false,
                g.pose().last().pose(), g.bufferSource(),
                Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
    }
}
