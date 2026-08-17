package com.wsteam.wandscape.road.client.studio;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Immediate-mode self-drawn widgets for the Road Studio overlay.
 * All widget methods draw directly via {@link GuiGraphics} and return interaction results.
 */
public final class StudioWidgets {
    private StudioWidgets() {}

    // ── Per-frame state ──
    private static GuiGraphics g;
    private static Font font;
    private static int mouseX, mouseY;
    private static boolean mouseDown, mouseClicked, mouseReleased;
    private static int clipLeft, clipRight;

    // ── Layout cursor ──
    private static int layoutX, layoutY, layoutW;
    private static int layoutStartY;
    private static final int SPACING = 4;
    private static final int LINE_H = 12;

    // ── Active widget tracking ──
    private static String activeSlider = null;
    private static String openCombo = null;
    private static int comboDropX, comboDropY, comboDropW, comboDropH;
    private static String[] comboDropOptions;
    private static String comboDropId;
    private static long comboOpenTime = 0;

    /** Call once per frame before any widget rendering. */
    public static void beginFrame(GuiGraphics graphics, Font f,
                                   int mx, int my,
                                   boolean down, boolean clicked, boolean released,
                                   int cLeft, int cRight) {
        g = graphics;
        font = f;
        mouseX = mx;
        mouseY = my;
        mouseDown = down;
        mouseClicked = clicked;
        mouseReleased = released;
        clipLeft = cLeft;
        clipRight = cRight;

        if (released) {
            activeSlider = null;
        }
    }

    /** Start a vertical layout region. Widgets auto-advance downward. */
    public static void beginLayout(int x, int y, int w) {
        layoutX = x;
        layoutY = y;
        layoutW = w;
        layoutStartY = y;
    }

    /** Returns total content height consumed since beginLayout. */
    public static int endLayout() {
        return layoutY - layoutStartY;
    }

    public static int getY() { return layoutY; }
    public static void setY(int y) { layoutY = y; }
    public static void advance(int dy) { layoutY += dy; }
    public static void spacing() { layoutY += SPACING; }
    public static void spacingLarge() { layoutY += SPACING * 2; }
    public static int getLayoutX() { return layoutX; }
    public static int getLayoutW() { return layoutW; }

    // ════════════════════════════════════════════════════════════════
    //  TEXT
    // ════════════════════════════════════════════════════════════════

    public static void sectionHeader(String title) {
        spacing();
        g.drawString(font, title, layoutX, layoutY, StudioColors.TEXT_GOLD);
        layoutY += LINE_H;
        g.fill(layoutX, layoutY, layoutX + layoutW, layoutY + 1, StudioColors.SEPARATOR);
        layoutY += 1 + SPACING;
    }

    public static void textMuted(String text) {
        g.drawString(font, text, layoutX, layoutY, StudioColors.TEXT_MUTED);
        layoutY += LINE_H;
    }

    public static void text(String text) {
        g.drawString(font, text, layoutX, layoutY, StudioColors.TEXT_WARM);
        layoutY += LINE_H;
    }

    public static void textColored(String text, int color) {
        g.drawString(font, text, layoutX, layoutY, color);
        layoutY += LINE_H;
    }

    public static void textDisabled(String text) {
        g.drawString(font, text, layoutX, layoutY, StudioColors.TEXT_DISABLED);
        layoutY += LINE_H;
    }

    // ════════════════════════════════════════════════════════════════
    //  BUTTONS
    // ════════════════════════════════════════════════════════════════

    public static boolean button(String label, int h) {
        return buttonAt(label, layoutX, layoutY, layoutW, h,
                StudioColors.BUTTON_NORMAL, StudioColors.BUTTON_HOVER, StudioColors.BUTTON_ACTIVE);
    }

    public static boolean buttonAt(String label, int x, int y, int w, int h,
                                    int bgNormal, int bgHover, int bgActive) {
        boolean hovered = isHovered(x, y, w, h);
        boolean pressed = hovered && mouseDown;
        boolean clicked = hovered && mouseClicked;

        int bg = pressed ? bgActive : hovered ? bgHover : bgNormal;
        g.fill(x, y, x + w, y + h, bg);

        int borderCol = hovered ? StudioColors.BORDER_GOLD_BRIGHT : StudioColors.BORDER_GOLD;
        drawBorder(x, y, w, h, borderCol);

        int tw = font.width(label);
        int tx = x + (w - tw) / 2;
        int ty = y + (h - font.lineHeight) / 2;
        g.drawString(font, label, tx, ty, hovered ? StudioColors.TEXT_GOLD : StudioColors.TEXT_WARM);

        return clicked;
    }

