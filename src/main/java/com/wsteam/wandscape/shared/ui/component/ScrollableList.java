package com.wsteam.wandscape.shared.ui.component;

import java.util.List;
import java.util.function.Consumer;

import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.shared.ui.util.RenderUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Virtual-scrolling list with themed scrollbar.
 *
 * @param <T> the item type
 */
public abstract class ScrollableList<T> extends AbstractWidget {

    protected final int rowHeight;
    protected final int scrollbarWidth = 6;
    protected int scrollOffset;
    protected int selectedIndex = -1;
    protected List<T> items = List.of();
    private Consumer<Integer> onSelect;

    public ScrollableList(int x, int y, int width, int height, int rowHeight) {
        super(x, y, width, height, Component.empty());
        this.rowHeight = rowHeight;
    }

    public void setItems(List<T> items) {
        this.items = items;
        this.scrollOffset = 0;
        this.selectedIndex = -1;
    }

    public void setOnSelect(Consumer<Integer> onSelect) {
        this.onSelect = onSelect;
    }

    public T getSelected() {
        return (selectedIndex >= 0 && selectedIndex < items.size()) ? items.get(selectedIndex) : null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active || button != 0) return false;

        int contentRight = getX() + width - scrollbarWidth;
        if (mouseX < getX() || mouseX >= contentRight) return false;

        int relY = (int) mouseY - getY();
        int row = (relY / rowHeight) + (scrollOffset / rowHeight);
        if (row >= 0 && row < items.size()) {
            selectedIndex = row;
            if (onSelect != null) onSelect.accept(row);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!visible || !active) return false;
        int totalRows = items.size();
        int visibleRows = height / rowHeight;
        int maxScroll = Math.max(0, totalRows * rowHeight - height);

        scrollOffset = (int) Math.clamp(
                scrollOffset - scrollY * rowHeight * 2,
                0, maxScroll);
        return true;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;

        // Scissor to visible area (content only, not scrollbar)
        int contentRight = getX() + width - scrollbarWidth;
        g.enableScissor(getX(), getY(), contentRight, getY() + height);

        int totalRows = items.size();
        int startRow = scrollOffset / rowHeight;
        int visibleRows = (height / rowHeight) + 1;

        for (int i = startRow; i < Math.min(totalRows, startRow + visibleRows); i++) {
            int rowY = getY() + i * rowHeight - scrollOffset;
            boolean selected = i == selectedIndex;
            boolean hovered = mouseX >= getX() && mouseX < contentRight
                    && mouseY >= rowY && mouseY < rowY + rowHeight;

            // Row background
            if (selected) {
                g.fill(getX(), rowY, contentRight, rowY + rowHeight,
                        MedievalColors.PURPLE_BG);
            } else if (hovered) {
                g.fill(getX(), rowY, contentRight, rowY + rowHeight,
                        MedievalColors.PARCHMENT_LIGHT);
            }

            renderRow(g, items.get(i), getX() + 2, rowY, i, selected, hovered);
        }

        g.disableScissor();

        // Scrollbar
        RenderUtil.drawScrollbar(g, getX() + width - scrollbarWidth, getY(),
                scrollbarWidth, height, totalRows * rowHeight, scrollOffset);
    }

    /** Render a single row. Override in subclass for custom row layouts. */
    protected abstract void renderRow(GuiGraphics g, T item,
                                       int x, int y, int index,
                                       boolean selected, boolean hovered);

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.USAGE,
                Component.literal("List with " + items.size() + " items"));
    }
}
