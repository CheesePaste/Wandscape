package com.wsteam.wandscape.npc.client;

import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.npc.NpcMenu;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.network.NpcDataPacket;
import com.wsteam.wandscape.npc.network.NpcOpenStrategyPacket;
import com.wsteam.wandscape.npc.network.NpcRenamePacket;
import com.wsteam.wandscape.npc.network.NpcTogglePacket;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.ReplayProtectedScreen;
import com.wsteam.wandscape.shared.ui.component.HelpButton;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.skin.SkinRender;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.shared.ui.vanilla.VanillaPlayerInventory;

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
    private String strategyPreset = "BALANCED";
    private List<String> knownSpells = List.of();
    private List<String> spellCategories = List.of();
    private List<String> priority = List.of();
    private Map<String, String> magicCatalog = Map.of();
    private List<ItemStack> armorStacks = List.of(ItemStack.EMPTY, ItemStack.EMPTY,
            ItemStack.EMPTY, ItemStack.EMPTY);
    private int skinVariant;
    private int hatColor;
    private WandscapeNpc displayNpc;

    // 布局（init/render 时计算）
    private int contentTop;
    private int rightCol;
    private int modelX, modelY, modelW, modelH;
    private int closeBtnX, closeBtnY;
    private HelpButton helpButton;
    private String helpDocumentPath = "npc_guide";

    private EditBox nameBox;
    private String lastServerName = "";
    private MedievalButton peaceButton;
    private MedievalButton followButton;

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
        this.strategyPreset = packet.strategyPreset();
        this.knownSpells = packet.knownSpells();
        this.spellCategories = packet.spellCategories();
        this.priority = packet.priority();
        this.magicCatalog = packet.magicCatalog();
        this.armorStacks = packet.armorStacks();
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

        // 底部按钮行（装备槽区下方、玩家背包上方）
        int btnY = topPos + 124;
        peaceButton = new MedievalButton(leftPos + 8, btnY, 78, 16,
                peaceLabel(), () -> {
            peaceMode = !peaceMode;
            refreshToggleButtons();
            PacketDistributor.sendToServer(new NpcTogglePacket(entityId, NpcTogglePacket.FLAG_PEACE, peaceMode));
        });
        followButton = new MedievalButton(leftPos + 90, btnY, 78, 16,
                followLabel(), () -> {
            followMode = !followMode;
            refreshToggleButtons();
            PacketDistributor.sendToServer(new NpcTogglePacket(entityId, NpcTogglePacket.FLAG_FOLLOW, followMode));
        });
        addRenderableWidget(peaceButton);
        addRenderableWidget(followButton);
        addRenderableWidget(new MedievalButton(leftPos + 172, btnY, 56, 16,
                I18n.name("gui.wandscape.npc.strategy", "Strategy"),
                () -> PacketDistributor.sendToServer(new NpcOpenStrategyPacket(entityId))));
        addRenderableWidget(new MedievalButton(leftPos + 232, btnY, 60, 16,
                I18n.name("gui.wandscape.common.close", "Close"),
                () -> Minecraft.getInstance().setScreen(null)));
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
        renderModel(g, mouseX, mouseY);
        renderAttributes(g);
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