    public static boolean buttonFull(String label, int h, int bgNormal, int bgHover) {
        boolean result = buttonAt(label, layoutX, layoutY, layoutW, h,
                bgNormal, bgHover, StudioColors.BUTTON_ACTIVE);
        layoutY += h + SPACING;
        return result;
    }

    public static boolean modeButton(String label, boolean selected, int x, int y, int w, int h) {
        int bg = selected ? StudioColors.BUTTON_SELECTED_BG : StudioColors.BUTTON_UNSELECTED_BG;
        int bgH = selected ? StudioColors.BUTTON_SELECTED_BG : StudioColors.BUTTON_HOVER;
        int border = selected ? StudioColors.BUTTON_SELECTED_BORDER : StudioColors.BUTTON_UNSELECTED_BORDER;

        boolean hovered = isHovered(x, y, w, h);
        boolean clicked = hovered && mouseClicked;

        g.fill(x, y, x + w, y + h, hovered && !selected ? bgH : bg);
        drawBorder(x, y, w, h, (hovered || selected) ? StudioColors.BORDER_GOLD_BRIGHT : border);

        int tw = font.width(label);
        int tx = x + (w - tw) / 2;
        int ty = y + (h - font.lineHeight) / 2;
        g.drawString(font, label, tx, ty, selected ? StudioColors.TEXT_GOLD : StudioColors.TEXT_WARM);

        return clicked;
    }

    // ════════════════════════════════════════════════════════════════
    //  CHECKBOX
    // ════════════════════════════════════════════════════════════════

    public static boolean checkbox(String label, boolean currentValue) {
        int boxSize = 12;
        int boxX = layoutX;
        int boxY = layoutY + 1;
        int totalW = boxSize + 6 + font.width(label);
        int totalH = Math.max(boxSize + 2, LINE_H + 2);

        boolean hovered = isHovered(layoutX, layoutY, totalW, totalH);
        boolean clicked = hovered && mouseClicked;

        g.fill(boxX, boxY, boxX + boxSize, boxY + boxSize, StudioColors.CHECK_BOX_BG);
        drawBorder(boxX, boxY, boxSize, boxSize,
                hovered ? StudioColors.BORDER_GOLD_BRIGHT : StudioColors.BORDER_GOLD);

        if (currentValue) {
            g.fill(boxX + 2, boxY + 2, boxX + boxSize - 2, boxY + boxSize - 2, StudioColors.CHECK_MARK);
        }

        g.drawString(font, label, boxX + boxSize + 6, layoutY + 2,
                hovered ? StudioColors.TEXT_GOLD : StudioColors.TEXT_WARM);
        layoutY += totalH + SPACING;

        return clicked;
    }

    // ════════════════════════════════════════════════════════════════
    //  RADIO BUTTON
    // ════════════════════════════════════════════════════════════════

    public static boolean radioButtonAt(String label, boolean selected, int x, int y) {
        int dotSize = 10;
        int totalW = dotSize + 6 + font.width(label);
        int totalH = Math.max(dotSize + 2, LINE_H);
        boolean hovered = isHovered(x, y, totalW, totalH);
        boolean clicked = hovered && mouseClicked;

        g.fill(x, y + 1, x + dotSize, y + 1 + dotSize, StudioColors.CHECK_BOX_BG);
        drawBorder(x, y + 1, dotSize, dotSize,
                (hovered || selected) ? StudioColors.BORDER_GOLD_BRIGHT : StudioColors.BORDER_GOLD);

        if (selected) {
            g.fill(x + 2, y + 3, x + dotSize - 2, y + 1 + dotSize - 2, StudioColors.RADIO_DOT);
        }

        g.drawString(font, label, x + dotSize + 6, y + 1,
                selected ? StudioColors.TEXT_GOLD : hovered ? StudioColors.TEXT_GOLD : StudioColors.TEXT_WARM);
        return clicked;
    }

