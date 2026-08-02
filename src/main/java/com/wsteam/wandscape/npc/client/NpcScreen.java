package com.wsteam.wandscape.npc.client;

import com.wsteam.wandscape.npc.network.NpcDataPacket;
import com.wsteam.wandscape.npc.network.NpcEquipPacket;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.wand.item.WandItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
/**
 * NPC info & equipment screen.
 *
 * <p>Layout:
 * <ul>
 *   <li>Left — equipment slot (wand)</li>
 *   <li>Right — attributes (HP, mana, regen, spell power)</li>
 *   <li>Bottom — player inventory grid (click wand items to equip)</li>
 * </ul>
 */
public class NpcScreen extends MedievalScreen {

    private static final int PW = 280;
    private static final int PH = 248;
    private static final int SLOT_SIZE = 18;

    // NPC data
    private final int entityId;
    private String npcName;
    private int currentHealth, maxHealth;
    private int currentMana, maxMana;
    private int manaRegen, spellPower;
    private int range;
    private float manaCostMultiplier;
    private ItemStack wandStack;
    private boolean isDefaultWand;

    // Layout positions (computed in render, used for click detection)
    private int wandSlotX, wandSlotY;

    public NpcScreen(NpcDataPacket packet) {
        super(Component.literal("NPC Info"), PW, PH);
        setTitleBar("Mage Info");
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
        this.manaRegen = packet.manaRegen();
        this.spellPower = packet.spellPower();
        this.range = packet.range();
        this.manaCostMultiplier = packet.manaCostMultiplier();
        this.wandStack = packet.wandStack();
        this.isDefaultWand = packet.isDefaultWand();
        setTitleBar(npcName);
    }

