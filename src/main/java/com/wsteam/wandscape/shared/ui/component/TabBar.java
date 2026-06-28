package com.wsteam.wandscape.shared.ui.component;

import java.util.List;
import java.util.function.Consumer;

import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
/**
 * Horizontal tab bar. Each tab is a label string; the selected tab
 * gets a gold underline and gold text color.
 *
 * <p>Tab widths are computed from text width + padding.
 */
public class TabBar extends AbstractWidget {

    private final List<String> tabs;
    private int selectedIndex;
    private final Consumer<Integer> onSelect;

    private static final int TAB_PADDING = 10;
    private static final int TAB_HEIGHT = 16;
    private static final int UNDERLINE_HEIGHT = 2;

    public TabBar(int x, int y, int width, List<String> tabs, int initialIndex,
                  Consumer<Integer> onSelect) {
        super(x, y, width, TAB_HEIGHT, Component.empty());
        this.tabs = tabs;
        this.selectedIndex = initialIndex;
        this.onSelect = onSelect;
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setSelectedIndex(int index) {
        if (index >= 0 && index < tabs.size()) {
            this.selectedIndex = index;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active) return false;

        if (button == 0 && isMouseOver(mouseX, mouseY)) {
            float tabWidth = (float) width / tabs.size();
            int idx = (int) ((mouseX - getX()) / tabWidth);
            if (idx >= 0 && idx < tabs.size() && idx != selectedIndex) {
                selectedIndex = idx;
                if (onSelect != null) {
                    onSelect.accept(idx);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;

        var font = Minecraft.getInstance().font;
        float tabWidth = (float) width / tabs.size();
        int y = getY();

        // Tab separator line (full width, bottom)
        g.fill(getX(), y + TAB_HEIGHT - 1, getX() + width, y + TAB_HEIGHT,
                MedievalColors.BORDER_GOLD_DARK);

        for (int i = 0; i < tabs.size(); i++) {
            int tabX = getX() + (int) (i * tabWidth);
            int tabW = (int) tabWidth;
            boolean selected = i == selectedIndex;
            boolean hovered = isMouseOverTab(mouseX, mouseY, i, tabWidth);

            int textColor;
            if (selected) {
                textColor = MedievalColors.ACCENT_GOLD;
            } else if (hovered) {
                textColor = MedievalColors.TEXT_WARM_WHITE;
            } else {
                textColor = MedievalColors.TEXT_MUTED;
            }

            // Text centered in tab area
            int textWidth = font.width(tabs.get(i));
            int textX = tabX + (tabW - textWidth) / 2;
            g.drawString(font, tabs.get(i), textX, y + 3, textColor);

            // Gold underline for selected tab
            if (selected) {
                g.fill(tabX + TAB_PADDING, y + TAB_HEIGHT - UNDERLINE_HEIGHT,
                        tabX + tabW - TAB_PADDING, y + TAB_HEIGHT,
                        MedievalColors.ACCENT_GOLD);
            }
        }
    }

    private boolean isMouseOverTab(double mouseX, double mouseY, int index, float tabWidth) {
        int tabX = getX() + (int) (index * tabWidth);
        return mouseX >= tabX && mouseX < tabX + tabWidth
                && mouseY >= getY() && mouseY < getY() + TAB_HEIGHT;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.USAGE,
                Component.literal("Tab " + (selectedIndex + 1) + " of " + tabs.size()));
    }
}
