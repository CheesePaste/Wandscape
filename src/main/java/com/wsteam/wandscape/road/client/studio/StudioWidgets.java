package com.wsteam.wandscape.road.client.studio;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Immediate-mode self-drawn widgets for the Road Studio overlay.
 * All widget methods draw directly via {@link GuiGraphics} and return interaction results.
 *
 * <p>Usage per frame:
 * <pre>
 *   StudioWidgets.beginFrame(g, font, mouseX, mouseY, leftDown, leftClicked, leftReleased, scrollDelta);
 *   StudioWidgets.beginLayout(panelX + PAD, contentY, panelW - PAD * 2);
 *   // ... widget calls ...
 *   int totalH = StudioWidgets.endLayout();
 * </pre>
 */
public final class StudioWidgets {
    private StudioWidgets() {}

    // ── Per-frame state (set by beginFrame) ──
    private static GuiGraphics g;
    private static Font font;
    private static int mouseX, mouseY;
    private static boolean mouseDown, mouseClicked, mouseReleased;
    private static double scrollDelta;

    // ── Layout cursor ──
    private static int layoutX, layoutY, layoutW;
    private static int layoutStartY;
    private static final int SPACING = 4;
    private static final int LINE_H = 12;

    // ── Active widget tracking ──
    private static String activeSlider = null;
    private static String openCombo = null;
    private static int comboTempIndex = -1;
    // Combo dropdown geometry (filled when combo is open)
    private static int comboDropX, comboDropY, comboDropW, comboDropH;
    private static String[] comboDropOptions;
    private static String comboDropId;