    // ════════════════════════════════════════════════════════════════
    //  COMBO BOX (DROPDOWN)
    // ════════════════════════════════════════════════════════════════

    public static int combo(String id, String[] options, int currentIndex, int h) {
        int x = layoutX;
        int y = layoutY;
        int w = layoutW;
        boolean hovered = isHovered(x, y, w, h);
        boolean clicked = hovered && mouseClicked;

        g.fill(x, y, x + w, y + h, StudioColors.INPUT_BG);
        drawBorder(x, y, w, h,
                hovered ? StudioColors.INPUT_BORDER_FOCUS : StudioColors.INPUT_BORDER);

        String display = (currentIndex >= 0 && currentIndex < options.length)
                ? options[currentIndex] : "---";
        g.drawString(font, display, x + 6, y + (h - font.lineHeight) / 2, StudioColors.TEXT_WARM);
        g.drawString(font, "\u25BC", x + w - 14, y + (h - font.lineHeight) / 2, StudioColors.TEXT_MUTED);

        if (clicked) {
            if (id.equals(openCombo)) {
                openCombo = null;
            } else {
                openCombo = id;
                comboDropX = x;
                comboDropY = y + h;
                comboDropW = w;
                comboDropOptions = options;
                comboDropId = id;
                comboOpenTime = System.currentTimeMillis();
            }
        }

        layoutY += h + SPACING;
        return currentIndex;
    }

    public static int renderComboDropdown() {
        if (openCombo == null || comboDropOptions == null) return -1;

        int x = comboDropX;
        int y = comboDropY;
        int w = comboDropW;
        String[] opts = comboDropOptions;
        int itemH = font.lineHeight + 6;
        int totalH = opts.length * itemH + 2;

        // Background & gold border on top of everything
        g.fill(x, y, x + w, y + totalH, StudioColors.PANEL_BG);
        drawBorder(x, y, w, totalH, StudioColors.BORDER_GOLD_BRIGHT);

        int result = -1;
        for (int i = 0; i < opts.length; i++) {
            int iy = y + 1 + i * itemH;
            boolean hov = mouseX >= x + 1 && mouseX < x + w - 1 && mouseY >= iy && mouseY < iy + itemH;
            if (hov) {
                g.fill(x + 1, iy, x + w - 1, iy + itemH, StudioColors.LIST_ITEM_HOVER);
            }
            g.drawString(font, opts[i], x + 6, iy + 3, hov ? StudioColors.TEXT_GOLD : StudioColors.TEXT_WARM);
            if (hov && mouseClicked && (System.currentTimeMillis() - comboOpenTime > 50)) {
                result = i;
                openCombo = null;
                break;
            }
        }

        if (mouseClicked && (System.currentTimeMillis() - comboOpenTime > 50)
                && !(mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + totalH)) {
            openCombo = null;
        }

        return result;
    }

    public static boolean isComboOpen() {
        return openCombo != null;
    }

    // ════════════════════════════════════════════════════════════════
    //  SLIDER INT
    // ════════════════════════════════════════════════════════════════

    public static int sliderInt(String id, String label, int value, int min, int max) {
        int x = layoutX;
        int y = layoutY;
        int w = layoutW;
        int trackH = 14;

        String display = label + ": " + value;
        g.drawString(font, display, x, y, StudioColors.TEXT_WARM);
        y += LINE_H;

        g.fill(x, y, x + w, y + trackH, StudioColors.SLIDER_TRACK);
        drawBorder(x, y, w, trackH, StudioColors.BORDER_GOLD);

        float ratio = (max > min) ? (float)(value - min) / (max - min) : 0;
        int fillW = (int)(ratio * (w - 2));
        if (fillW > 0) {
            g.fill(x + 1, y + 1, x + 1 + fillW, y + trackH - 1, StudioColors.SLIDER_FILL);
        }

        boolean hovered = isHovered(x, y, w, trackH);
        boolean isActive = id.equals(activeSlider);

        if (mouseClicked && hovered) {
            activeSlider = id;
            isActive = true;
        }

        if (isActive && mouseDown) {
            float newRatio = Math.max(0f, Math.min(1f, (float)(mouseX - x) / (float)w));
            value = min + Math.round(newRatio * (max - min));
            ratio = (float)(value - min) / (max - min);
            fillW = (int)(ratio * (w - 2));
        }

        int thumbX = x + 1 + fillW - 3;
        int thumbColor = (hovered || isActive)
                ? StudioColors.SLIDER_THUMB_HOVER : StudioColors.SLIDER_THUMB;
        g.fill(Math.max(x, thumbX), y, Math.min(x + w, thumbX + 6), y + trackH, thumbColor);

        layoutY = y + trackH + SPACING;
        return Math.max(min, Math.min(max, value));
    }

