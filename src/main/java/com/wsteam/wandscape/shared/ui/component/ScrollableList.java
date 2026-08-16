package com.wsteam.wandscape.shared.ui.component;

import java.util.List;

import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.shared.ui.util.RenderUtil;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

public abstract class ScrollableList<T> extends AbstractWidget {

    protected final int rowHeight;
    protected final int scrollbarWidth = 6;
    protected int scrollOffset;
    protected int selectedIndex = -1;
    protected List<T> items = List.of();

    public ScrollableList(int x, int y, int width, int height, int rowHeight) {
        super(x, y, width, height, Component.empty());
        this.rowHeight = rowHeight;
    }

    public void setItems(List<T> items) {
        this.items = items;
        this.scrollOffset = 0;
        this.selectedIndex = -1;
    }

    /** Convenience: fires {@code onSelect} whenever the selected row changes. */
    public void setOnSelect(java.util.function.Consumer<Integer> onSelect) {
        this.onSelect = onSelect;
    }

    /** Returns the currently selected item, or null if nothing selected. */
    public T getSelected() {
        return (selectedIndex >= 0 && selectedIndex < items.size()) ? items.get(selectedIndex) : null;
    }

    private java.util.function.Consumer<Integer> onSelect;

    // ── Scrollbar drag state ──

    private boolean scrollbarDragging;
    private double dragStartMouseY;
    private int dragStartScrollOffset;

    // ── Row click callback ──

    @FunctionalInterface
    public interface RowClickHandler<T> {
        void onRowClick(T item, int index, int button);
    }

    private RowClickHandler<T> rowClickHandler;

    public void setOnRowClick(RowClickHandler<T> handler) {
        this.rowClickHandler = handler;
    }

    // ── Mouse events ──

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active) return false;

        int sbX = getX() + width - scrollbarWidth;
        int totalHeight = items.size() * rowHeight;

        // Scrollbar thumb: start drag
        if (totalHeight > height && mouseX >= sbX && mouseX < getX() + width) {
            int thumbHeight = Math.max(8, height * height / totalHeight);
            int maxScroll = totalHeight - height;
            int thumbY = getY() + (maxScroll == 0 ? 0 : scrollOffset * (height - thumbHeight) / maxScroll);
            if (mouseY >= thumbY && mouseY < thumbY + thumbHeight) {
                scrollbarDragging = true;
                dragStartMouseY = mouseY;
                dragStartScrollOffset = scrollOffset;
                return true;
            }
        }

        // Content area: row selection
        int contentRight = getX() + width - scrollbarWidth;
        if (mouseX < getX() || mouseX >= contentRight) return false;

        int relY = (int) mouseY - getY();
        if (relY < 0 || relY >= height) return false;
        int row = (relY + scrollOffset) / rowHeight;
        if (row >= 0 && row < items.size()) {
            int prevSelected = selectedIndex;
            selectedIndex = row;
            if (rowClickHandler != null) {
                rowClickHandler.onRowClick(items.get(row), row, button);
            }
            if (onSelect != null && prevSelected != selectedIndex) {
                onSelect.accept(selectedIndex);
            }
            return true;
        }
        return false;
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
        if (!scrollbarDragging) return;
        int totalHeight = items.size() * rowHeight;
        int maxScroll = Math.max(0, totalHeight - height);
        int thumbHeight = Math.max(8, height * height / totalHeight);
        int trackHeight = height - thumbHeight;
        if (trackHeight <= 0) return;
        double deltaY = mouseY - dragStartMouseY;
        scrollOffset = (int) Math.clamp(
                dragStartScrollOffset + deltaY * maxScroll / trackHeight,
                0, maxScroll);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (scrollbarDragging) {
            scrollbarDragging = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!visible || !active) return false;
        int totalRows = items.size();
        int maxScroll = Math.max(0, totalRows * rowHeight - height);

        scrollOffset = (int) Math.clamp(
                scrollOffset - scrollY * rowHeight * 2,
                0, maxScroll);
        return true;
    }

    // ── Rendering ──

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;

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

            if (selected) {
                g.fill(getX(), rowY, contentRight, rowY + rowHeight, MedievalColors.PURPLE_BG);
            } else if (hovered) {
                g.fill(getX(), rowY, contentRight, rowY + rowHeight, MedievalColors.PARCHMENT_LIGHT);
            }

            renderRow(g, items.get(i), getX() + 2, rowY, i, selected, hovered);
        }

        g.disableScissor();

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
