package com.wsteam.wandscape.content.npc.client;

import com.wsteam.wandscape.content.npc.NpcInventoryMenu;
import com.wsteam.wandscape.content.npc.network.NpcOpenEquipPacket;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.component.MedievalButton;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import com.wsteam.wandscape.foundation.ui.vanilla.VanillaPlayerInventory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 法师背包容器屏幕：顶部法师 27 格背包（3×9），下方原版玩家背包。
 * 视觉风格对齐 {@code NpcScreen} 与 {@code NpcCuriosScreen}（玻璃渐变 + 金边 + 紫色 header）。
 */
public class NpcInventoryScreen extends AbstractContainerScreen<NpcInventoryMenu> {

    private static final int HEADER_H = 22;
    private static final int GLASS_TOP = 0xBB483828;
    private static final int GLASS_BOTTOM = 0xBB1E1410;
    private static final ResourceLocation INVENTORY_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/gui/container/inventory.png");

    public NpcInventoryScreen(NpcInventoryMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = NpcInventoryMenu.PANEL_W;
        this.imageHeight = NpcInventoryMenu.PANEL_H;
    }

    @Override
    protected void init() {
        super.init();
        this.titleLabelX = 10;
        this.titleLabelY = (HEADER_H - 9) / 2;
        // 自绘 header，禁画 vanilla 标签
        this.inventoryLabelX = 100000;
        this.inventoryLabelY = 100000;
        // 返回按钮：重新打开法师主界面（NpcScreen）
        addRenderableWidget(new MedievalButton(leftPos + imageWidth - 62, topPos + 3, 54, 16,
                I18n.name("gui.wandscape.curios.back", "Back"), this::onBack));
    }

    private void onBack() {
        int entityId = menu.getEntityId() >= 0 ? menu.getEntityId() : NpcScreenNavigator.getLastEntityId();
        if (entityId >= 0 && minecraft != null) {
            NpcScreenNavigator.prepareTransition(entityId);
            PacketDistributor.sendToServer(new NpcOpenEquipPacket(entityId));
        } else if (minecraft != null && minecraft.player != null) {
            minecraft.player.closeContainer();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fillGradient(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight,
                GLASS_TOP, GLASS_BOTTOM);
        drawGlowBorder(g, leftPos, topPos, imageWidth, imageHeight, MedievalColors.BORDER_GOLD);
        renderHeader(g);

        // NPC 背包槽底
        for (int i = 0; i < NpcInventoryMenu.NPC_SLOT_COUNT; i++) {
            Slot slot = menu.slots.get(i);
            VanillaPlayerInventory.blitSlotBackground(g, leftPos + slot.x, topPos + slot.y);
        }

        // 玩家背包底（3×9 + 快捷栏）
        g.blit(INVENTORY_TEXTURE, leftPos + 8, topPos + NpcInventoryMenu.PLAYER_INV_Y,
                8, 84, 9 * 18, 76);

        // header 与背包区之间的分隔线
        g.fill(leftPos + 8, topPos + NpcInventoryMenu.PLAYER_INV_Y - 4,
                leftPos + imageWidth - 8, topPos + NpcInventoryMenu.PLAYER_INV_Y - 3,
                MedievalColors.BORDER_GOLD_DARK);
    }

    private void renderHeader(GuiGraphics g) {
        int hx = leftPos + 1;
        int hy = topPos + 1;
        g.fillGradient(hx, hy, hx + imageWidth - 2, hy + HEADER_H, 0xFF502870, 0xFF1A0830);
        g.fill(hx, hy + HEADER_H, hx + imageWidth - 2, hy + HEADER_H + 1, MedievalColors.BORDER_GOLD);
        g.fillGradient(hx, hy, hx + 3, hy + HEADER_H, 0xFFD4A840, 0xFF6A4020);
        g.drawString(font, I18n.name("gui.wandscape.npc.inventory", "Inventory"),
                leftPos + 10, topPos + (HEADER_H - 9) / 2, 0xFFE8D8B0);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // header 已覆盖面板标题；不画 vanilla 标签
    }

    private static void drawGlowBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        int c0 = color;
        int c1 = (color & 0x00FFFFFF) | 0x66000000;
        g.fill(x, y, x + w, y + 1, c0);
        g.fill(x, y + h - 1, x + w, y + h, c0);
        g.fill(x, y + 1, x + 1, y + h, c0);
        g.fill(x + w - 1, y, x + w, y + h, c0);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, c1);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, c1);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, c1);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, c1);
    }
}