    public static float sliderFloat(String id, String label, float value, float min, float max, String fmt) {
        int x = layoutX;
        int y = layoutY;
        int w = layoutW;
        int trackH = 14;

        String display = label + ": " + String.format(fmt, value);
        g.drawString(font, display, x, y, StudioColors.TEXT_WARM);
        y += LINE_H;

        g.fill(x, y, x + w, y + trackH, StudioColors.SLIDER_TRACK);
        drawBorder(x, y, w, trackH, StudioColors.BORDER_GOLD);

        float ratio = (max > min) ? (value - min) / (max - min) : 0;
        int fillW = (int)(ratio * (w - 2));
        if (fillW > 0) {
            g.fill(x + 1, y + 1, x + 1 + fillW, y + trackH - 1, StudioColors.SLIDER_FILL);
        }

        boolean hovered = isHovered(x, y, w, trackH);
        boolean isActive = id.equals(activeSlider);

        if (mouseClicked && hovered) {
            activeSlider = id;
            isActive = true;
        }

        if (isActive && mouseDown) {
            float newRatio = Math.max(0f, Math.min(1f, (float)(mouseX - x) / (float)w));
            value = min + newRatio * (max - min);
            ratio = (value - min) / (max - min);
            fillW = (int)(ratio * (w - 2));
        }

        int thumbX = x + 1 + fillW - 3;
        int thumbColor = (hovered || isActive)
                ? StudioColors.SLIDER_THUMB_HOVER : StudioColors.SLIDER_THUMB;
        g.fill(Math.max(x, thumbX), y, Math.min(x + w, thumbX + 6), y + trackH, thumbColor);

        layoutY = y + trackH + SPACING;
        return Math.max(min, Math.min(max, value));
    }

    // ════════════════════════════════════════════════════════════════
    //  SELECTABLE LIST
    // ════════════════════════════════════════════════════════════════

