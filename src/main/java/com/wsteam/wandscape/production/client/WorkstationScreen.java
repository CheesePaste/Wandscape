package com.wsteam.wandscape.production.client;

import java.util.ArrayList;
import java.util.List;

import com.wsteam.wandscape.production.menu.WorkstationMenu;
import com.wsteam.wandscape.production.network.RequestProductionTaskPacket;
import com.wsteam.wandscape.production.network.WorkstationDataPacket;
import com.wsteam.wandscape.production.network.WorkstationDataPacket.DecomposableEntry;
import com.wsteam.wandscape.production.network.WorkstationDataPacket.SynthesizeEntry;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

public class WorkstationScreen extends AbstractContainerScreen<WorkstationMenu> {

    private static final int TEXTURE_W = 256;
    private static final int TEXTURE_H = 210;
    private static final int LIST_X = 8;
    private static final int LIST_Y = 30;
    private static final int ROW_HEIGHT = 16;
    private static final int VISIBLE_ROWS = 7;
    private static final int PANEL_W = 160;

    private BlockPos stationPos = BlockPos.ZERO;

    // Tab state: 0 = decompose, 1 = synthesize
    private int activeTab = 0;

    // Data (set by packet)
    private List<DecomposableEntry> decomposableItems = new ArrayList<>();
    private List<SynthesizeEntry> synthesizeRecipes = new ArrayList<>();

    // Selection
    private int selectedIndex = -1;
    private int quantity = 1;
    private int scrollOffset = 0;

    public WorkstationScreen(WorkstationMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = TEXTURE_W;
        this.imageHeight = TEXTURE_H;
        this.inventoryLabelY = this.imageHeight - 94;
        this.inventoryLabelX = 8;
    }

    /** Called by packet handler to push data. */
    public void updateData(WorkstationDataPacket packet) {
        this.stationPos = packet.stationPos();
        this.decomposableItems = packet.decomposableEntries();
        this.synthesizeRecipes = packet.synthesizeEntries();
        this.selectedIndex = -1;
        this.quantity = 1;
    }

    // ════════════════════════════════════════════════════════════
    //  Render
    // ════════════════════════════════════════════════════════════