    @Override
    protected void init() {
        super.init();
        // Close button
        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54, topPos + PH - 22, 46, 16,
                Component.literal("Close"), () -> Minecraft.getInstance().setScreen(null)));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        var font = Minecraft.getInstance().font;
        int leftCol = leftPos + 12;
        int rightCol = leftPos + 72;
        int contentTop = topPos + headerHeight + 6;

        // ── Equipment section (left) ──
        g.drawString(font, "Equipment", leftCol, contentTop, MedievalColors.ACCENT_GOLD);
        int sepY = contentTop + 10;
        g.fill(leftCol, sepY, leftCol + 62, sepY + 1, MedievalColors.BORDER_GOLD_DARK);

        int slotBgSize = 36;
        int slotBgX = leftCol + 5;
        int slotBgY = sepY + 4;
        // Dark background
        g.fill(slotBgX, slotBgY, slotBgX + slotBgSize, slotBgY + slotBgSize,
                MedievalColors.PARCHMENT_DEEPEST);
        // Gold border
        g.fill(slotBgX, slotBgY, slotBgX + slotBgSize, slotBgY + 1, MedievalColors.BORDER_GOLD);
        g.fill(slotBgX, slotBgY + slotBgSize - 1, slotBgX + slotBgSize, slotBgY + slotBgSize, MedievalColors.BORDER_GOLD);
        g.fill(slotBgX, slotBgY, slotBgX + 1, slotBgY + slotBgSize, MedievalColors.BORDER_GOLD);
        g.fill(slotBgX + slotBgSize - 1, slotBgY, slotBgX + slotBgSize, slotBgY + slotBgSize, MedievalColors.BORDER_GOLD);

        // "Wand" label below
        g.drawCenteredString(font, "Wand",
                slotBgX + slotBgSize / 2, slotBgY + slotBgSize + 2, MedievalColors.TEXT_MUTED);

        // Store bounds for click detection
        this.wandSlotX = slotBgX;
        this.wandSlotY = slotBgY;

        // Render wand item (only if non-default)
        int itemX = slotBgX + (slotBgSize - SLOT_SIZE) / 2 + 1;
        int itemY = slotBgY + (slotBgSize - SLOT_SIZE) / 2 + 1;
        if (!isDefaultWand && !wandStack.isEmpty()) {
            g.renderItem(wandStack, itemX, itemY);
            g.renderItemDecorations(font, wandStack, itemX, itemY);
        }

        // ── Attributes section (right) ──
        g.drawString(font, "Attributes", rightCol, contentTop, MedievalColors.ACCENT_GOLD);
        g.fill(rightCol, sepY, rightCol + 175, sepY + 1, MedievalColors.BORDER_GOLD_DARK);

        int attrY = sepY + 4;
        int labelW = 42;
        int barWidth = 135;

        // Health
        g.drawString(font, "Health:", rightCol, attrY, MedievalColors.TEXT_WARM_WHITE);
        drawStatBar(g, rightCol + labelW, attrY, barWidth, 10,
                (float) currentHealth / maxHealth,
                currentHealth + "/" + maxHealth,
                MedievalColors.DANGER_RED);
        attrY += 11;

        // Mana
        g.drawString(font, "Mana:", rightCol, attrY, MedievalColors.TEXT_WARM_WHITE);
        drawStatBar(g, rightCol + labelW, attrY, barWidth, 10,
                (float) currentMana / maxMana,
                currentMana + "/" + maxMana,
                MedievalColors.INFO_BLUE);
        attrY += 11;

        // Range
        g.drawString(font, "Range:", rightCol, attrY, MedievalColors.TEXT_WARM_WHITE);
        g.drawString(font, range + " blocks", rightCol + labelW, attrY, MedievalColors.TEXT_MUTED);
        attrY += 10;

        // Mana Cost
        g.drawString(font, "Cost:", rightCol, attrY, MedievalColors.TEXT_WARM_WHITE);
        g.drawString(font, String.format("%.1fx", manaCostMultiplier), rightCol + labelW, attrY, MedievalColors.TEXT_MUTED);
        attrY += 10;

        // Regen
        g.drawString(font, "Regen:", rightCol, attrY, MedievalColors.TEXT_WARM_WHITE);
        g.drawString(font, manaRegen + "/tick", rightCol + labelW, attrY, MedievalColors.TEXT_MUTED);
        attrY += 10;

        // Spell Power
        g.drawString(font, "Spell:", rightCol, attrY, MedievalColors.TEXT_WARM_WHITE);
        g.drawString(font, String.valueOf(spellPower), rightCol + labelW, attrY, MedievalColors.TEXT_MUTED);

        // ── Divider ──
        int divY = contentTop + 100;
        g.fill(leftCol, divY, leftPos + PW - 12, divY + 1, MedievalColors.BORDER_GOLD_DARK);

        // ── Inventory section (bottom) ──
        int invLabelY = divY + 6;
        g.drawString(font, "Inventory", leftCol, invLabelY, MedievalColors.ACCENT_GOLD);

        int gridX = leftCol + 2;
        int gridY = invLabelY + 13;
        int slotPitch = SLOT_SIZE + 2;
        int cols = 9;

        Player player = Minecraft.getInstance().player;
        if (player != null) {
            var items = player.getInventory().items;
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < cols; col++) {
                    int sx = gridX + col * slotPitch;
                    int sy = gridY + row * slotPitch;
                    int slotIndex;
                    if (row == 3) { // hotbar
                        slotIndex = col;
                    } else { // main inventory slots 9-35
                        slotIndex = 9 + row * 9 + col;
                    }

                    g.fill(sx, sy, sx + SLOT_SIZE + 2, sy + SLOT_SIZE + 2,
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
                && mouseX >= wandSlotX && mouseX < wandSlotX + slotBgSize
                && mouseY >= wandSlotY && mouseY < wandSlotY + slotBgSize) {
            g.renderTooltip(font, wandStack, mouseX, mouseY);
        }

        // ── Tooltip: inventory slots ──
        if (player != null) {
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < cols; col++) {
                    int sx = gridX + col * slotPitch;
                    int sy = gridY + row * slotPitch;
                    int slotIndex = (row == 3) ? col : 9 + row * 9 + col;
                    ItemStack stack = player.getInventory().items.get(slotIndex);
                    if (!stack.isEmpty()
                            && mouseX >= sx && mouseX < sx + SLOT_SIZE + 2
                            && mouseY >= sy && mouseY < sy + SLOT_SIZE + 2) {
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

        int slotBgSize = 36;

        // Click on wand slot → unequip
        if (!isDefaultWand && !wandStack.isEmpty()
                && mouseX >= wandSlotX && mouseX < wandSlotX + slotBgSize
                && mouseY >= wandSlotY && mouseY < wandSlotY + slotBgSize) {
            PacketDistributor.sendToServer(new NpcEquipPacket(entityId,
                    NpcEquipPacket.ACTION_UNEQUIP, 0));
            return true;
        }

        // Click on inventory slot → equip (only if it's a wand item)
        int gridX = leftPos + 14;
        int gridY = topPos + headerHeight + 125; // matches render() gridY
        int slotPitch = 20;
        int cols = 9;

        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < cols; col++) {
                int sx = gridX + col * slotPitch;
                int sy = gridY + row * slotPitch;
                int slotIndex = (row == 3) ? col : 9 + row * 9 + col;

                if (mouseX >= sx && mouseX < sx + SLOT_SIZE + 2
                        && mouseY >= sy && mouseY < sy + SLOT_SIZE + 2) {
                    Player player = Minecraft.getInstance().player;
                    if (player == null) return true;
                    ItemStack stack = player.getInventory().items.get(slotIndex);
                    if (!stack.isEmpty() && stack.getItem() instanceof WandItem) {
                        PacketDistributor.sendToServer(new NpcEquipPacket(entityId,
                                NpcEquipPacket.ACTION_EQUIP, slotIndex));
                        return true;
                    }
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
