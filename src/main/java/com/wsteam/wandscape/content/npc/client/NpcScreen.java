package com.wsteam.wandscape.content.npc.client;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.types.EntityId;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.compat.curios.CuriosCompat;
import com.wsteam.wandscape.compat.curios.NpcOpenCuriosPacket;
import com.wsteam.wandscape.compat.curios.client.NpcCuriosButton;
import com.wsteam.wandscape.content.npc.NpcMenu;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.network.*;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.ReplayProtectedScreen;
import com.wsteam.wandscape.foundation.ui.component.HelpButton;
import com.wsteam.wandscape.foundation.ui.component.MedievalButton;
import com.wsteam.wandscape.foundation.ui.component.MedievalConfirmDialog;
import com.wsteam.wandscape.foundation.ui.skin.SkinRender;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import com.wsteam.wandscape.foundation.ui.vanilla.VanillaPlayerInventory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * NPC 装备界面（容器化）：左列 4 盔甲槽 + 1 法杖槽（真实 vanilla 槽，左键/右键/
 * Shift/拖拽全部生效），中间 3D 模型，右侧属性，底部原版玩家背包（
 * {@link VanillaPlayerInventory} 槽 + 原版背包底）。装备槽变更即时写回 NPC 实体。
 *
 * <p>属性/名字等展示数据经 {@link NpcDataPacket} 刷新（打开时服务端补发）。
 */
public class NpcScreen extends AbstractContainerScreen<NpcMenu> implements ReplayProtectedScreen {

    private static final int HEADER_H = 22;
    private static final int CLOSE_W = 18;
    private static final int CLOSE_H = 14;
    private static final int GLASS_TOP = 0xBB483828;
    private static final int GLASS_BOTTOM = 0xBB1E1410;
    private static final ResourceLocation INVENTORY_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/gui/container/inventory.png");

    // NPC 数据
    /** 实体 id：客户端构造时未知（-1），经 NpcDataPacket.apply 更新。 */
    private int entityId = -1;
    private String npcName;
    private int currentHealth, maxHealth;
    private int currentMana, maxMana;
    private float moveSpeed, spellPower, workSpeed, spellSpeed, armorValue;
    private ItemStack wandStack = ItemStack.EMPTY;
    private boolean isDefaultWand = true;
    private boolean peaceMode;
    private boolean followMode;
    private int skinVariant;
    private int hatColor;
    private WandscapeNpc displayNpc;

    // 布局（init/render 时计算）
    private int contentTop;
    private int rightCol;
    private int modelX, modelY, modelW, modelH;
    private int closeBtnX, closeBtnY;
    private HelpButton helpButton;
    private final String helpDocumentPath = "npc_guide";

    private EditBox nameBox;
    private String lastServerName = "";
    private MedievalButton peaceButton;
    private MedievalButton followButton;
    private final MedievalConfirmDialog confirmDialog = new MedievalConfirmDialog();
    /** 法师 3D 缩略图左上角的 Curios 饰品按钮（仅 Curios 加载时创建）；模型底会在 widget 之后绘制，
     *  故不加入 renderable widget 列表，由 render 手动绘制、mouseClicked 手动处理。 */
    private NpcCuriosButton curiosButton;

    public NpcScreen(NpcMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = NpcMenu.PANEL_W;
        this.imageHeight = NpcMenu.PANEL_H;
    }

    // ── 数据刷新 ──

    public void apply(NpcDataPacket packet) {
        this.entityId = packet.entityId();
        this.npcName = packet.npcName();
        this.currentHealth = packet.currentHealth();
        this.maxHealth = packet.maxHealth();
        this.currentMana = packet.currentMana();
        this.maxMana = packet.maxMana();
        this.moveSpeed = packet.moveSpeed();
        this.spellPower = packet.spellPower();
        this.workSpeed = packet.workSpeed();
        this.spellSpeed = packet.spellSpeed();
        this.armorValue = packet.armorValue();
        this.wandStack = packet.wandStack();
        this.isDefaultWand = packet.isDefaultWand();
        this.skinVariant = packet.skinVariant();
        this.hatColor = packet.hatColor();
        this.peaceMode = packet.peaceMode();
        this.followMode = packet.followMode();
        refreshToggleButtons();
        rebuildDisplayNpc();
        this.lastServerName = packet.npcName();
        if (nameBox != null && !nameBox.isFocused()
                && !nameBox.getValue().equals(packet.npcName())) {
            nameBox.setValue(packet.npcName());
        }
    }