    @Override
    protected void renderBg(GuiGraphics gfx, float partialTick, int mouseX, int mouseY) {
        // Background
        gfx.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF_C0C0C0);
        gfx.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xFF_8B8B8B);

        // Tab bar
        drawTab(gfx, 0, "Decompose", leftPos + LIST_X, topPos + 6);
        drawTab(gfx, 1, "Synthesize", leftPos + LIST_X + 80, topPos + 6);

        // Panel background
        gfx.fill(leftPos + LIST_X, topPos + LIST_Y,
                leftPos + LIST_X + PANEL_W, topPos + LIST_Y + VISIBLE_ROWS * ROW_HEIGHT,
                0xFF_404040);

        // Row content
        if (activeTab == 0) {
            renderDecomposeRows(gfx, mouseX, mouseY);
        } else {
            renderSynthesizeRows(gfx, mouseX, mouseY);
        }

        // Quantity controls
        int qy = topPos + LIST_Y + VISIBLE_ROWS * ROW_HEIGHT + 6;
        gfx.drawString(font, "Qty: " + quantity, leftPos + LIST_X, qy, 0xFFFFFF);
        drawButton(gfx, "-", leftPos + LIST_X + 50, qy - 2, 14, 14, mouseX, mouseY);
        drawButton(gfx, "+", leftPos + LIST_X + 68, qy - 2, 14, 14, mouseX, mouseY);

        // Submit button
        drawButton(gfx, "Submit", leftPos + LIST_X + PANEL_W - 50, qy - 2, 46, 14, mouseX, mouseY);
    }

    private void renderDecomposeRows(GuiGraphics gfx, int mouseX, int mouseY) {
        int maxRows = Math.min(VISIBLE_ROWS, decomposableItems.size() - scrollOffset);
        for (int i = 0; i < maxRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= decomposableItems.size()) break;
            DecomposableEntry entry = decomposableItems.get(idx);
            int rowY = topPos + LIST_Y + i * ROW_HEIGHT;

            boolean hovered = isRowHovered(mouseX, mouseY, i);
            if (idx == selectedIndex) {
                gfx.fill(leftPos + LIST_X, rowY, leftPos + LIST_X + PANEL_W, rowY + ROW_HEIGHT, 0x80_FFD700);
            } else if (hovered) {
                gfx.fill(leftPos + LIST_X, rowY, leftPos + LIST_X + PANEL_W, rowY + ROW_HEIGHT, 0x40_FFFFFF);
            }

            var item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(entry.itemId()));
            if (item != null && item != Items.AIR) {
                gfx.renderItem(new ItemStack(item), leftPos + LIST_X + 2, rowY);
            }
            String label = entry.itemId() + "  x" + formatCount(entry.count());
            gfx.drawString(font, label, leftPos + LIST_X + 20, rowY + 4, 0xFFFFFF);
        }
    }

    private void renderSynthesizeRows(GuiGraphics gfx, int mouseX, int mouseY) {
        int maxRows = Math.min(VISIBLE_ROWS, synthesizeRecipes.size() - scrollOffset);
        for (int i = 0; i < maxRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= synthesizeRecipes.size()) break;
            SynthesizeEntry entry = synthesizeRecipes.get(idx);
            int rowY = topPos + LIST_Y + i * ROW_HEIGHT;

            boolean hovered = isRowHovered(mouseX, mouseY, i);
            if (idx == selectedIndex) {
                gfx.fill(leftPos + LIST_X, rowY, leftPos + LIST_X + PANEL_W, rowY + ROW_HEIGHT, 0x80_FFD700);
            } else if (hovered) {
                gfx.fill(leftPos + LIST_X, rowY, leftPos + LIST_X + PANEL_W, rowY + ROW_HEIGHT, 0x40_FFFFFF);
            }

            var item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(entry.outputItem()));
            if (item != null && item != Items.AIR) {
                gfx.renderItem(new ItemStack(item), leftPos + LIST_X + 2, rowY);
            }
            StringBuilder costStr = new StringBuilder();
            entry.cost().forEach((elem, amt) -> {
                if (!costStr.isEmpty()) costStr.append(", ");
                costStr.append(elem.name().toLowerCase()).append(":").append(amt);
            });
            gfx.drawString(font, entry.outputItem(), leftPos + LIST_X + 20, rowY + 1, 0xFFFFFF);
            gfx.drawString(font, costStr.toString(), leftPos + LIST_X + 20, rowY + 9, 0xA0A0A0);
        }
    }

    private void drawTab(GuiGraphics gfx, int tabIdx, String label, int x, int y) {
        int color = (activeTab == tabIdx) ? 0xFF_D4A017 : 0xFF_666666;
        int bgColor = (activeTab == tabIdx) ? 0xFF_505050 : 0xFF_303030;
        gfx.fill(x, y, x + 76, y + 14, bgColor);
        gfx.drawString(font, label, x + 4, y + 3, color);
    }

    private void drawButton(GuiGraphics gfx, String label, int x, int y, int w, int h, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int bg = hovered ? 0xFF_707070 : 0xFF_505050;
        gfx.fill(x, y, x + w, y + h, bg);
        gfx.fill(x, y, x + w, y + 1, 0xFF_909090);
        gfx.fill(x, y + h - 1, x + w, y + h, 0xFF_303030);
        int textW = font.width(label);
        gfx.drawString(font, label, x + (w - textW) / 2, y + 3, 0xFFFFFF);
    }

    private boolean isRowHovered(int mouseX, int mouseY, int row) {
        int rowY = topPos + LIST_Y + row * ROW_HEIGHT;
        return mouseX >= leftPos + LIST_X && mouseX <= leftPos + LIST_X + PANEL_W
                && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
    }

    @Override
    protected void renderLabels(GuiGraphics gfx, int mouseX, int mouseY) {
        gfx.drawString(font, "Workstation", 8, 6, 0x404040);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        renderTooltip(gfx, mouseX, mouseY);
    }

    // ════════════════════════════════════════════════════════════
    //  Input
    // ════════════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);

        int mxInt = (int) mx;
        int myInt = (int) my;

        // Tab clicks
        if (myInt >= topPos + 6 && myInt < topPos + 20) {
            if (mxInt >= leftPos + LIST_X && mxInt < leftPos + LIST_X + 76) {
                activeTab = 0;
                selectedIndex = -1;
                scrollOffset = 0;
                return true;
            }
            if (mxInt >= leftPos + LIST_X + 80 && mxInt < leftPos + LIST_X + 156) {
                activeTab = 1;
                selectedIndex = -1;
                scrollOffset = 0;
                return true;
            }
        }

        // Row clicks
        if (mxInt >= leftPos + LIST_X && mxInt <= leftPos + LIST_X + PANEL_W
                && myInt >= topPos + LIST_Y && myInt < topPos + LIST_Y + VISIBLE_ROWS * ROW_HEIGHT) {
            int row = (myInt - topPos - LIST_Y) / ROW_HEIGHT;
            int idx = scrollOffset + row;
            int max = (activeTab == 0) ? decomposableItems.size() : synthesizeRecipes.size();
            if (idx >= 0 && idx < max) {
                selectedIndex = idx;
                quantity = 1;
                return true;
            }
        }

        // Quantity / Submit buttons
        int qy = topPos + LIST_Y + VISIBLE_ROWS * ROW_HEIGHT + 6;
        if (myInt >= qy - 2 && myInt < qy + 14) {
            // Minus
            if (mxInt >= leftPos + LIST_X + 50 && mxInt < leftPos + LIST_X + 64) {
                if (quantity > 1) quantity--;
                return true;
            }
            // Plus
            if (mxInt >= leftPos + LIST_X + 68 && mxInt < leftPos + LIST_X + 82) {
                if (quantity < 64) quantity++;
                return true;
            }
            // Submit
            if (mxInt >= leftPos + LIST_X + PANEL_W - 50 && mxInt < leftPos + LIST_X + PANEL_W) {
                submitTask();
                return true;
            }
        }

        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = (activeTab == 0) ? decomposableItems.size() : synthesizeRecipes.size();
        int maxScroll = Math.max(0, max - VISIBLE_ROWS);
        scrollOffset = (int) Math.clamp(scrollOffset - (int) scrollY, 0, maxScroll);
        return true;
    }

    private void submitTask() {
        String action = (activeTab == 0) ? "decompose" : "synthesize";
        String recipeOrItemId;
        if (activeTab == 0) {
            if (selectedIndex < 0 || selectedIndex >= decomposableItems.size()) return;
            recipeOrItemId = decomposableItems.get(selectedIndex).itemId();
        } else {
            if (selectedIndex < 0 || selectedIndex >= synthesizeRecipes.size()) return;
            recipeOrItemId = synthesizeRecipes.get(selectedIndex).recipeId();
        }

        PacketDistributor.sendToServer(new RequestProductionTaskPacket(
                stationPos, action, recipeOrItemId, quantity));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String formatCount(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
        return String.format("%.1fM", n / 1_000_000.0);
    }
}
