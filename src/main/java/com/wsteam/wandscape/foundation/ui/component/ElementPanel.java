package com.wsteam.wandscape.foundation.ui.component;

import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.Map;
/**
 * Displays all 7 element types with icon, name, and formatted quantity.
 * Each row is 18px tall (icon 16x16 + 2px spacing).
 *
 * <p>Icons are the PNG textures at {@code assets/wandscape/textures/gui/icons/element_{id}.png},
 * tinted per element the same way as the V-key panel top bar.
 */
public class ElementPanel extends AbstractWidget {

    private static final int ROW_HEIGHT = 18;
    private static final int ICON_SIZE = 16;

    /** Map from ElementType to stored amount. */
    private Map<ElementType, Long> elements = new LinkedHashMap<>();

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

            // PNG element icon tinted per element (same look as V-key panel top bar)
            WandscapeTheme.drawIcon(g, WandscapeTheme.elementIcon(type.getId()),
                    getX() + 1, rowY + 1, ICON_SIZE, ICON_SIZE,
                    WandscapeTheme.elementColor(type.getId()));

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