    /** Call once per frame before any widget rendering. */
    public static void beginFrame(GuiGraphics graphics, Font f,
                                   int mx, int my,
                                   boolean down, boolean clicked, boolean released,
                                   double scroll) {
        g = graphics;
        font = f;
        mouseX = mx;
        mouseY = my;
        mouseDown = down;
        mouseClicked = clicked;
        mouseReleased = released;
        scrollDelta = scroll;

        // Clear slider when mouse released
        if (released && activeSlider != null) {
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

    /** Current layout Y position. */
    public static int getY() { return layoutY; }

    /** Set layout Y explicitly (for scroll offsets). */
    public static void setY(int y) { layoutY = y; }

    /** Advance layout Y by a given amount. */
    public static void advance(int dy) { layoutY += dy; }

    /** Add vertical spacing. */
    public static void spacing() { layoutY += SPACING; }

    /** Double spacing. */
    public static void spacingLarge() { layoutY += SPACING * 2; }

    public static int getLayoutX() { return layoutX; }
    public static int getLayoutW() { return layoutW; }

    // ════════════════════════════════════════════════════════════════
    //  TEXT
    // ════════════════════════════════════════════════════════════════

    /** Draw gold section header with separator line. */
    public static void sectionHeader(String title) {
        spacing();
        g.drawString(font, title, layoutX, layoutY, StudioColors.TEXT_GOLD);
        layoutY += LINE_H;
        // Separator line
        g.fill(layoutX, layoutY, layoutX + layoutW, layoutY + 1, StudioColors.SEPARATOR);
        layoutY += 1 + SPACING;
    }

    /** Draw muted text. */
    public static void textMuted(String text) {
        g.drawString(font, text, layoutX, layoutY, StudioColors.TEXT_MUTED);
        layoutY += LINE_H;
    }

    /** Draw normal text. */
    public static void text(String text) {
        g.drawString(font, text, layoutX, layoutY, StudioColors.TEXT_WARM);
        layoutY += LINE_H;
    }

    /** Draw text at specific position (no layout advance). */
    public static void textAt(String text, int x, int y, int color) {
        g.drawString(font, text, x, y, color);
    }

    /** Draw colored text with layout advance. */
    public static void textColored(String text, int color) {
        g.drawString(font, text, layoutX, layoutY, color);
        layoutY += LINE_H;
    }

    /** Draw disabled text. */
    public static void textDisabled(String text) {
        g.drawString(font, text, layoutX, layoutY, StudioColors.TEXT_DISABLED);
        layoutY += LINE_H;
    }

    // ════════════════════════════════════════════════════════════════
    //  BUTTONS
    // ════════════════════════════════════════════════════════════════

    /**
     * Full-width button. Returns true if clicked.
     * @param h button height
     */
    public static boolean button(String label, int h) {
        return buttonAt(label, layoutX, layoutY, layoutW, h,
                StudioColors.BUTTON_NORMAL, StudioColors.BUTTON_HOVER, StudioColors.BUTTON_ACTIVE);
    }

    /** Full-width colored button. */
    public static boolean buttonColored(String label, int h, int bgNormal, int bgHover) {
        return buttonAt(label, layoutX, layoutY, layoutW, h,
                bgNormal, bgHover, bgHover);
    }

    /**
     * Button at explicit position. Returns true if clicked.
     * Does NOT advance layout (caller manages multi-column layout).
     */
    public static boolean buttonAt(String label, int x, int y, int w, int h,
                                    int bgNormal, int bgHover, int bgActive) {
        boolean hovered = isOver(x, y, w, h);
        boolean clicked = hovered && mouseClicked;

        int bg = clicked ? bgActive : hovered ? bgHover : bgNormal;
        g.fill(x, y, x + w, y + h, bg);

        // 1px border
        int borderCol = hovered ? StudioColors.BORDER_GOLD_BRIGHT : StudioColors.BORDER_GOLD;
        drawBorder(x, y, w, h, borderCol);

        // Centered label
        int tw = font.width(label);
        int tx = x + (w - tw) / 2;
        int ty = y + (h - font.lineHeight) / 2;
        g.drawString(font, label, tx, ty, StudioColors.TEXT_WARM);

        return clicked;
    }

    /** Full-width button with layout advance. */
    public static boolean buttonFull(String label, int h, int bgNormal, int bgHover) {
        boolean result = buttonAt(label, layoutX, layoutY, layoutW, h,
                bgNormal, bgHover, bgHover);
        layoutY += h + SPACING;
        return result;
    }

    /**
     * Mode selector button (selected = highlighted, unselected = dim).
     * Does NOT advance layout Y.
     */
    public static boolean modeButton(String label, boolean selected, int x, int y, int w, int h) {
        int bg = selected ? StudioColors.BUTTON_SELECTED_BG : StudioColors.BUTTON_UNSELECTED_BG;
        int bgH = selected ? StudioColors.BUTTON_SELECTED_BG : StudioColors.BUTTON_HOVER;
        int border = selected ? StudioColors.BUTTON_SELECTED_BORDER : StudioColors.BUTTON_UNSELECTED_BORDER;

        boolean hovered = isOver(x, y, w, h);
        boolean clicked = hovered && mouseClicked;

        g.fill(x, y, x + w, y + h, hovered && !selected ? bgH : bg);
        drawBorder(x, y, w, h, hovered ? StudioColors.BORDER_GOLD_BRIGHT : border);

        int tw = font.width(label);
        int tx = x + (w - tw) / 2;
        int ty = y + (h - font.lineHeight) / 2;
        g.drawString(font, label, tx, ty, selected ? StudioColors.TEXT_GOLD : StudioColors.TEXT_WARM);

        return clicked;
    }

    // ════════════════════════════════════════════════════════════════
    //  CHECKBOX
    // ════════════════════════════════════════════════════════════════

    /**
     * Checkbox. Returns true if toggled this frame.
     */
    public static boolean checkbox(String label, boolean currentValue) {
        int boxSize = 10;
        int boxX = layoutX;
        int boxY = layoutY + 1;
        int totalW = boxSize + 4 + font.width(label);
        int totalH = Math.max(boxSize + 2, LINE_H);

        boolean hovered = isOver(layoutX, layoutY, totalW, totalH);
        boolean clicked = hovered && mouseClicked;

        // Box
        g.fill(boxX, boxY, boxX + boxSize, boxY + boxSize, StudioColors.CHECK_BOX_BG);
        drawBorder(boxX, boxY, boxSize, boxSize,
                hovered ? StudioColors.BORDER_GOLD_BRIGHT : StudioColors.BORDER_GOLD);

        // Check mark
        if (currentValue) {
            int pad = 2;
            g.fill(boxX + pad, boxY + pad, boxX + boxSize - pad, boxY + boxSize - pad,
                    StudioColors.CHECK_MARK);
        }

        // Label
        g.drawString(font, label, boxX + boxSize + 4, layoutY + 1, StudioColors.TEXT_WARM);
        layoutY += totalH + SPACING;

        return clicked;
    }

    // ════════════════════════════════════════════════════════════════
    //  RADIO BUTTON
    // ════════════════════════════════════════════════════════════════

    /**
     * Radio button. Returns true if clicked.
     * Does NOT advance layout (call from same line groups).
     */
    public static boolean radioButtonAt(String label, boolean selected, int x, int y) {
        int dotSize = 8;
        int totalW = dotSize + 4 + font.width(label);
        int totalH = Math.max(dotSize + 2, LINE_H);
        boolean hovered = isOver(x, y, totalW, totalH);
        boolean clicked = hovered && mouseClicked;

        // Outer circle (approximated as box)
        g.fill(x, y + 2, x + dotSize, y + 2 + dotSize, StudioColors.CHECK_BOX_BG);
        drawBorder(x, y + 2, dotSize, dotSize,
                hovered ? StudioColors.BORDER_GOLD_BRIGHT : StudioColors.BORDER_GOLD);

        // Inner dot
        if (selected) {
            g.fill(x + 2, y + 4, x + dotSize - 2, y + dotSize, StudioColors.RADIO_DOT);
        }

        g.drawString(font, label, x + dotSize + 4, y + 1, StudioColors.TEXT_WARM);
        return clicked;
    }

    // ════════════════════════════════════════════════════════════════
    //  COMBO BOX (DROPDOWN)
    // ════════════════════════════════════════════════════════════════

    /**
     * Combo/dropdown selector. Returns newly selected index, or currentIndex if unchanged.
     */
    public static int combo(String id, String[] options, int currentIndex, int h) {
        int x = layoutX;
        int y = layoutY;
        int w = layoutW;
        boolean hovered = isOver(x, y, w, h);
        boolean clicked = hovered && mouseClicked;

        // Draw combo box
        g.fill(x, y, x + w, y + h, StudioColors.INPUT_BG);
        drawBorder(x, y, w, h,
                hovered ? StudioColors.INPUT_BORDER_FOCUS : StudioColors.INPUT_BORDER);

        // Current value
        String display = (currentIndex >= 0 && currentIndex < options.length)
                ? options[currentIndex] : "---";
        g.drawString(font, display, x + 4, y + (h - font.lineHeight) / 2, StudioColors.TEXT_WARM);

        // Arrow indicator
        g.drawString(font, "\u25BC", x + w - 12, y + (h - font.lineHeight) / 2, StudioColors.TEXT_MUTED);

        int result = currentIndex;

        // Toggle dropdown
        if (clicked) {
            if (id.equals(openCombo)) {
                openCombo = null;
            } else {
                openCombo = id;
                comboTempIndex = currentIndex;
                comboDropX = x;
                comboDropY = y + h;
                comboDropW = w;
                comboDropOptions = options;
                comboDropId = id;
                int maxItems = Math.min(options.length, 8);
                comboDropH = maxItems * (font.lineHeight + 4) + 2;
            }
        }

        layoutY += h + SPACING;
        return result;
    }

    /**
     * Render and handle open combo dropdown. Call AFTER all panel content,
     * so the dropdown renders on top. Returns selected index or -1 if no selection.
     */
    public static int renderComboDropdown() {
        if (openCombo == null || comboDropOptions == null) return -1;

        int x = comboDropX;
        int y = comboDropY;
        int w = comboDropW;
        String[] opts = comboDropOptions;
        int itemH = font.lineHeight + 4;
        int totalH = opts.length * itemH + 2;

        // Background
        g.fill(x, y, x + w, y + totalH, StudioColors.PANEL_BG);
        drawBorder(x, y, w, totalH, StudioColors.BORDER_GOLD_BRIGHT);

        int result = -1;
        for (int i = 0; i < opts.length; i++) {
            int iy = y + 1 + i * itemH;
            boolean hov = isOver(x + 1, iy, w - 2, itemH);
            if (hov) {
                g.fill(x + 1, iy, x + w - 1, iy + itemH, StudioColors.LIST_ITEM_HOVER);
            }
            g.drawString(font, opts[i], x + 4, iy + 2, StudioColors.TEXT_WARM);
            if (hov && mouseClicked) {
                result = i;
                openCombo = null;
            }
        }

        // Close if clicked outside
        if (mouseClicked && !isOver(x, y, w, totalH)) {
            openCombo = null;
        }

        return result;
    }

    /** True if any combo dropdown is currently open. */
    public static boolean isComboOpen() {
        return openCombo != null;
    }

    // ════════════════════════════════════════════════════════════════
    //  SLIDER INT
    // ════════════════════════════════════════════════════════════════

    /**
     * Integer slider. Returns current value (may be changed by drag).
     */
    public static int sliderInt(String id, String label, int value, int min, int max) {
        int x = layoutX;
        int y = layoutY;
        int w = layoutW;
        int trackH = 14;

        // Label
        String display = label + ": " + value;
        g.drawString(font, display, x, y, StudioColors.TEXT_WARM);
        y += LINE_H;

        // Track
        g.fill(x, y, x + w, y + trackH, StudioColors.SLIDER_TRACK);
        drawBorder(x, y, w, trackH, StudioColors.BORDER_GOLD);

        // Fill
        float ratio = (max > min) ? (float)(value - min) / (max - min) : 0;
        int fillW = (int)(ratio * (w - 2));
        if (fillW > 0) {
            g.fill(x + 1, y + 1, x + 1 + fillW, y + trackH - 1, StudioColors.SLIDER_FILL);
        }

        // Thumb
        int thumbX = x + 1 + fillW - 3;
        boolean hovered = isOver(x, y, w, trackH);
        boolean isActive = id.equals(activeSlider);

        if (mouseClicked && hovered) {
            activeSlider = id;
            isActive = true;
        }

        if (isActive && mouseDown) {
            float newRatio = Math.max(0, Math.min(1, (float)(mouseX - x) / w));
            value = min + Math.round(newRatio * (max - min));
            ratio = (float)(value - min) / (max - min);
            fillW = (int)(ratio * (w - 2));
            thumbX = x + 1 + fillW - 3;
        }

        int thumbColor = (hovered || isActive)
                ? StudioColors.SLIDER_THUMB_HOVER : StudioColors.SLIDER_THUMB;
        g.fill(Math.max(x, thumbX), y, Math.min(x + w, thumbX + 6), y + trackH, thumbColor);

        layoutY = y + trackH + SPACING;
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Float slider. Returns current value.
     */
    public static float sliderFloat(String id, String label, float value, float min, float max, String fmt) {
        int x = layoutX;
        int y = layoutY;
        int w = layoutW;
        int trackH = 14;

        // Label
        String display = label + ": " + String.format(fmt, value);
        g.drawString(font, display, x, y, StudioColors.TEXT_WARM);
        y += LINE_H;

        // Track
        g.fill(x, y, x + w, y + trackH, StudioColors.SLIDER_TRACK);
        drawBorder(x, y, w, trackH, StudioColors.BORDER_GOLD);

        // Fill
        float ratio = (max > min) ? (value - min) / (max - min) : 0;
        int fillW = (int)(ratio * (w - 2));
        if (fillW > 0) {
            g.fill(x + 1, y + 1, x + 1 + fillW, y + trackH - 1, StudioColors.SLIDER_FILL);
        }

        boolean hovered = isOver(x, y, w, trackH);
        boolean isActive = id.equals(activeSlider);

        if (mouseClicked && hovered) {
            activeSlider = id;
            isActive = true;
        }

        if (isActive && mouseDown) {
            float newRatio = Math.max(0, Math.min(1, (float)(mouseX - x) / w));
            value = min + newRatio * (max - min);
            ratio = (value - min) / (max - min);
        }

        // Thumb
        int thumbFillW = (int)(ratio * (w - 2));
        int thumbX = x + 1 + thumbFillW - 3;
        int thumbColor = (hovered || isActive)
                ? StudioColors.SLIDER_THUMB_HOVER : StudioColors.SLIDER_THUMB;
        g.fill(Math.max(x, thumbX), y, Math.min(x + w, thumbX + 6), y + trackH, thumbColor);

        layoutY = y + trackH + SPACING;
        return Math.max(min, Math.min(max, value));
    }

    /** Returns true if any slider is currently being dragged. */
    public static boolean isSliderActive() {
        return activeSlider != null;
    }

    // ════════════════════════════════════════════════════════════════
    //  SELECTABLE LIST
    // ════════════════════════════════════════════════════════════════

    /**
     * Scrollable selectable list. Returns index of clicked item, or -1 if none clicked.
     * @param listH total list height in pixels
     */
    public static int selectableList(String[] items, int selectedIndex, int listH) {
        int x = layoutX;
        int y = layoutY;
        int w = layoutW;
        int itemH = font.lineHeight + 4;

        // Background
        g.fill(x, y, x + w, y + listH, StudioColors.CHILD_BG);
        drawBorder(x, y, w, listH, StudioColors.BORDER_GOLD);

        // Scissor clip
        g.enableScissor(x + 1, y + 1, x + w - 1, y + listH - 1);

        int result = -1;
        for (int i = 0; i < items.length; i++) {
            int iy = y + 1 + i * itemH;
            if (iy + itemH < y || iy > y + listH) continue; // culled

            boolean sel = (i == selectedIndex);
            boolean hov = isOver(x + 1, iy, w - 2, itemH);

            if (sel) {
                g.fill(x + 1, iy, x + w - 1, iy + itemH, StudioColors.LIST_ITEM_SELECTED);
            } else if (hov) {
                g.fill(x + 1, iy, x + w - 1, iy + itemH, StudioColors.LIST_ITEM_HOVER);
            }

            int textColor = sel ? StudioColors.TEXT_GOLD : StudioColors.TEXT_WARM;
            g.drawString(font, items[i], x + 4, iy + 2, textColor);

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

    /**
     * Tab bar. Returns index of clicked tab, or -1 if none clicked.
     */
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
            boolean hov = isOver(tx, y, tabW, tabH);

            int bg = active ? StudioColors.TAB_ACTIVE : hov ? StudioColors.TAB_HOVER : StudioColors.TAB_NORMAL;
            g.fill(tx, y, tx + tabW, y + tabH, bg);

            if (active) {
                // Active tab bottom highlight
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

    /**
     * Position display row (read-only label + XYZ + clear button).
     * Returns: 0=no action, 1=clear clicked, 2=capture clicked.
     */
    public static int positionRow(String label, net.minecraft.core.BlockPos pos) {
        int x = layoutX;
        int y = layoutY;
        int w = layoutW;
        int rowH = 16;

        g.drawString(font, label + ":", x, y, StudioColors.TEXT_MUTED);
        y += LINE_H;

        int result = 0;
        if (pos != null) {
            String coordStr = String.format("(%d, %d, %d)", pos.getX(), pos.getY(), pos.getZ());
            g.drawString(font, coordStr, x + 4, y + 2, StudioColors.TEXT_GREEN);

            int clearW = font.width("\u2717") + 8;
            int clearX = x + w - clearW;
            boolean clearHov = isOver(clearX, y, clearW, rowH);
            g.fill(clearX, y, clearX + clearW, y + rowH,
                    clearHov ? StudioColors.BUTTON_RED_HOVER : StudioColors.BUTTON_RED);
            g.drawString(font, "\u2717", clearX + 4, y + 2, StudioColors.TEXT_WARM);
            if (clearHov && mouseClicked) result = 1;
        } else {
            g.drawString(font, "  [\u672A\u8BBE\u7F6E]", x + 4, y + 2, StudioColors.TEXT_DISABLED);
            y += rowH;

            // Capture button
            int capH = 18;
            boolean capHov = isOver(x, y, w, capH);
            g.fill(x, y, x + w, y + capH,
                    capHov ? StudioColors.BUTTON_HOVER : StudioColors.BUTTON_NORMAL);
            drawBorder(x, y, w, capH, StudioColors.BORDER_GOLD);
            String capLabel = "\u6355\u6349\u811A\u4E0B\u4F4D\u70B9";
            int ctw = font.width(capLabel);
            g.drawString(font, capLabel, x + (w - ctw) / 2, y + 3, StudioColors.TEXT_WARM);
            if (capHov && mouseClicked) result = 2;
            y += capH;
        }

        layoutY = y + rowH + SPACING;
        return result;
    }

    // ════════════════════════════════════════════════════════════════
    //  SCROLLBAR
    // ════════════════════════════════════════════════════════════════

    /**
     * Vertical scrollbar. Returns new scroll offset.
     */
    public static int verticalScrollbar(int x, int y, int h, int contentH, int scrollOffset) {
        if (contentH <= h) return 0;

        int barW = 6;
        int barX = x;

        // Track
        g.fill(barX, y, barX + barW, y + h, StudioColors.SCROLLBAR_BG);

        // Thumb
        float viewRatio = (float) h / contentH;
        int thumbH = Math.max(16, (int)(h * viewRatio));
        float scrollRatio = (float) scrollOffset / (contentH - h);
        int thumbY = y + (int)(scrollRatio * (h - thumbH));

        boolean thumbHov = isOver(barX, thumbY, barW, thumbH);
        g.fill(barX, thumbY, barX + barW, thumbY + thumbH,
                thumbHov ? StudioColors.SCROLLBAR_THUMB_HOVER : StudioColors.SCROLLBAR_THUMB);

        return scrollOffset;
    }

    // ════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════

    /** Check if mouse is over a rectangle. */
    public static boolean isOver(int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** Draw 1px border around a rectangle. */
    public static void drawBorder(int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);          // top
        g.fill(x, y + h - 1, x + w, y + h, color);  // bottom
        g.fill(x, y + 1, x + 1, y + h - 1, color);  // left
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color); // right
    }

    /** Draw a filled gradient box. */
    public static void gradientBox(int x, int y, int w, int h, int top, int bottom) {
        g.fillGradient(x, y, x + w, y + h, top, bottom);
    }
}
