package com.wsteam.wandscape.production.client;

import java.util.ArrayList;
import java.util.List;

import com.wsteam.wandscape.production.menu.CraftingStationMenu;
import com.wsteam.wandscape.production.network.CraftingStationPacket;
import com.wsteam.wandscape.production.network.CraftingStationPacket.RecipeEntry;
import com.wsteam.wandscape.production.network.RequestProductionTaskPacket;

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

public class CraftingStationScreen extends AbstractContainerScreen<CraftingStationMenu> {

    private static final int TEXTURE_W = 256;
    private static final int TEXTURE_H = 210;
    private static final int LIST_X = 8;
    private static final int LIST_Y = 24;
    private static final int ROW_HEIGHT = 18;
    private static final int VISIBLE_ROWS = 8;
    private static final int PANEL_W = 200;

    private BlockPos stationPos = BlockPos.ZERO;

    private List<RecipeEntry> recipes = new ArrayList<>();
    private int selectedIndex = -1;
    private int quantity = 1;
    private int scrollOffset = 0;

    public CraftingStationScreen(CraftingStationMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = TEXTURE_W;
        this.imageHeight = TEXTURE_H;
        this.inventoryLabelY = this.imageHeight - 94;
        this.inventoryLabelX = 8;
    }

    public void updateData(CraftingStationPacket packet) {
        this.stationPos = packet.stationPos();
        this.recipes = packet.entries();
        this.selectedIndex = -1;
        this.quantity = 1;
    }

    @Override
    protected void renderBg(GuiGraphics gfx, float partialTick, int mouseX, int mouseY) {
        gfx.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF_C0C0C0);
        gfx.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xFF_8B8B8B);

        // Panel
        gfx.fill(leftPos + LIST_X, topPos + LIST_Y,
                leftPos + LIST_X + PANEL_W, topPos + LIST_Y + VISIBLE_ROWS * ROW_HEIGHT,
                0xFF_404040);

        int maxRows = Math.min(VISIBLE_ROWS, recipes.size() - scrollOffset);
        for (int i = 0; i < maxRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= recipes.size()) break;
            RecipeEntry entry = recipes.get(idx);
            int rowY = topPos + LIST_Y + i * ROW_HEIGHT;

            boolean hovered = mouseX >= leftPos + LIST_X && mouseX <= leftPos + LIST_X + PANEL_W
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;

            if (idx == selectedIndex) {
                gfx.fill(leftPos + LIST_X, rowY, leftPos + LIST_X + PANEL_W, rowY + ROW_HEIGHT, 0x80_FFD700);
            } else if (hovered) {
                gfx.fill(leftPos + LIST_X, rowY, leftPos + LIST_X + PANEL_W, rowY + ROW_HEIGHT, 0x40_FFFFFF);
            }

            var item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(entry.outputItem()));
            if (item != null && item != Items.AIR) {
                gfx.renderItem(new ItemStack(item), leftPos + LIST_X + 2, rowY + 1);
            }

            String name = entry.outputItem();
            gfx.drawString(font, name, leftPos + LIST_X + 22, rowY + 1, 0xFFFFFF);

            StringBuilder costStr = new StringBuilder();
            entry.cost().forEach((elem, amt) -> {
                if (!costStr.isEmpty()) costStr.append(", ");
                costStr.append(elem.name().toLowerCase()).append(":").append(amt);
            });
            gfx.drawString(font, costStr.toString(), leftPos + LIST_X + 22, rowY + 9, 0xA0A0A0);

            if (entry.requiredLevel() > 1) {
                String lvl = "Lv." + entry.requiredLevel();
                int lw = font.width(lvl);
                gfx.drawString(font, lvl, leftPos + LIST_X + PANEL_W - lw - 4, rowY + 1, 0xFFD700);
            }
        }

        // Quantity
        int qy = topPos + LIST_Y + VISIBLE_ROWS * ROW_HEIGHT + 6;
        gfx.drawString(font, "Qty: " + quantity, leftPos + LIST_X, qy, 0xFFFFFF);
        drawButton(gfx, "-", leftPos + LIST_X + 50, qy - 2, 14, 14, mouseX, mouseY);
        drawButton(gfx, "+", leftPos + LIST_X + 68, qy - 2, 14, 14, mouseX, mouseY);

        // Submit
        drawButton(gfx, "Submit", leftPos + LIST_X + PANEL_W - 50, qy - 2, 46, 14, mouseX, mouseY);
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

    @Override
    protected void renderLabels(GuiGraphics gfx, int mouseX, int mouseY) {
        gfx.drawString(font, "Crafting Station", 8, 6, 0x404040);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        super.render(gfx, mouseX, mouseY, partialTick);
        renderTooltip(gfx, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);

        int mxInt = (int) mx;
        int myInt = (int) my;

        // Row selection
        if (mxInt >= leftPos + LIST_X && mxInt <= leftPos + LIST_X + PANEL_W
                && myInt >= topPos + LIST_Y && myInt < topPos + LIST_Y + VISIBLE_ROWS * ROW_HEIGHT) {
            int row = (myInt - topPos - LIST_Y) / ROW_HEIGHT;
            int idx = scrollOffset + row;
            if (idx >= 0 && idx < recipes.size()) {
                selectedIndex = idx;
                quantity = 1;
                return true;
            }
        }

        // Buttons
        int qy = topPos + LIST_Y + VISIBLE_ROWS * ROW_HEIGHT + 6;
        if (myInt >= qy - 2 && myInt < qy + 14) {
            if (mxInt >= leftPos + LIST_X + 50 && mxInt < leftPos + LIST_X + 64) {
                if (quantity > 1) quantity--;
                return true;
            }
            if (mxInt >= leftPos + LIST_X + 68 && mxInt < leftPos + LIST_X + 82) {
                if (quantity < 64) quantity++;
                return true;
            }
            if (mxInt >= leftPos + LIST_X + PANEL_W - 50 && mxInt < leftPos + LIST_X + PANEL_W) {
                submitTask();
                return true;
            }
        }

        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, recipes.size() - VISIBLE_ROWS);
        scrollOffset = (int) Math.clamp(scrollOffset - (int) scrollY, 0, maxScroll);
        return true;
    }

    private void submitTask() {
        if (selectedIndex < 0 || selectedIndex >= recipes.size()) return;
        RecipeEntry entry = recipes.get(selectedIndex);
        PacketDistributor.sendToServer(new RequestProductionTaskPacket(
                stationPos, "craft_wand", entry.recipeId(), quantity));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
