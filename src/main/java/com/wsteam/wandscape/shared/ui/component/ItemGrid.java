package com.wsteam.wandscape.shared.ui.component;

import java.util.List;

import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.shared.ui.util.RenderUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
/**
 * Grid of item slots with virtual scrolling (by row).
 * Each cell shows an ItemStack icon + optional count text.
 */
public class ItemGrid extends AbstractWidget {

    private final int columns;
    private final int cellSize;
    private final int scrollbarWidth = 6;
    private List<ItemStack> items = List.of();
    private int selectedIndex = -1;
    private int scrollOffset;

    public ItemGrid(int x, int y, int width, int height, int columns, int cellSize) {
        super(x, y, width, height, Component.empty());
        this.columns = columns;
        this.cellSize = cellSize;
    }

    public void setItems(List<ItemStack> items) {
        this.items = items;
        this.scrollOffset = 0;
        this.selectedIndex = -1;
    }

    public ItemStack getSelectedItem() {
        return (selectedIndex >= 0 && selectedIndex < items.size()) ? items.get(selectedIndex) : ItemStack.EMPTY;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !active || button != 0) return false;

        int contentRight = getX() + width - scrollbarWidth;
        if (mouseX < getX() || mouseX >= contentRight) return false;

        int col = (int) (mouseX - getX()) / cellSize;
        int row = ((int) (mouseY - getY()) / cellSize) + (scrollOffset / cellSize);
        int index = row * columns + col;

        if (col >= 0 && col < columns && index >= 0 && index < items.size()) {
            selectedIndex = index;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!visible || !active) return false;
        int totalRows = (items.size() + columns - 1) / columns;
        int visibleRows = height / cellSize;
        int maxScroll = Math.max(0, totalRows * cellSize - height);
        scrollOffset = (int) Math.clamp(scrollOffset - scrollY * cellSize, 0, maxScroll);
        return true;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!visible) return;

        int contentRight = getX() + width - scrollbarWidth;
        g.enableScissor(getX(), getY(), contentRight, getY() + height);

        int totalRows = (items.size() + columns - 1) / columns;
        int startRow = scrollOffset / cellSize;
        int visibleRows = (height / cellSize) + 1;

        for (int row = startRow; row < Math.min(totalRows, startRow + visibleRows); row++) {
            for (int col = 0; col < columns; col++) {
                int index = row * columns + col;
                if (index >= items.size()) break;

                int cellX = getX() + col * cellSize;
                int cellY = getY() + row * cellSize - scrollOffset;
                boolean selected = index == selectedIndex;
                boolean hovered = mouseX >= cellX && mouseX < cellX + cellSize
                        && mouseY >= cellY && mouseY < cellY + cellSize;

                // Cell background
                if (selected) {
                    g.fill(cellX, cellY, cellX + cellSize, cellY + cellSize,
                            MedievalColors.PURPLE_BG);
                } else if (hovered) {
                    g.fill(cellX, cellY, cellX + cellSize, cellY + cellSize,
                            MedievalColors.PARCHMENT_LIGHT);
                }

                // Item icon
                g.renderItem(items.get(index), cellX + 2, cellY + 2);

                // Count text if stack size > 1
                int count = items.get(index).getCount();
                if (count > 1) {
                    g.renderItemDecorations(Minecraft.getInstance().font, items.get(index), cellX + 2, cellY + 2);
                }
            }
        }

        g.disableScissor();

        // Scrollbar
        RenderUtil.drawScrollbar(g, getX() + width - scrollbarWidth, getY(),
                scrollbarWidth, height, totalRows * cellSize, scrollOffset);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.USAGE,
                Component.literal("Item grid with " + items.size() + " items"));
    }
}
