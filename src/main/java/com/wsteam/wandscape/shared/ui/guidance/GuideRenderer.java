package com.wsteam.wandscape.shared.ui.guidance;

import java.util.ArrayList;
import java.util.List;

import com.wsteam.wandscape.shared.ui.panel.WandscapePanelOverlay;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Renders the onboarding guidance box (dark background, gold border) at the
 * top-right corner, its top edge aligned with the top bar. The whole box is
 * drawn at {@link #SCALE} so text and layout shrink together. Supports clicking
 * to collapse/expand.
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

    /** Inner padding; kept small so the box stays compact. */
    private static final int PAD = 4;
    /** Uniform shrink of the whole box (text + background together). */
    private static final float SCALE = 0.8f;
    /** Lines longer than this wrap (logical px, pre-scale). */
    private static final int MAX_CONTENT_WIDTH = 300;
    /** Room reserved on the right for the ▶/× buttons. */
    private static final int BTN_EXTRA = 28;

    private record Box(int x, int y, int w, int h,
                       int closeX, int closeY, int closeS,
                       int toggleX, int toggleY, int toggleS,
                       int wrapW, String title, List<String> lines, String hint, boolean collapsed) {}

    private static Box layout(Font font, int screenW, int screenH, GuideStep step,
                              boolean buildMode, boolean isPlacing, boolean isBar, boolean isPinned) {
        int pad = PAD;
        boolean collapsed = GuideSession.isCollapsed();
        int topY = Math.round(WandscapePanelOverlay.TOP_BAR_H / SCALE);
        int scaledW = Math.round(screenW / SCALE);

        if (collapsed) {
            // Compact tab in the top-right corner — only the expand triangle.
            int s = font.lineHeight + pad * 2 + 2;
            int x = scaledW - s;
            int y = topY;
            return new Box(x, y, s, s, 0, 0, 0, 0, 0, 9, 0, "", List.of(), "", true);
        }

        List<String> lines = step.linesFor(buildMode, isPlacing, isBar, isPinned);
        String hint = step.hint();

        // Layout is in pose space (pre-scale): the font draws at logical size there, so
        // text widths/heights stay logical — the pose scale in render() shrinks everything.
        // Wrap at one consistent logical width, then size the box to the widest wrapped
        // piece so no line spills past the flush-right edge.
        int wrapW = Math.min(naturalWidth(font, step.title(), lines, hint), MAX_CONTENT_WIDTH);
        int maxLogicalW = 0;
        int rows = 0;
        for (FormattedCharSequence piece : wrap(font, step.title(), wrapW)) {
            rows++;
            maxLogicalW = Math.max(maxLogicalW, font.width(piece));
        }
        for (String l : lines) {
            for (FormattedCharSequence piece : wrap(font, l, wrapW)) {
                rows++;
                maxLogicalW = Math.max(maxLogicalW, font.width(piece));
            }
        }
        for (FormattedCharSequence piece : wrap(font, hint, wrapW)) {
            rows++;
            maxLogicalW = Math.max(maxLogicalW, font.width(piece));
        }

        int boxW = maxLogicalW + pad * 2 + BTN_EXTRA;
        // Each drawn row advances lineHeight+1, plus a 1px divider gap and 2px hint gap.
        int boxH = pad * 2 + rows * (font.lineHeight + 1) + 3;

        // Top-right corner, flush against the right edge, just below the top bar.
        int x = scaledW - boxW;
        int y = topY;

        int btnS = 9;
        int closeX = x + boxW - btnS - 7;
        int closeY = y + 6;

        int toggleX = closeX - btnS - 6;
        int toggleY = y + 6;

        return new Box(x, y, boxW, boxH, closeX, closeY, btnS, toggleX, toggleY, btnS, wrapW, step.title(), lines, hint, false);
    }

    /** Widest single-line width among the title, lines and hint (logical px). */
    private static int naturalWidth(Font font, String title, List<String> lines, String hint) {
        int w = font.width(title);
        for (String l : lines) {
            w = Math.max(w, font.width(l));
        }
        return Math.max(w, font.width(hint));
    }

    /** Split formatted text into lines: explicit {@code \n} breaks first, then width-based wrap. */
    private static List<FormattedCharSequence> wrap(Font font, String text, int width) {
        List<FormattedCharSequence> out = new ArrayList<>();
        for (String piece : text.split("\n")) {
            out.addAll(font.split(Component.literal(piece), width));
        }
        return out;
    }

    public static boolean isCloseClicked(Font font, double mx, double my, int screenW, int screenH,
                                         GuideStep step, boolean buildMode, boolean isPlacing, boolean isBar, boolean isPinned) {
        Box b = layout(font, screenW, screenH, step, buildMode, isPlacing, isBar, isPinned);
        if (b.collapsed) return false; // no close button on the collapsed tab
        double sx = mx / SCALE;
        double sy = my / SCALE;
        return sx >= b.closeX - 2 && sx <= b.closeX + b.closeS + 2
                && sy >= b.closeY - 2 && sy <= b.closeY + b.closeS + 2;
    }

    public static boolean isCollapseClicked(Font font, double mx, double my, int screenW, int screenH,
                                            GuideStep step, boolean buildMode, boolean isPlacing, boolean isBar, boolean isPinned) {
        Box b = layout(font, screenW, screenH, step, buildMode, isPlacing, isBar, isPinned);
        double sx = mx / SCALE;
        double sy = my / SCALE;
        if (b.collapsed) {
            // The whole compact tab toggles back to expanded.
            return sx >= b.x && sx <= b.x + b.w && sy >= b.y && sy <= b.y + b.h;
        }
        return sx >= b.toggleX - 2 && sx <= b.toggleX + b.toggleS + 2
                && sy >= b.toggleY - 2 && sy <= b.toggleY + b.toggleS + 2;
    }

    public static void render(GuiGraphics g, Font font, int screenW, int screenH, double mx, double my,
                              GuideStep step,
                              boolean buildMode, boolean isPlacing, boolean isBar, boolean isPinned) {
        Box b = layout(font, screenW, screenH, step, buildMode, isPlacing, isBar, isPinned);
        int pad = PAD;

        g.pose().pushPose();
        g.pose().scale(SCALE, SCALE, 1.0f);

        // Background
        g.fill(RenderType.guiOverlay(), b.x, b.y, b.x + b.w, b.y + b.h, 0, BOX_BG);
        // Border
        g.fill(RenderType.guiOverlay(), b.x, b.y, b.x + b.w, b.y + 1, 0, BOX_BORDER);
        g.fill(RenderType.guiOverlay(), b.x, b.y + b.h - 1, b.x + b.w, b.y + b.h, 0, BOX_BORDER);
        g.fill(RenderType.guiOverlay(), b.x, b.y, b.x + 1, b.y + b.h, 0, BOX_BORDER);
        g.fill(RenderType.guiOverlay(), b.x + b.w - 1, b.y, b.x + b.w, b.y + b.h, 0, BOX_BORDER);

        if (b.collapsed) {
            // Collapsed: just the expand triangle, centered in the small tab.
            String icon = "◀";
            boolean hover = isCollapseClicked(font, mx, my, screenW, screenH, step, buildMode, isPlacing, isBar, isPinned);
            float ix = b.x + (b.w - font.width(icon)) / 2f;
            float iy = b.y + (b.h - font.lineHeight) / 2f;
            drawText(g, font, icon, ix, iy, hover ? BTN_HOVER : BTN_IDLE);
            g.pose().popPose();
            return;
        }

        int tx = b.x + pad;
        float ty = b.y + pad;

        ty = drawWrapped(g, font, b.title, tx, ty, b.wrapW, BOX_TITLE);
        ty += 1;
        g.fill(RenderType.guiOverlay(), tx, (int) ty - 2, b.x + b.w - pad * 2, (int) ty - 1, 0, BOX_DIVIDER);

        for (String line : b.lines) {
            ty = drawWrapped(g, font, line, tx, ty, b.wrapW, BOX_LINE);
        }

        ty += 2;
        drawWrapped(g, font, b.hint, tx, ty, b.wrapW, BOX_HINT);

        // Toggle (▶ — folds the box to the right) button
        boolean hoverToggle = isCollapseClicked(font, mx, my, screenW, screenH, step, buildMode, isPlacing, isBar, isPinned);
        if (hoverToggle) {
            g.fill(RenderType.guiOverlay(), b.toggleX - 2, b.toggleY - 2,
                    b.toggleX + b.toggleS + 2, b.toggleY + b.toggleS + 2, 0, BTN_TOGGLE_HOVER_BG);
        }
        drawText(g, font, "▶", b.toggleX + 1, b.toggleY, hoverToggle ? BTN_HOVER : BTN_IDLE);

        // Close (×) button
        boolean hoverClose = isCloseClicked(font, mx, my, screenW, screenH, step, buildMode, isPlacing, isBar, isPinned);
        if (hoverClose) {
            g.fill(RenderType.guiOverlay(), b.closeX - 2, b.closeY - 2,
                    b.closeX + b.closeS + 2, b.closeY + b.closeS + 2, 0, BTN_HOVER_BG);
        }
        drawText(g, font, "×", b.closeX + 1, b.closeY, hoverClose ? BTN_HOVER : BTN_IDLE);

        g.pose().popPose();
    }

    private static void drawText(GuiGraphics g, Font font, String text, float x, float y, int color) {
        font.drawInBatch(text, x, y, color, false,
                g.pose().last().pose(), g.bufferSource(),
                Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
    }

    /** Draw a possibly-wrapped string, line by line; returns the Y after the last line (pose units). */
    private static float drawWrapped(GuiGraphics g, Font font, String text, float x, float y, int width, int color) {
        for (FormattedCharSequence line : wrap(font, text, width)) {
            font.drawInBatch(line, x, y, color, false,
                    g.pose().last().pose(), g.bufferSource(),
                    Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
            y += font.lineHeight + 1;
        }
        return y;
    }
}
