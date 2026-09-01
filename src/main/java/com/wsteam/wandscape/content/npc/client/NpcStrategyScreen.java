package com.wsteam.wandscape.content.npc.client;

import com.wsteam.wandscape.core.component.EquippedMagicComponent;
import com.wsteam.wandscape.content.magic.data.MagicDef;
import com.wsteam.wandscape.content.npc.NpcStrategyMenu;
import com.wsteam.wandscape.content.npc.network.NpcDataPacket;
import com.wsteam.wandscape.content.npc.network.NpcStrategyPacket;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.ReplayProtectedScreen;
import com.wsteam.wandscape.shared.ui.component.HelpButton;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.skin.SkinRender;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 施法策略屏（容器化）：顶部 4 个总体策略预设按钮 + 中部 4 分类 × 3 卷轴槽（真实
 * vanilla 槽，槽位序 = 类内施法优先级；放卷轴装备、取出即拿回卷轴、Shift 快速转移）
 * + 底部原版玩家背包。槽变更由服务端 {@link NpcStrategyMenu} 校验并写回 NPC 的
 * {@code EquippedMagicComponent}。
 */
public class NpcStrategyScreen extends AbstractContainerScreen<NpcStrategyMenu>
        implements ReplayProtectedScreen {

    private static final int HEADER_H = 22;
    private static final int GLASS_TOP = 0xBB483828;
    private static final int GLASS_BOTTOM = 0xBB1E1410;
    private static final int BTN_W = 62;
    private static final int CLOSE_W = 18;
    private static final int CLOSE_H = 14;
    private static final ResourceLocation INVENTORY_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/gui/container/inventory.png");

    private static final List<String> PRESET_NAMES = List.of("balanced", "offensive", "support", "defensive");

    /** 实体 id：客户端构造时未知（-1），经 NpcDataPacket.apply 更新。 */
    private int entityId = -1;
    private String preset = "BALANCED";
    private int closeBtnX, closeBtnY;
    private HelpButton helpButton;
    private final String helpDocumentPath = "strategy_guide";
    private final java.util.Map<String, int[]> presetButtonBounds = new java.util.LinkedHashMap<>();

    public NpcStrategyScreen(NpcStrategyMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = NpcStrategyMenu.PANEL_W;
        this.imageHeight = NpcStrategyMenu.PANEL_H;
    }

    /** 服务端回发策略数据时刷新预设高亮与实体 id。 */
    public void apply(NpcDataPacket packet) {
        this.entityId = packet.entityId();
        this.preset = packet.strategyPreset() != null ? packet.strategyPreset() : "BALANCED";
    }

    @Override
    protected void init() {
        super.init();
        closeBtnX = leftPos + imageWidth - CLOSE_W - 6;
        closeBtnY = topPos + (HEADER_H - CLOSE_H) / 2;
        helpButton = new HelpButton(closeBtnX - 14 - 4, topPos + (HEADER_H - 14) / 2, 14, 14,
                this::openHelpDocument);
        addRenderableWidget(helpButton);

        presetButtonBounds.clear();
        int x = leftPos + 12;
        int presetY = topPos + 28;
        for (String name : PRESET_NAMES) {
            final String p = name.toUpperCase();
            MedievalButton btn = new MedievalButton(x, presetY, BTN_W, 16,
                    I18n.name("gui.wandscape.strategy.preset." + name, name),
                    () -> {
                this.preset = p;
                PacketDistributor.sendToServer(new NpcStrategyPacket(
                        entityId, p, List.of(), NpcStrategyPacket.NO_CONSUME));
            });
            addRenderableWidget(btn);
            presetButtonBounds.put(p, new int[]{x, presetY});
            x += BTN_W + 6;
        }

        addRenderableWidget(new MedievalButton(
                leftPos + imageWidth - 66, topPos + imageHeight - 22, 46, 16,
                I18n.name("gui.wandscape.common.close", "Close"),
                () -> Minecraft.getInstance().setScreen(null)));
    }

    public void openHelpDocument() {
        if (helpDocumentPath != null && minecraft != null) {
            String content = com.wsteam.wandscape.shared.ui.markdown.navigation.DocumentLoader
                    .loadMarkdown(helpDocumentPath);
            minecraft.setScreen(new com.wsteam.wandscape.shared.ui.guide.GuideTestScreen(
                    this, content, helpDocumentPath));
        }
    }

    // ── 渲染 ──

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        // 与仓库一致：原版 render 不画 tooltip，需显式调用（悬停物品显示介绍）
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fillGradient(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight,
                GLASS_TOP, GLASS_BOTTOM);
        drawGlowBorder(g, leftPos, topPos, imageWidth, imageHeight, MedievalColors.BORDER_GOLD);
        renderHeader(g, mouseX, mouseY);
        renderInventoryBackground(g);
        renderCategoryRows(g);
        renderSpecialPanel(g);
    }

    private void renderHeader(GuiGraphics g, int mouseX, int mouseY) {
        int hx = leftPos + 1;
        int hy = topPos + 1;
        g.fillGradient(hx, hy, hx + imageWidth - 2, hy + HEADER_H, 0xFF502870, 0xFF1A0830);
        g.fill(hx, hy + HEADER_H, hx + imageWidth - 2, hy + HEADER_H + 1, MedievalColors.BORDER_GOLD);
        g.fillGradient(hx, hy, hx + 3, hy + HEADER_H, 0xFFD4A840, 0xFF6A4020);
        g.drawString(font, I18n.name("gui.wandscape.strategy.title", "Cast Strategy"),
                hx + 10, hy + (HEADER_H - font.lineHeight) / 2,
                MedievalColors.TEXT_WARM_WHITE);
        SkinRender.drawCloseButton(g, closeBtnX, closeBtnY, CLOSE_W, CLOSE_H,
                isInRect(mouseX, mouseY, closeBtnX, closeBtnY, CLOSE_W, CLOSE_H) ? 1 : 0);
    }

    /** 玩家背包区底（与 NPC 装备屏一致）。 */
    private void renderInventoryBackground(GuiGraphics g) {
        int dstY = topPos + NpcStrategyMenu.PLAYER_INV_Y;
        g.blit(INVENTORY_TEXTURE, leftPos + 8, dstY, 8, 84, 9 * 18, 76);
    }

    /** 分类标签行（槽由 vanilla 渲染，位置见 {@link NpcStrategyMenu} 常量）。 */
    private void renderCategoryRows(GuiGraphics g) {
        // 当前预设高亮
        int[] pb = presetButtonBounds.get(preset);
        if (pb != null) {
            drawGlowBorder(g, pb[0], pb[1], BTN_W, 16, MedievalColors.BORDER_GOLD);
        }
        for (int cat = 0; cat < EquippedMagicComponent.CATEGORIES.size(); cat++) {
            String name = EquippedMagicComponent.CATEGORIES.get(cat);
            int rowY = topPos + NpcStrategyMenu.SPELL_Y + cat * NpcStrategyMenu.ROW_PITCH;
            g.drawString(font,
                    I18n.name("gui.wandscape.strategy.category." + name, name).getString(),
                    leftPos + 12, rowY + (NpcStrategyMenu.SLOT - font.lineHeight) / 2,
                    MedievalColors.TEXT_WARM_WHITE);
        }
        // 分隔线（槽区与背包之间）
        g.fill(leftPos + 8, topPos + 132, leftPos + imageWidth - 8, topPos + 133,
                MedievalColors.BORDER_GOLD_DARK);
    }

    /** 右侧只读「特殊」面板：列出所有 NPC 天生固有的特殊魔法（teleport/heal），不可更换。 */
    private void renderSpecialPanel(GuiGraphics g) {
        int colX = leftPos + 168;
        int startY = topPos + NpcStrategyMenu.SPELL_Y;
        // 槽区与特殊面板之间的竖向分隔线
        g.fill(leftPos + 158, startY - 2,
                leftPos + 159, startY + 3 * NpcStrategyMenu.ROW_PITCH + NpcStrategyMenu.SLOT,
                MedievalColors.BORDER_GOLD_DARK);
        g.drawString(font,
                I18n.name("gui.wandscape.strategy.category.special", "special").getString(),
                colX, startY + (NpcStrategyMenu.SLOT - font.lineHeight) / 2,
                MedievalColors.TEXT_WARM_WHITE);
        int y = startY + NpcStrategyMenu.ROW_PITCH;
        for (String magicId : MagicDef.SPECIAL_SPELLS) {
            g.drawString(font,
                    I18n.name("magic.wandscape." + magicId, magicId).getString(),
                    colX, y + (NpcStrategyMenu.SLOT - font.lineHeight) / 2,
                    MedievalColors.TEXT_MUTED);
            y += NpcStrategyMenu.ROW_PITCH;
        }
        g.drawString(font,
                I18n.name("gui.wandscape.strategy.special.note", "默认使用，不可更换").getString(),
                colX, startY + 3 * NpcStrategyMenu.ROW_PITCH + (NpcStrategyMenu.SLOT - font.lineHeight) / 2,
                MedievalColors.TEXT_DIM);
    }

    @Override
    protected void renderSlot(GuiGraphics g, net.minecraft.world.inventory.Slot slot) {
        if (slot instanceof NpcStrategyMenu.SpellSlot) {
            com.wsteam.wandscape.shared.ui.vanilla.VanillaPlayerInventory
                    .blitSlotBackground(g, slot.x, slot.y);
        }
        super.renderSlot(g, slot);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // 面板装饰由 renderBg 绘制；不画 vanilla 标签。
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isInRect(mouseX, mouseY, closeBtnX, closeBtnY, CLOSE_W, CLOSE_H)) {
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ── 皮肤工具 ──

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

    private static boolean isInRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}