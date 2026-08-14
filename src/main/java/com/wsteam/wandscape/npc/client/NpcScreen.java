package com.wsteam.wandscape.npc.client;

import java.util.List;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.npc.network.NpcDataPacket;
import com.wsteam.wandscape.npc.network.NpcEquipPacket;
import com.wsteam.wandscape.npc.network.NpcRenamePacket;
import com.wsteam.wandscape.npc.network.NpcTogglePacket;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.wand.item.WandItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
/**
 * NPC info & equipment screen.
 *
 * <p>Layout:
 * <ul>
 *   <li>Left — 3D NPC display (vanilla inventory style) + 4 armor slots + wand slot</li>
 *   <li>Right — attributes (HP, spell power, work speed, spell speed, armor)</li>
 *   <li>Bottom — player inventory grid (click wand/armor items to equip)</li>
 * </ul>
 */
public class NpcScreen extends MedievalScreen {

    private static final int PW = 300;
    private static final int PH = 230;
    private static final int SLOT_SIZE = 18;
    private static final int SLOT_PITCH = 18;

    // NPC data
    private final int entityId;
    private String npcName;
    private int currentHealth, maxHealth;
    private int currentMana, maxMana;
    private float moveSpeed, spellPower, workSpeed, spellSpeed, armorValue;
    private ItemStack wandStack;
    private boolean isDefaultWand;
    private boolean peaceMode;
    private boolean followMode;
    private String strategyPreset = "BALANCED";
    private List<String> knownSpells = List.of();
    private List<String> spellCategories = List.of();
    private List<String> priority = List.of();
    /** 盔甲格（顺序：头盔/胸甲/护腿/靴子）。 */
    private List<ItemStack> armorStacks = List.of(ItemStack.EMPTY, ItemStack.EMPTY,
            ItemStack.EMPTY, ItemStack.EMPTY);
    private int skinVariant;
    private int hatColor;
    /** 客户端 3D 展示克隆（不入世界，仅用于面板渲染）。 */
    private WandscapeNpc displayNpc;

    // ── 装备操作即时提示 ──
    private Component statusTip;
    private int statusTipColor = MedievalColors.ACCENT_GOLD;
    private long statusTipExpireTick = 0;

    public void setStatusTip(Component tip, int color) {
        this.statusTip = tip;
        this.statusTipColor = color;
        this.statusTipExpireTick = System.currentTimeMillis() + 3000L;
    }

    // Layout positions (computed in render, used for click detection)
    private int wandSlotX, wandSlotY;
    private final int[] armorSlotX = new int[4];
    private final int[] armorSlotY = new int[4];
    private int gridX, gridY;

    // ── Editable name (top-left title bar) ──
    private EditBox nameBox;
    /** 服务端已确认的名字（与 nameBox 一致时跳过重复发送）。 */
    private String lastServerName = "";

    // ── 底部行为切换按钮（和平 / 跟随） ──
    private MedievalButton peaceButton;
    private MedievalButton followButton;

    public NpcScreen(NpcDataPacket packet) {
        super(Component.literal("NPC Info"), PW, PH);
        this.showCloseButton = true;
        this.showHelpButton = true;
        this.helpDocumentPath = "npc_guide";
        this.entityId = packet.entityId();
        apply(packet);
    }

    /** Apply/refresh data from a server packet (preserves entityId). */
    public void apply(NpcDataPacket packet) {
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
        this.armorStacks = packet.armorStacks();
        this.skinVariant = packet.skinVariant();
        this.hatColor = packet.hatColor();
        this.peaceMode = packet.peaceMode();
        this.followMode = packet.followMode();
        refreshToggleButtons();
        rebuildDisplayNpc();
        // 名字：仅当输入框未聚焦时才回写（避免打断正在编辑），且值相同则不触发重发
        this.lastServerName = packet.npcName();
        if (nameBox != null && !nameBox.isFocused()
                && !nameBox.getValue().equals(packet.npcName())) {
            nameBox.setValue(packet.npcName());
        }
    }

