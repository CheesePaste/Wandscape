package com.wsteam.wandscape.warehouse.client;

import java.util.ArrayList;
import java.util.List;

import com.wsteam.wandscape.warehouse.WarehouseMenu;
import com.wsteam.wandscape.warehouse.network.WarehouseDataPacket;
import com.wsteam.wandscape.warehouse.network.WarehouseDataPacket.ItemEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Warehouse GUI screen.
 *
 * <p>Left panel: item list (icon + name + count).
 * Right area: player inventory (standard slots).
 *
 * <p>Data is received via {@link WarehouseDataPacket} and stored here.
 * No interactive slots beyond the player inventory.
 */
public class WarehouseScreen extends AbstractContainerScreen<WarehouseMenu> {

    private static final int TEXTURE_W = 256;
    private static final int TEXTURE_H = 210;

    private static final int LIST_X = 8;
    private static final int LIST_Y = 18;
    private static final int ROW_HEIGHT = 18;
    private static final int VISIBLE_ROWS = 8;

    private static final Component TITLE = Component.literal("Colony Warehouse");

    // ── Item data (set by packet) ──
    private List<ItemEntry> items = new ArrayList<>();

    // ── Scroll ──
    private int scrollOffset = 0;

    public WarehouseScreen(WarehouseMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, TITLE);
        this.imageWidth = TEXTURE_W;
        this.imageHeight = TEXTURE_H;
        this.inventoryLabelY = this.imageHeight - 94;
        this.inventoryLabelX = 8;
    }

    /** Called by {@link WarehouseDataPacket#handleClient} to push item data. */
    public void updateItems(WarehouseDataPacket packet) {
        this.items = packet.entries();
    }

    // ════════════════════════════════════════════════════════════
    //  Render
    // ════════════════════════════════════════════════════════════

    @Override
    protected void renderBg(GuiGraphics gfx, float partialTick, int mouseX, int mouseY) {
        // Background
        gfx.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF_C0C0C0);
        gfx.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xFF_8B8B8B);

        // Left panel background
        int panelW = 160;
        gfx.fill(leftPos + LIST_X, topPos + LIST_Y,
                leftPos + LIST_X + panelW, topPos + LIST_Y + VISIBLE_ROWS * ROW_HEIGHT,
                0xFF_404040);

        // Item rows
        int maxRows = Math.min(VISIBLE_ROWS, items.size() - scrollOffset);
        for (int i = 0; i < maxRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= items.size()) break;
            ItemEntry entry = items.get(idx);
            int rowY = topPos + LIST_Y + i * ROW_HEIGHT;

            // Highlight on hover
            int mouseRow = (mouseY - (topPos + LIST_Y)) / ROW_HEIGHT;
            if (i == mouseRow && mouseX >= leftPos + LIST_X && mouseX <= leftPos + LIST_X + panelW) {
                gfx.fill(leftPos + LIST_X, rowY,
                        leftPos + LIST_X + panelW, rowY + ROW_HEIGHT, 0x80_FFFFFF);
            }

            // Item icon
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(entry.itemId()));
            if (item != null && item != Items.AIR) {
                ItemStack stack = new ItemStack(item);
                gfx.renderItem(stack, leftPos + LIST_X + 2, rowY + 1);
            }

            // Item name + count
            String label = entry.itemId() + "  ×" + formatCount(entry.count());
            int color = 0xFFFFFF;
            gfx.drawString(font, label, leftPos + LIST_X + 22, rowY + 5, color);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics gfx, int mouseX, int mouseY) {
        // Title
        gfx.drawString(font, TITLE, 8, 6, 0x404040);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        renderTooltip(gfx, mouseX, mouseY);

        // Scroll hint
        if (items.size() > VISIBLE_ROWS) {
            int maxScroll = items.size() - VISIBLE_ROWS;
            gfx.drawString(font, "Scroll: " + scrollOffset + "/" + maxScroll,
                    leftPos + LIST_X, topPos + LIST_Y + VISIBLE_ROWS * ROW_HEIGHT + 2, 0xA0A0A0);
        }
    }

    // ════════════════════════════════════════════════════════════
    //  Scrolling
    // ════════════════════════════════════════════════════════════

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, items.size() - VISIBLE_ROWS);
        scrollOffset = (int) Math.clamp(scrollOffset - (int) scrollY, 0, maxScroll);
        return true;
    }

    // ════════════════════════════════════════════════════════════
    //  Display config
    // ════════════════════════════════════════════════════════════

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════

    private static String formatCount(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
        return String.format("%.1fM", n / 1_000_000.0);
    }
}
