package com.wsteam.wandscape.compat.curios.client;

import com.wsteam.wandscape.compat.curios.NpcCurioSlot;
import com.wsteam.wandscape.compat.curios.NpcCuriosMenu;
import com.wsteam.wandscape.content.npc.network.NpcOpenEquipPacket;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.component.MedievalButton;
import com.wsteam.wandscape.foundation.ui.vanilla.VanillaPlayerInventory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Locale;

/**
 * 法师饰品容器屏幕：顶部法师饰品槽网格（空槽显示 Curios 槽类型图标），下方原版玩家背包。
 * 视觉风格对齐 {@code NpcScreen}（玻璃渐变 + 金边 + 紫色 header）。
 */
public class NpcCuriosScreen extends AbstractContainerScreen<NpcCuriosMenu> {

    private static final int HEADER_H = 22;
    private static final int GLASS_TOP = 0xBB483828;
    private static final int GLASS_BOTTOM = 0xBB1E1410;
    private static final int BORDER_COLOR = 0xFFD4A840;
    private static final ResourceLocation INVENTORY_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/gui/container/inventory.png");

    public NpcCuriosScreen(NpcCuriosMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = NpcCuriosMenu.PANEL_W;
        this.imageHeight = NpcCuriosMenu.panelHeight(menu.getCurioSlotCount());
    }

    @Override
    protected void init() {
        super.init();
        this.titleLabelX = 10;
        this.titleLabelY = (HEADER_H - 9) / 2;
        // 自绘 header，禁画 vanilla 标签
        this.inventoryLabelX = 100000;
        this.inventoryLabelY = 100000;
        // 返回按钮：重新打开法师装备界面（法师 UI）
        addRenderableWidget(new MedievalButton(leftPos + imageWidth - 62, topPos + 3, 54, 16,
                I18n.name("gui.wandscape.curios.back", "Back"), this::onBack));
    }

    private void onBack() {
        int entityId = menu.getEntityId();
        if (entityId >= 0 && minecraft != null) {
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
    protected void renderTooltip(GuiGraphics g, int mouseX, int mouseY) {
        super.renderTooltip(g, mouseX, mouseY);
        // 空饰品槽悬停：提示槽位名称（curios.identifier.<id>，缺失回退首字母大写）
        if (this.hoveredSlot instanceof NpcCurioSlot curio && this.menu.getCarried().isEmpty()
                && this.hoveredSlot.getItem().isEmpty()) {
            g.renderTooltip(font, Component.literal(slotName(curio.getIdentifier())), mouseX, mouseY);
        }
    }

    private static String slotName(String id) {
        String key = "curios.identifier." + id;
        if (net.minecraft.client.resources.language.I18n.exists(key)) {
            return net.minecraft.client.resources.language.I18n.get(key);
        }
        return Character.toUpperCase(id.charAt(0)) + id.substring(1).toLowerCase(Locale.ROOT);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fillGradient(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight,
                GLASS_TOP, GLASS_BOTTOM);
        drawGlowBorder(g, leftPos, topPos, imageWidth, imageHeight, BORDER_COLOR);
        renderHeader(g);

        // 饰品槽底（左)与右上装饰区样式统一用暗色槽底，空槽图标由 renderSlot 的 getNoItemIcon 绘制
        int curioSlots = menu.getCurioSlotCount();
        for (Slot slot : menu.slots.subList(0, curioSlots)) {
            VanillaPlayerInventory.blitSlotBackground(g, leftPos + slot.x, topPos + slot.y);
        }
        // 玩家背包底（3×9 + 快捷栏）
        int playerInvTop = NpcCuriosMenu.playerInvTop(curioSlots);
        g.blit(INVENTORY_TEXTURE, leftPos + VanillaPlayerInventory.INVENTORY_X,
                topPos + playerInvTop, 8, 84, 9 * 18, 76);
        // header 与背包区之间的分隔线
        g.fill(leftPos + 8, topPos + playerInvTop - 4, leftPos + imageWidth - 8,
                topPos + playerInvTop - 3, 0xFF6A4020);
    }

    private void renderHeader(GuiGraphics g) {
        int hx = leftPos + 1;
        int hy = topPos + 1;
        g.fillGradient(hx, hy, hx + imageWidth - 2, hy + HEADER_H, 0xFF502870, 0xFF1A0830);
        g.fill(hx, hy + HEADER_H, hx + imageWidth - 2, hy + HEADER_H + 1, BORDER_COLOR);
        g.fillGradient(hx, hy, hx + 3, hy + HEADER_H, 0xFFD4A840, 0xFF6A4020);
        g.drawString(font, I18n.name("gui.wandscape.curios.title", "Mage Trinkets"),
                leftPos + 10, topPos + (HEADER_H - 9) / 2, 0xFFE8D8B0);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // header 已覆盖面板标题；不画 vanilla 标签
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static void drawGlowBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        int c0 = color;
        int c1 = (color & 0x00FFFFFF) | 0x66000000;
        g.fill(x, y, x + w, y + 1, c0);
        g.fill(x, y + h - 1, x + w, y + h, c0);
        g.fill(x, y, x + 1, y + h, c0);
        g.fill(x + w - 1, y, x + w, y + h, c0);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, c1);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, c1);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, c1);
        g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, c1);
    }
}