    /**
     * 从包数据构建一个不入世界的展示克隆，供左侧 3D 模型渲染。
     * 携带皮肤变体/帽子颜色/当前法杖，标记 guiDisplayMode 跳过名牌与气泡。
     */
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
            displayNpc.setItemInHand(InteractionHand.MAIN_HAND, wand);
        }
    }

    @Override
    protected void init() {
        super.init();
        // 左上角名字框（可编辑，写名字自动保存；结束于帮助按钮之前）
        int boxH = font.lineHeight + 2;
        int bx = leftPos + titleXOffset + 1;
        int by = topPos + (headerHeight - boxH) / 2;
        int boxRight = closeBtnX - 20; // 14(help) + 4(gap) + 2
        nameBox = new EditBox(font, bx, by, Math.max(40, boxRight - bx), boxH,
                Component.literal("Name")) {
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

        // 和平 / 跟随 切换按钮（策略按钮左侧，同一行排布）：
        // 点击即乐观翻转本地状态刷新文字，再发包给服务端确认（服务端回发 NpcDataPacket 同步）。
        peaceButton = new MedievalButton(
                leftPos + PW - 272, topPos + PH - 22, 80, 16,
                peaceLabel(), () -> {
            peaceMode = !peaceMode;
            refreshToggleButtons();
            PacketDistributor.sendToServer(new NpcTogglePacket(entityId, NpcTogglePacket.FLAG_PEACE, peaceMode));
        });
        followButton = new MedievalButton(
                leftPos + PW - 188, topPos + PH - 22, 80, 16,
                followLabel(), () -> {
            followMode = !followMode;
            refreshToggleButtons();
            PacketDistributor.sendToServer(new NpcTogglePacket(entityId, NpcTogglePacket.FLAG_FOLLOW, followMode));
        });
        addRenderableWidget(peaceButton);
        addRenderableWidget(followButton);
        // 策略按钮（打开施法策略屏）
        addRenderableWidget(new MedievalButton(
                leftPos + PW - 104, topPos + PH - 22, 46, 16,
                I18n.name("gui.wandscape.npc.strategy", "Strategy"),
                () -> Minecraft.getInstance().setScreen(
                        new NpcStrategyScreen(entityId, strategyPreset, knownSpells, spellCategories, priority))));
        // Close button
        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54, topPos + PH - 22, 46, 16,
                I18n.name("gui.wandscape.common.close", "Close"), () -> Minecraft.getInstance().setScreen(null)));
    }

    /** 和平按钮文字：开启时显示「取消和平」，未开启显示「和平」。 */
    private Component peaceLabel() {
        return I18n.name(peaceMode ? "gui.wandscape.npc.peaceOff" : "gui.wandscape.npc.peace",
                peaceMode ? "Cancel Peace" : "Peace");
    }

    /** 跟随按钮文字：跟随中显示「取消跟随」，否则显示「跟随」。 */
    private Component followLabel() {
        return I18n.name(followMode ? "gui.wandscape.npc.followOff" : "gui.wandscape.npc.follow",
                followMode ? "Cancel Follow" : "Follow");
    }

    /** 依据当前模式刷新两个切换按钮的文字（apply 时与服务端同步）。 */
    private void refreshToggleButtons() {
        if (peaceButton != null) peaceButton.setMessage(peaceLabel());
        if (followButton != null) followButton.setMessage(followLabel());
    }

    /** 名字框每次变更：非空且与服务端不同则自动发送改名包（写好了自动保存）。 */
    private void onNameChanged(String newName) {
        String trimmed = newName.trim();
        if (trimmed.isEmpty() || trimmed.equals(lastServerName)) return;
        lastServerName = trimmed;
        this.npcName = trimmed;
        PacketDistributor.sendToServer(new NpcRenamePacket(entityId, trimmed));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        var font = Minecraft.getInstance().font;
        int leftCol = leftPos + 12;
        int rightCol = leftPos + 118;
        int contentTop = topPos + headerHeight + 4;

        // ── Equipment slots (left): 4 armor + 1 wand, all same size, gold border ──
        int slotX = leftCol;
        ResourceLocation[] armorIcons = {
                InventoryMenu.EMPTY_ARMOR_SLOT_HELMET,
                InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
                InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS,
                InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS
        };
        for (int i = 0; i < 5; i++) {
            int sy = contentTop + 4 + i * SLOT_PITCH;
            // 槽背景 + 金色边框
            g.fill(slotX, sy, slotX + SLOT_SIZE, sy + SLOT_SIZE, MedievalColors.PARCHMENT_DEEPEST);
            g.fill(slotX, sy, slotX + SLOT_SIZE, sy + 1, MedievalColors.BORDER_GOLD);
            g.fill(slotX, sy + SLOT_SIZE - 1, slotX + SLOT_SIZE, sy + SLOT_SIZE, MedievalColors.BORDER_GOLD);
            g.fill(slotX, sy, slotX + 1, sy + SLOT_SIZE, MedievalColors.BORDER_GOLD);
            g.fill(slotX + SLOT_SIZE - 1, sy, slotX + SLOT_SIZE, sy + SLOT_SIZE, MedievalColors.BORDER_GOLD);

            if (i < 4) { // armor slots
                armorSlotX[i] = slotX;
                armorSlotY[i] = sy;
                ItemStack stack = i < armorStacks.size() ? armorStacks.get(i) : ItemStack.EMPTY;
                if (!stack.isEmpty()) {
                    g.renderItem(stack, slotX + 1, sy + 1);
                    g.renderItemDecorations(font, stack, slotX + 1, sy + 1);
                } else {
                    // 空槽占位：原版 E 对应部位的盔甲图标
                    TextureAtlasSprite sprite = Minecraft.getInstance()
                            .getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(armorIcons[i]);
                    g.blit(slotX + 1, sy + 1, 0, 16, 16, sprite);
                }
            } else { // wand slot
                wandSlotX = slotX;
                wandSlotY = sy;
                if (!isDefaultWand && !wandStack.isEmpty()) {
                    g.renderItem(wandStack, slotX + 1, sy + 1);
                    g.renderItemDecorations(font, wandStack, slotX + 1, sy + 1);
                } else {
                    // 空槽占位：法杖图标
                    g.renderItem(new ItemStack(Wandscape.WAND.get()), slotX + 1, sy + 1);
                }
            }
        }

        // ── 3D NPC model（原版装备栏风格，随鼠标转向），槽列与属性区之间留空隙 ──
        int modelX = slotX + SLOT_SIZE + 8;
        int modelY = contentTop + 10;
        int modelW = 66;
        int modelH = 80;
        g.fill(modelX, modelY, modelX + modelW, modelY + modelH, MedievalColors.PARCHMENT_DEEPEST);
        g.fill(modelX, modelY, modelX + modelW, modelY + 1, MedievalColors.BORDER_GOLD);
        g.fill(modelX, modelY + modelH - 1, modelX + modelW, modelY + modelH, MedievalColors.BORDER_GOLD);
        g.fill(modelX, modelY, modelX + 1, modelY + modelH, MedievalColors.BORDER_GOLD);
        g.fill(modelX + modelW - 1, modelY, modelX + modelW, modelY + modelH, MedievalColors.BORDER_GOLD);
        if (displayNpc != null) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    g, modelX + 4, modelY + 4, modelX + modelW - 4, modelY + modelH - 4,
                    30, 0.0625f, mouseX, mouseY, displayNpc);
        }

        // ── Attributes section (right) ──
        int sepY = contentTop + 10;
        g.drawString(font, I18n.name("gui.wandscape.npc.attributes", "Attributes"),
                rightCol, contentTop, MedievalColors.ACCENT_GOLD);
        g.fill(rightCol, sepY, rightCol + 165, sepY + 1, MedievalColors.BORDER_GOLD_DARK);

        int attrY = sepY + 4;
        int labelW = 40;
        int barWidth = 125;

        // Health
        g.drawString(font, I18n.name("gui.wandscape.npc.health", "Health").getString() + ":",
                rightCol, attrY, MedievalColors.TEXT_WARM_WHITE);
        drawStatBar(g, rightCol + labelW, attrY, barWidth, 10,
                (float) currentHealth / maxHealth,
                currentHealth + "/" + maxHealth,
                MedievalColors.DANGER_RED);
        attrY += 11;

        // Mana
        g.drawString(font, I18n.name("gui.wandscape.npc.mana", "Mana").getString() + ":",
                rightCol, attrY, MedievalColors.TEXT_WARM_WHITE);
        drawStatBar(g, rightCol + labelW, attrY, barWidth, 10,
                maxMana > 0 ? (float) currentMana / maxMana : 0f,
                currentMana + "/" + maxMana,
                MedievalColors.MANA_BLUE);
        attrY += 11;

        // Move Speed
        g.drawString(font, I18n.name("gui.wandscape.npc.moveSpeed", "Move").getString() + ":",
                rightCol, attrY, MedievalColors.TEXT_WARM_WHITE);
        g.drawString(font, String.format("%.2f", moveSpeed), rightCol + labelW, attrY, MedievalColors.TEXT_MUTED);
        attrY += 10;

        // Spell Power
        g.drawString(font, I18n.name("gui.wandscape.npc.spell", "Spell").getString() + ":",
                rightCol, attrY, MedievalColors.TEXT_WARM_WHITE);
        g.drawString(font, String.format("%.1f", spellPower), rightCol + labelW, attrY, MedievalColors.TEXT_MUTED);
        attrY += 10;

        // Work Speed
        g.drawString(font, I18n.name("gui.wandscape.npc.work", "Work").getString() + ":",
                rightCol, attrY, MedievalColors.TEXT_WARM_WHITE);
        g.drawString(font, String.format("%.1f", workSpeed), rightCol + labelW, attrY, MedievalColors.TEXT_MUTED);
        attrY += 10;

        // Spell Speed
        g.drawString(font, I18n.name("gui.wandscape.npc.spellSpeed", "Cast").getString() + ":",
                rightCol, attrY, MedievalColors.TEXT_WARM_WHITE);
        g.drawString(font, String.format("%.1f", spellSpeed), rightCol + labelW, attrY, MedievalColors.TEXT_MUTED);
        attrY += 10;

        // Armor
        g.drawString(font, I18n.name("gui.wandscape.npc.armor", "Armor").getString() + ":",
                rightCol, attrY, MedievalColors.TEXT_WARM_WHITE);
        g.drawString(font, String.format("%.1f", armorValue), rightCol + labelW, attrY, MedievalColors.TEXT_MUTED);

        // ── Divider ──
        int divY = contentTop + 96;
        g.fill(leftCol, divY, leftPos + PW - 12, divY + 1, MedievalColors.BORDER_GOLD_DARK);

        // ── Inventory section (bottom) ──
        int invLabelY = divY + 2;
        g.drawString(font, I18n.name("gui.wandscape.npc.inventory", "Inventory"),
                leftCol, invLabelY, MedievalColors.ACCENT_GOLD);

        if (statusTip != null) {
            if (System.currentTimeMillis() > statusTipExpireTick) {
                statusTip = null;
            } else {
                g.drawString(font, statusTip, leftCol + 45, invLabelY, statusTipColor);
            }
        }

        int gridX = leftCol + 2;
        int gridY = divY + 12;
        int cols = 9;
        this.gridX = gridX;
        this.gridY = gridY;

        Player player = Minecraft.getInstance().player;
        if (player != null) {
            var items = player.getInventory().items;
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < cols; col++) {
                    int sx = gridX + col * SLOT_PITCH;
                    int sy = gridY + row * SLOT_PITCH;
                    int slotIndex;
                    if (row == 3) { // hotbar
                        slotIndex = col;
                    } else { // main inventory slots 9-35
                        slotIndex = 9 + row * 9 + col;
                    }

                    g.fill(sx, sy, sx + SLOT_SIZE, sy + SLOT_SIZE,
                            MedievalColors.PARCHMENT_DEEPEST);

                    ItemStack stack = items.get(slotIndex);
                    if (!stack.isEmpty()) {
                        g.renderItem(stack, sx + 1, sy + 1);
                        g.renderItemDecorations(font, stack, sx + 1, sy + 1);
                    }
                }
            }
        }

        // ── Tooltip: wand slot ──
        if (!isDefaultWand && !wandStack.isEmpty()
                && mouseX >= wandSlotX && mouseX < wandSlotX + SLOT_SIZE
                && mouseY >= wandSlotY && mouseY < wandSlotY + SLOT_SIZE) {
            g.renderTooltip(font, wandStack, mouseX, mouseY);
        }

        // ── Tooltip: armor slots ──
        for (int i = 0; i < 4; i++) {
            ItemStack stack = i < armorStacks.size() ? armorStacks.get(i) : ItemStack.EMPTY;
            if (!stack.isEmpty()
                    && mouseX >= armorSlotX[i] && mouseX < armorSlotX[i] + SLOT_SIZE
                    && mouseY >= armorSlotY[i] && mouseY < armorSlotY[i] + SLOT_SIZE) {
                g.renderTooltip(font, stack, mouseX, mouseY);
            }
        }

        // ── Tooltip: inventory slots ──
        if (player != null) {
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < cols; col++) {
                    int sx = gridX + col * SLOT_PITCH;
                    int sy = gridY + row * SLOT_PITCH;
                    int slotIndex = (row == 3) ? col : 9 + row * 9 + col;
                    ItemStack stack = player.getInventory().items.get(slotIndex);
                    if (!stack.isEmpty()
                            && mouseX >= sx && mouseX < sx + SLOT_SIZE
                            && mouseY >= sy && mouseY < sy + SLOT_SIZE) {
                        g.renderTooltip(font, stack, mouseX, mouseY);
                    }
                }
            }
        }
    }

    /** Draw a compact stat bar. */
    private void drawStatBar(GuiGraphics g, int x, int y, int barWidth, int barHeight,
                             float ratio, String label, int fillColor) {
        g.fill(x, y, x + barWidth, y + barHeight, MedievalColors.PROGRESS_BG);
        int fillW = (int) (barWidth * Math.clamp(ratio, 0f, 1f));
        if (fillW > 0) {
            g.fill(x, y, x + fillW, y + barHeight, fillColor);
        }
        var font = Minecraft.getInstance().font;
        g.drawCenteredString(font, label, x + barWidth / 2, y + (barHeight - 9) / 2,
                MedievalColors.TEXT_WARM_WHITE);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        // Click on an occupied armor slot → unequip to player inventory
        for (int i = 0; i < 4; i++) {
            ItemStack stack = i < armorStacks.size() ? armorStacks.get(i) : ItemStack.EMPTY;
            if (!stack.isEmpty()
                    && mouseX >= armorSlotX[i] && mouseX < armorSlotX[i] + SLOT_SIZE
                    && mouseY >= armorSlotY[i] && mouseY < armorSlotY[i] + SLOT_SIZE) {
                setStatusTip(Component.translatable("gui.wandscape.npc.tip.unequip_success", stack.getHoverName()),
                        MedievalColors.TEXT_WARM_WHITE);
                Minecraft.getInstance().getSoundManager().play(
                        SimpleSoundInstance.forUI(SoundEvents.ITEM_PICKUP, 1.0f));
                PacketDistributor.sendToServer(new NpcEquipPacket(entityId,
                        NpcEquipPacket.ACTION_UNEQUIP_ARMOR, 0, i));
                return true;
            }
        }

        // Click on wand slot → unequip
        if (mouseX >= wandSlotX && mouseX < wandSlotX + SLOT_SIZE
                && mouseY >= wandSlotY && mouseY < wandSlotY + SLOT_SIZE) {
            if (isDefaultWand || wandStack.isEmpty()) {
                setStatusTip(Component.translatable("gui.wandscape.npc.tip.cannot_unequip_default"),
                        MedievalColors.DANGER_RED);
                Minecraft.getInstance().getSoundManager().play(
                        SimpleSoundInstance.forUI(SoundEvents.DISPENSER_FAIL, 1.2f));
            } else {
                setStatusTip(Component.translatable("gui.wandscape.npc.tip.unequip_success", wandStack.getHoverName()),
                        MedievalColors.TEXT_WARM_WHITE);
                Minecraft.getInstance().getSoundManager().play(
                        SimpleSoundInstance.forUI(SoundEvents.ITEM_PICKUP, 1.0f));
                PacketDistributor.sendToServer(new NpcEquipPacket(entityId,
                        NpcEquipPacket.ACTION_UNEQUIP, 0, 0));
            }
            return true;
        }

        // Click on inventory slot → equip wand or armor
        int cols = 9;
        Player player = Minecraft.getInstance().player;

        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < cols; col++) {
                int sx = gridX + col * SLOT_PITCH;
                int sy = gridY + row * SLOT_PITCH;
                int slotIndex = (row == 3) ? col : 9 + row * 9 + col;

                if (mouseX >= sx && mouseX < sx + SLOT_SIZE
                        && mouseY >= sy && mouseY < sy + SLOT_SIZE) {
                    if (player == null) return true;
                    ItemStack stack = player.getInventory().items.get(slotIndex);
                    if (stack.isEmpty()) return true;

                    if (stack.getItem() instanceof WandItem) {
                        if (!isDefaultWand && !wandStack.isEmpty()) {
                            setStatusTip(Component.translatable("gui.wandscape.npc.tip.swap_success",
                                    wandStack.getHoverName(), stack.getHoverName()), MedievalColors.ACCENT_GOLD);
                        } else {
                            setStatusTip(Component.translatable("gui.wandscape.npc.tip.equip_success",
                                    stack.getHoverName()), MedievalColors.SUCCESS_GREEN);
                        }
                        Minecraft.getInstance().getSoundManager().play(
                                SimpleSoundInstance.forUI(SoundEvents.ENCHANTMENT_TABLE_USE, 1.2f));
                        PacketDistributor.sendToServer(new NpcEquipPacket(entityId,
                                NpcEquipPacket.ACTION_EQUIP, slotIndex, 0));
                        return true;
                    }

                    int armorIdx = armorIndexFor(stack);
                    if (armorIdx >= 0) {
                        ItemStack oldArmor = armorIdx < armorStacks.size() ? armorStacks.get(armorIdx) : ItemStack.EMPTY;
                        if (!oldArmor.isEmpty()) {
                            setStatusTip(Component.translatable("gui.wandscape.npc.tip.swap_success",
                                    oldArmor.getHoverName(), stack.getHoverName()), MedievalColors.ACCENT_GOLD);
                        } else {
                            setStatusTip(Component.translatable("gui.wandscape.npc.tip.equip_success",
                                    stack.getHoverName()), MedievalColors.SUCCESS_GREEN);
                        }
                        Minecraft.getInstance().getSoundManager().play(
                                SimpleSoundInstance.forUI(SoundEvents.ARMOR_EQUIP_GENERIC.value(), 1.0f));
                        PacketDistributor.sendToServer(new NpcEquipPacket(entityId,
                                NpcEquipPacket.ACTION_EQUIP_ARMOR, slotIndex, armorIdx));
                        return true;
                    }

                    // Clicked non-equippable item in inventory
                    setStatusTip(Component.translatable("gui.wandscape.npc.tip.not_equippable"),
                            MedievalColors.DANGER_RED);
                    Minecraft.getInstance().getSoundManager().play(
                            SimpleSoundInstance.forUI(SoundEvents.DISPENSER_FAIL, 1.2f));
                    return true;
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 背包物品 → 盔甲格索引（0..3），非盔甲返回 -1。 */
    private static int armorIndexFor(ItemStack stack) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return -1;
        var slot = player.getEquipmentSlotForItem(stack);
        for (int i = 0; i < WandscapeNpc.ARMOR_SLOT_COUNT; i++) {
            if (WandscapeNpc.ARMOR_VANILLA_SLOTS[i] == slot) return i;
        }
        return -1;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
