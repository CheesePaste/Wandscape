package com.wsteam.wandscape.shared.ui.component;

import java.util.LinkedHashMap;
import java.util.Map;

import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
/**
 * Displays all 7 element types with icon, name, and formatted quantity.
 * Each row is 18px tall (icon 16x16 + 2px spacing).
 *
 * <p>Icons are rendered programmatically as colored circles with a letter glyph.
 * No texture assets required. To upgrade to custom PNG textures, place
 * 16x16 PNGs at {@code assets/wandscape/textures/gui/element/{id}.png}
 * and switch the rendering in {@link #drawElementIcon}.
 */
public class ElementPanel extends AbstractWidget {

    private static final int ROW_HEIGHT = 18;
    private static final int ICON_SIZE = 16;

    /** Map from ElementType to stored amount. */
    private Map<ElementType, Long> elements = new LinkedHashMap<>();

    /** Element -> display color (background circle). */
    private static final Map<ElementType, Integer> ICON_COLORS = Map.of(
            ElementType.EARTH,   0xFF8B6914,
            ElementType.WOOD,    0xFF2E8B57,
            ElementType.WATER,   0xFF4A90D9,
            ElementType.FIRE,    0xFFB22222,
            ElementType.METAL,   0xFF808080,
            ElementType.WIND,    0xFF87CEEB,
            ElementType.DARK,    0xFF4B0082
    );

    /** Element -> display name color. */
    private static final Map<ElementType, Integer> NAME_COLORS = Map.of(
            ElementType.EARTH,   0xFFC4A44A,
            ElementType.WOOD,    0xFF5CB878,
            ElementType.WATER,   0xFF7AB8F0,
            ElementType.FIRE,    0xFFD04444,
            ElementType.METAL,   0xFFB0B0B0,
            ElementType.WIND,    0xFFA0E0F0,
            ElementType.DARK,    0xFFA060D0
    );

    public ElementPanel(int x, int y, int width) {
        super(x, y, width, 7 * ROW_HEIGHT, Component.empty());
    }

    /** Update displayed element amounts. */
    public void setElements(Map<ElementType, Long> elements) {
        this.elements = (elements != null) ? elements : Map.of();
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;

        var font = Minecraft.getInstance().font;
        int idx = 0;
        for (ElementType type : ElementType.values()) {
            int rowY = getY() + idx * ROW_HEIGHT;
            long amount = elements.getOrDefault(type, 0L);

            // Programmatic colored circle icon
            drawElementIcon(g, getX() + 1, rowY + 1, ICON_SIZE,
                    ICON_COLORS.getOrDefault(type, 0xFF888888),
                    type.getId().charAt(0));

            // Name
            int nameColor = NAME_COLORS.getOrDefault(type, MedievalColors.TEXT_WARM_WHITE);
            Component displayName = I18n.name("element.wandscape." + type.getId(), capitalize(type.getId()));
            g.drawString(font, displayName, getX() + ICON_SIZE + 4, rowY + 4, nameColor);

            // Amount (right-aligned)
            String amountStr = amount == 0 ? "0" : formatAmount(amount);
            int amountColor = amount > 0 ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_DIM;
            int amountWidth = font.width(amountStr);
            g.drawString(font, amountStr, getX() + width - amountWidth - 2, rowY + 4, amountColor);

            idx++;
        }
    }

    /**
     * Draw a 16x16 colored circle with a centered letter glyph.
     * Approximates a circle using row-by-row pixel widths.
     */
    private static void drawElementIcon(GuiGraphics g, int x, int y, int size, int color, char glyph) {
        int half = size / 2;
        int cx = x + half;
        int cy = y + half;
        int r = size / 2 - 1;

        // Draw filled circle row by row (approximation for 16x16)
        for (int dy = -r; dy <= r; dy++) {
            // Circle equation: dx^2 + dy^2 <= r^2
            int maxDx = (int) Math.sqrt(r * r - dy * dy);
            if (maxDx < 0) continue;
            int rowY = cy + dy;
            g.fill(cx - maxDx, rowY, cx + maxDx + 1, rowY + 1, color);
        }

        // Letter glyph (centered, uppercase)
        var font = Minecraft.getInstance().font;
        String letter = String.valueOf(Character.toUpperCase(glyph));
        int letterW = font.width(letter);
        g.drawString(font, letter,
                cx - letterW / 2, cy - 5,
                MedievalColors.TEXT_WARM_WHITE);
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String formatAmount(long value) {
        if (value < 1_000) return String.valueOf(value);
        if (value < 1_000_000) return String.format("%.1fK", value / 1_000.0);
        return String.format("%.1fM", value / 1_000_000.0);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.USAGE, Component.literal("Element storage"));
    }
}