    public static int selectableList(String[] items, int selectedIndex, int listH) {
        int x = layoutX;
        int y = layoutY;
        int w = layoutW;
        int itemH = font.lineHeight + 5;

        g.fill(x, y, x + w, y + listH, StudioColors.CHILD_BG);
        drawBorder(x, y, w, listH, StudioColors.BORDER_GOLD);

        g.enableScissor(x + 1, y + 1, x + w - 1, y + listH - 1);

        int result = -1;
        for (int i = 0; i < items.length; i++) {
            int iy = y + 1 + i * itemH;
            if (iy + itemH < y || iy > y + listH) continue;

            boolean sel = (i == selectedIndex);
            boolean hov = isHovered(x + 1, iy, w - 2, itemH);

            if (sel) {
                g.fill(x + 1, iy, x + w - 1, iy + itemH, StudioColors.LIST_ITEM_SELECTED);
            } else if (hov) {
                g.fill(x + 1, iy, x + w - 1, iy + itemH, StudioColors.LIST_ITEM_HOVER);
            }

            int textColor = sel ? StudioColors.TEXT_GOLD : StudioColors.TEXT_WARM;
            g.drawString(font, items[i], x + 5, iy + 3, textColor);

            if (hov && mouseClicked) {
                result = i;
            }
        }

        g.disableScissor();
        layoutY += listH + SPACING;
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  TAB BAR
    // ════════════════════════════════════════════════════════════════

    public static int tabBar(String[] labels, int activeIndex) {
        int x = layoutX;
        int y = layoutY;
        int totalW = layoutW;
        int tabH = 22;
        int gap = 2;
        int tabW = (totalW - (labels.length - 1) * gap) / labels.length;

        int result = -1;
        for (int i = 0; i < labels.length; i++) {
            int tx = x + i * (tabW + gap);
            boolean active = (i == activeIndex);
            boolean hov = isHovered(tx, y, tabW, tabH);

            int bg = active ? StudioColors.TAB_ACTIVE : hov ? StudioColors.TAB_HOVER : StudioColors.TAB_NORMAL;
            g.fill(tx, y, tx + tabW, y + tabH, bg);

            if (active) {
                g.fill(tx, y + tabH - 2, tx + tabW, y + tabH, StudioColors.TEXT_GOLD);
            }

            drawBorder(tx, y, tabW, tabH,
                    active ? StudioColors.TAB_BORDER : StudioColors.BORDER_GOLD);

            int tw = font.width(labels[i]);
            g.drawString(font, labels[i], tx + (tabW - tw) / 2,
                    y + (tabH - font.lineHeight) / 2, active ? StudioColors.TEXT_GOLD : StudioColors.TEXT_WARM);

            if (hov && mouseClicked) {
                result = i;
            }
        }

        layoutY += tabH + SPACING;
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  COORDINATE DISPLAY + CAPTURE
    // ════════════════════════════════════════════════════════════════

    public static int positionRow(String label, net.minecraft.core.BlockPos pos) {
        int x = layoutX;
        int y = layoutY;
        int w = layoutW;
        int rowH = 18;

        g.drawString(font, label + ":", x, y, StudioColors.TEXT_MUTED);
        y += LINE_H;

        int result = 0;
        if (pos != null) {
            String coordStr = String.format("(%d, %d, %d)", pos.getX(), pos.getY(), pos.getZ());
            g.drawString(font, coordStr, x + 4, y + 3, StudioColors.TEXT_GREEN);

            int clearW = font.width("✕") + 10;
            int clearX = x + w - clearW;
            boolean clearHov = isHovered(clearX, y, clearW, rowH);
            g.fill(clearX, y, clearX + clearW, y + rowH,
                    clearHov ? StudioColors.BUTTON_RED_HOVER : StudioColors.BUTTON_RED);
            g.drawString(font, "✕", clearX + 5, y + 4, StudioColors.TEXT_WARM);
            if (clearHov && mouseClicked) result = 1;
            y += rowH;
        } else {
            g.drawString(font, "  [未设置]", x + 4, y + 2, StudioColors.TEXT_DISABLED);
            y += LINE_H + 2;

            int capH = 20;
            boolean capHov = isHovered(x, y, w, capH);
            g.fill(x, y, x + w, y + capH,
                    capHov ? StudioColors.BUTTON_HOVER : StudioColors.BUTTON_NORMAL);
            drawBorder(x, y, w, capH, capHov ? StudioColors.BORDER_GOLD_BRIGHT : StudioColors.BORDER_GOLD);
            String capLabel = "捕捉脚下位点";
            int ctw = font.width(capLabel);
            g.drawString(font, capLabel, x + (w - ctw) / 2, y + 4,
                    capHov ? StudioColors.TEXT_GOLD : StudioColors.TEXT_WARM);
            if (capHov && mouseClicked) result = 2;
            y += capH;
        }

        layoutY = y + SPACING;
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  SCROLLBAR
    // ════════════════════════════════════════════════════════════════

    public static int verticalScrollbar(int x, int y, int h, int contentH, int scrollOffset) {
        if (contentH <= h) return 0;

        int barW = 6;
        g.fill(x, y, x + barW, y + h, StudioColors.SCROLLBAR_BG);

        float viewRatio = (float) h / contentH;
        int thumbH = Math.max(16, (int)(h * viewRatio));
        float scrollRatio = (float) scrollOffset / (contentH - h);
        int thumbY = y + (int)(scrollRatio * (h - thumbH));

        boolean thumbHov = mouseX >= x && mouseX < x + barW && mouseY >= thumbY && mouseY < thumbY + thumbH;
        g.fill(x, thumbY, x + barW, thumbY + thumbH,
                thumbHov ? StudioColors.SCROLLBAR_THUMB_HOVER : StudioColors.SCROLLBAR_THUMB);

        return scrollOffset;
    }

    // ════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════

    public static boolean isHovered(int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h
                && mouseX >= clipLeft && mouseX <= clipRight;
    }

    public static void drawBorder(int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    public static void gradientBox(int x, int y, int w, int h, int top, int bottom) {
        g.fillGradient(x, y, x + w, y + h, top, bottom);
    }
}