    private void rebuildDisplayNpc() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            displayNpc = null;
            return;
        }
        displayNpc = new WandscapeNpc(Wandscape.WANDSCAPE_NPC.get(), mc.level);
        displayNpc.guiDisplayMode = true;
        displayNpc.setSkinVariant(skinVariant);
        displayNpc.setHatColor(hatColor);
        ItemStack wand = isDefaultWand ? new ItemStack(Wandscape.WAND.get()) : wandStack;
        if (!wand.isEmpty()) {
            displayNpc.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, wand);
        }
    }

    // ── 初始化 ──

    @Override
    protected void init() {
        super.init();
        closeBtnX = leftPos + imageWidth - CLOSE_W - 6;
        closeBtnY = topPos + (HEADER_H - CLOSE_H) / 2;
        helpButton = new HelpButton(closeBtnX - 14 - 4, topPos + (HEADER_H - 14) / 2, 14, 14,
                this::openHelpDocument);
        addRenderableWidget(helpButton);

        contentTop = topPos + NpcMenu.EQUIP_Y;
        rightCol = leftPos + 128;
        modelX = leftPos + 42;
        modelY = topPos + NpcMenu.EQUIP_Y + 4;
        modelW = 72;
        modelH = 86;

        // Curios：模型框左上角的饰品按钮（与玩家背包 3D 缩略图左上角图标同款），点击打开法师饰品栏
        if (CuriosCompat.isLoaded()) {
            curiosButton = new NpcCuriosButton(modelX + 6, modelY + 6, () -> {
                if (entityId >= 0) {
                    PacketDistributor.sendToServer(new NpcOpenCuriosPacket(entityId));
                }
            });
        }

        // 名字框（header 内）
        int boxH = font.lineHeight + 2;
        nameBox = new EditBox(font, leftPos + 10, topPos + (HEADER_H - boxH) / 2,
                Math.max(60, closeBtnX - 60 - 40), boxH, Component.literal("Name")) {
            @Override
            public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
                drawInsetField(g, getX() - 1, getY() - 2, getWidth() + 2, getHeight() + 4);
                super.renderWidget(g, mouseX, mouseY, partialTick);
            }
        };
        nameBox.setValue(npcName != null ? npcName : "");
        nameBox.setMaxLength(NpcRenamePacket.MAX_NAME_LENGTH);
        nameBox.setBordered(false);
        nameBox.setTextColor(MedievalColors.TEXT_WARM_WHITE);
        nameBox.setTextColorUneditable(MedievalColors.TEXT_MUTED);
        nameBox.setCanLoseFocus(true);
        nameBox.setResponder(this::onNameChanged);
        addRenderableWidget(nameBox);

        // 底部按钮行（装备槽区下方、玩家背包上方）：和平 / 跟随 / 策略 / 解雇 / 关闭
        int btnY = topPos + 124;
        int bx = leftPos + 8;
        peaceButton = new MedievalButton(bx, btnY, 68, 16,
                peaceLabel(), () -> {
            peaceMode = !peaceMode;
            refreshToggleButtons();
            PacketDistributor.sendToServer(new NpcTogglePacket(entityId, NpcTogglePacket.FLAG_PEACE, peaceMode));
        });
        followButton = new MedievalButton(bx + 70, btnY, 68, 16,
                followLabel(), () -> {
            followMode = !followMode;
            refreshToggleButtons();
            PacketDistributor.sendToServer(new NpcTogglePacket(entityId, NpcTogglePacket.FLAG_FOLLOW, followMode));
        });
        addRenderableWidget(peaceButton);
        addRenderableWidget(followButton);
        addRenderableWidget(new MedievalButton(bx + 140, btnY, 48, 16,
                I18n.name("gui.wandscape.npc.strategy", "Strategy"),
                () -> PacketDistributor.sendToServer(new NpcOpenStrategyPacket(entityId))));
        addRenderableWidget(new MedievalButton(bx + 190, btnY, 46, 16,
                I18n.name("gui.wandscape.npc.dismiss", "Dismiss"),
                this::onDismiss));
        addRenderableWidget(new MedievalButton(bx + 238, btnY, 42, 16,
                I18n.name("gui.wandscape.common.close", "Close"),
                this::onClose));
    }

    private Component peaceLabel() {
        return I18n.name(peaceMode ? "gui.wandscape.npc.peaceOff" : "gui.wandscape.npc.peace",
                peaceMode ? "Cancel Peace" : "Peace");
    }

    private Component followLabel() {
        return I18n.name(followMode ? "gui.wandscape.npc.followOff" : "gui.wandscape.npc.follow",
                followMode ? "Cancel Follow" : "Follow");
    }

    private void refreshToggleButtons() {
        if (peaceButton != null) peaceButton.setMessage(peaceLabel());
        if (followButton != null) followButton.setMessage(followLabel());
    }

    private void onNameChanged(String newName) {
        String trimmed = newName.trim();
        if (trimmed.isEmpty() || trimmed.equals(lastServerName)) return;
        lastServerName = trimmed;
        this.npcName = trimmed;
        PacketDistributor.sendToServer(new NpcRenamePacket(entityId, trimmed));
    }

    private void onDismiss() {
        if (entityId < 0) return;
        String name = (npcName != null && !npcName.isEmpty()) ? npcName : "…";
        confirmDialog.open(
                I18n.name("gui.wandscape.npc.dismiss_title", "解雇法师"),
                I18n.name("gui.wandscape.npc.dismiss_confirm", "确定解雇法师 %s？其装备将掉落。", name),
                () -> {
                    PacketDistributor.sendToServer(new NpcDismissPacket(entityId));
                    // 标准容器关闭（closeContainer + setScreen(null)），与关闭按钮一致；
                    // 服务端收到 close 包后立即同步关闭容器，避免面板残留/容器状态不一致
                    onClose();
                });
    }

    public void openHelpDocument() {
        if (helpDocumentPath != null && minecraft != null) {
            String content = com.wsteam.wandscape.foundation.ui.markdown.navigation.DocumentLoader
                    .loadMarkdown(helpDocumentPath);
            minecraft.setScreen(new com.wsteam.wandscape.foundation.ui.guide.GuideScreen(
                    this, content, helpDocumentPath));
        }
    }

    // ── 渲染 ──

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderModel(g, mouseX, mouseY);
        renderAttributes(g);
        // Curios 饰品按钮绘制在模型之上（widget 列表渲染早于模型底，会被盖住，需手动后置）
        if (curiosButton != null && CuriosCompat.isLoaded()) {
            curiosButton.render(g, mouseX, mouseY, partialTick);
        }
        // 与仓库一致：原版 render 不画 tooltip，需显式调用（悬停物品显示介绍）
        if (!confirmDialog.isOpen()) {
            renderTooltip(g, mouseX, mouseY);
        }
        // Curios 按钮悬停提示（置于物品 tooltip 之后保证浮在最上）
        if (curiosButton != null && CuriosCompat.isLoaded() && curiosButton.isHovered()) {
            g.renderTooltip(font, I18n.name("gui.wandscape.curios.open", "Trinkets"),
                    mouseX, mouseY);
        }
        // 确认框置于最顶层
        if (confirmDialog.isOpen()) {
            confirmDialog.render(g, width, height, mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fillGradient(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight,
                GLASS_TOP, GLASS_BOTTOM);
        drawGlowBorder(g, leftPos, topPos, imageWidth, imageHeight, MedievalColors.BORDER_GOLD);
        renderHeader(g, mouseX, mouseY);
        renderInventoryBackground(g);
        // 分隔线（装备区与背包之间）
        g.fill(leftPos + 8, topPos + 143, leftPos + imageWidth - 8, topPos + 144,
                MedievalColors.BORDER_GOLD_DARK);
    }

    private void renderHeader(GuiGraphics g, int mouseX, int mouseY) {
        int hx = leftPos + 1;
        int hy = topPos + 1;
        g.fillGradient(hx, hy, hx + imageWidth - 2, hy + HEADER_H, 0xFF502870, 0xFF1A0830);
        g.fill(hx, hy + HEADER_H, hx + imageWidth - 2, hy + HEADER_H + 1, MedievalColors.BORDER_GOLD);
        g.fillGradient(hx, hy, hx + 3, hy + HEADER_H, 0xFFD4A840, 0xFF6A4020);
        SkinRender.drawCloseButton(g, closeBtnX, closeBtnY, CLOSE_W, CLOSE_H,
                isInRect(mouseX, mouseY, closeBtnX, closeBtnY, CLOSE_W, CLOSE_H) ? 1 : 0);
    }

    /** 玩家背包区底（原版 inventory.png 下区，3×9+快捷栏）。 */
    private void renderInventoryBackground(GuiGraphics g) {
        int dstY = topPos + NpcMenu.PLAYER_INV_Y;
        g.blit(INVENTORY_TEXTURE, leftPos + 8, dstY, 8, 84, 9 * 18, 76);
    }
    private void renderModel(GuiGraphics g, int mouseX, int mouseY) {
        g.fill(modelX, modelY, modelX + modelW, modelY + modelH, MedievalColors.PARCHMENT_DEEPEST);
        g.fill(modelX, modelY, modelX + modelW, modelY + 1, MedievalColors.BORDER_GOLD);
        g.fill(modelX, modelY + modelH - 1, modelX + modelW, modelY + modelH, MedievalColors.BORDER_GOLD);
        g.fill(modelX, modelY, modelX + 1, modelY + modelH, MedievalColors.BORDER_GOLD);
        g.fill(modelX + modelW - 1, modelY, modelX + modelW, modelY + modelH, MedievalColors.BORDER_GOLD);
        if (displayNpc != null) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    g, modelX + 4, modelY + 4, modelX + modelW - 4, modelY + modelH - 4,
                    28, 0.0625f, mouseX, mouseY, displayNpc);
        }
    }

    private void renderAttributes(GuiGraphics g) {
        int y = contentTop + 6;
        g.drawString(font, I18n.name("gui.wandscape.npc.attributes", "Attributes"),
                rightCol, contentTop, MedievalColors.ACCENT_GOLD);
        y += 2;
        int labelW = 40;
        int barWidth = 116;

        drawStatBar(g, rightCol + labelW, y, barWidth, 10,
                (float) currentHealth / maxHealth,
                currentHealth + "/" + maxHealth, MedievalColors.DANGER_RED);
        g.drawString(font, I18n.name("gui.wandscape.npc.health", "Health").getString() + ":",
                rightCol, y, MedievalColors.TEXT_WARM_WHITE);
        y += 12;

        drawStatBar(g, rightCol + labelW, y, barWidth, 10,
                maxMana > 0 ? (float) currentMana / maxMana : 0f,
                currentMana + "/" + maxMana, MedievalColors.MANA_BLUE);
        g.drawString(font, I18n.name("gui.wandscape.npc.mana", "Mana").getString() + ":",
                rightCol, y, MedievalColors.TEXT_WARM_WHITE);
        y += 12;

        for (var entry : List.of(
                new Object[]{"gui.wandscape.npc.moveSpeed", "Move", String.format("%.2f", moveSpeed)},
                new Object[]{"gui.wandscape.npc.spell", "Spell", String.format("%.1f", spellPower)},
                new Object[]{"gui.wandscape.npc.work", "Work", String.format("%.1f", workSpeed)},
                new Object[]{"gui.wandscape.npc.spellSpeed", "Cast", String.format("%.1f", spellSpeed)},
                new Object[]{"gui.wandscape.npc.armor", "Armor", String.format("%.1f", armorValue)})) {
            g.drawString(font, I18n.name((String) entry[0], (String) entry[1]).getString() + ":",
                    rightCol, y, MedievalColors.TEXT_WARM_WHITE);
            g.drawString(font, (String) entry[2], rightCol + labelW, y, MedievalColors.TEXT_MUTED);
            y += 10;
        }
    }

    @Override
    protected void renderSlot(GuiGraphics g, Slot slot) {
        if (slot instanceof NpcMenu.NpcArmorSlot || slot instanceof NpcMenu.WandSlot) {
            VanillaPlayerInventory.blitSlotBackground(g, slot.x, slot.y);
        }
        // 空法杖槽显示默认法杖图标（默认法杖不可卸，暗示槽位用途）
        if (slot instanceof NpcMenu.WandSlot && slot.getItem().isEmpty()) {
            g.renderItem(new ItemStack(Wandscape.WAND.get()), slot.x, slot.y,
                    slot.x + slot.y * imageWidth);
            return;
        }
        super.renderSlot(g, slot);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // 面板装饰由 renderBg 绘制；不画 vanilla 标签。
    }

    private void drawStatBar(GuiGraphics g, int x, int y, int barWidth, int barHeight,
                             float ratio, String label, int fillColor) {
        g.fill(x, y, x + barWidth, y + barHeight, MedievalColors.PROGRESS_BG);
        int fillW = (int) (barWidth * Math.clamp(ratio, 0f, 1f));
        if (fillW > 0) {
            g.fill(x, y, x + fillW, y + barHeight, fillColor);
        }
        g.drawCenteredString(font, label, x + barWidth / 2, y + (barHeight - 9) / 2,
                MedievalColors.TEXT_WARM_WHITE);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Confirm dialog open: it consumes all clicks, blocking the screen behind.
        if (confirmDialog.isOpen()) {
            return confirmDialog.mouseClicked(mouseX, mouseY, button);
        }
        // Curios 饰品按钮（点击发 NpcOpenCuriosPacket 打开法师饰品栏）
        if (curiosButton != null && CuriosCompat.isLoaded()
                && curiosButton.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0 && isInRect(mouseX, mouseY, closeBtnX, closeBtnY, CLOSE_W, CLOSE_H)) {
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Confirm dialog open: swallow everything (Esc cancel / Enter confirm handled inside).
        if (confirmDialog.isOpen()) {
            return confirmDialog.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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

    private static void drawInsetField(GuiGraphics g, int x, int y, int w, int h) {
        g.fillGradient(x, y, x + w, y + h, 0x44000000, 0x33000000);
        g.fill(x, y, x + w, y + 1, 0x55000000);
        g.fill(x, y, x + 1, y + h, 0x55000000);
        g.fill(x, y + h - 1, x + w, y + h, 0x22FFFFFF);
        g.fill(x + w - 1, y, x + w, y + h, 0x22FFFFFF);
    }

    private static boolean isInRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}