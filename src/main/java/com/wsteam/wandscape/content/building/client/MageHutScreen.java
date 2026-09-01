package com.wsteam.wandscape.content.building.client;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.building.network.MageHutActionPacket;
import com.wsteam.wandscape.content.building.network.MageHutDataPacket;
import com.wsteam.wandscape.content.building.network.MageHutDataPacket.MageCandidate;
import com.wsteam.wandscape.content.building.network.OpenWarehousePacket;
import com.wsteam.wandscape.core.types.AttributeType;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.content.npc.data.MageHutAttributes;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.component.MedievalButton;
import com.wsteam.wandscape.foundation.ui.component.MedievalScreen;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Mage Hut panel — manage the single resident mage of a mage hut.
 *
 * <p>Completely custom self-drawn medieval interface matching Tavern style:
 * <ul>
 *   <li><b>Occupied State</b> — 3D wizard avatar, dossier info, interactive 7-attribute cards
 *       with progression mini-bars and breakdown tooltips, attribute training module,
 *       level promotion, and quick command buttons (Equip / Strategy / Rest).</li>
 *   <li><b>Empty State</b> — Candidate list on the left, candidate 3D preview &amp; assignment
 *       dossier on the right.</li>
 * </ul>
 */
public class MageHutScreen extends MedievalScreen {

    private static final int PW = 380;
    private static final int PH = 245;

    // ── Data snapshot ──
    private BlockPos buildingPos;
    private int colonyLevel;
    private boolean hasResident;
    private boolean alive;
    private boolean resting;
    private String mageName;
    private int mageLevel;
    private int skinVariant;
    private float[] base = new float[7];
    private float[] equip = new float[7];
    private List<MageCandidate> candidates = new ArrayList<>();

    // ── Interactive state ──
    private int selectedCandidate = 0;
    private int selectedTrain = 0;
    private int candidateScrollOffset = 0;
    private boolean candidateScrollDragging = false;
    private double candidateDragStartMouseY;
    private int candidateDragStartScrollOffset;
    private WandscapeNpc previewNpc;

    // ── Toast notifications ──
    private Component toastMessage;
    private int toastColor = MedievalColors.ACCENT_GOLD;
    private long toastExpireTick = 0;

    public MageHutScreen(MageHutDataPacket packet) {
        super(Component.literal("Mage Hut"), PW, PH);
        setTitleBar(I18n.name("gui.wandscape.mage_hut.title", "法师小屋"));
        this.showCloseButton = true;
        this.showHelpButton = true;
        this.helpDocumentPath = "mage_hut_guide";
        applyData(packet);
    }

    /** Smoothly update data from server sync packet without reopening screen. */
    public void apply(MageHutDataPacket packet) {
        applyData(packet);
        clearWidgets();
        init();
    }

    private void applyData(MageHutDataPacket packet) {
        this.buildingPos = packet.buildingPos();
        this.colonyLevel = packet.colonyLevel();
        this.hasResident = packet.hasResident();
        this.alive = packet.alive();
        this.resting = packet.resting();
        this.mageName = packet.mageName() != null ? packet.mageName() : "";
        this.mageLevel = packet.mageLevel();
        this.skinVariant = packet.skinVariant();
        this.base = packet.base() != null && packet.base().length >= 7 ? packet.base() : new float[7];
        this.equip = packet.equipBonus() != null && packet.equipBonus().length >= 7 ? packet.equipBonus() : new float[7];
        this.candidates = packet.candidates() != null ? packet.candidates() : List.of();
        setCreator(packet.creator());

        if (selectedTrain >= MageHutAttributes.ORDER.size()) selectedTrain = 0;
        if (selectedCandidate >= candidates.size()) selectedCandidate = Math.max(0, candidates.size() - 1);
        int itemH = 26 + 3;
        int maxScroll = Math.max(0, candidates.size() * itemH - (180 - 24));
        candidateScrollOffset = Math.clamp(candidateScrollOffset, 0, maxScroll);
        rebuildPreviewNpc();
    }

    public void setToast(Component msg, int color) {
        this.toastMessage = msg;
        this.toastColor = color;
        this.toastExpireTick = System.currentTimeMillis() + 3500L;
    }

    private void rebuildPreviewNpc() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            previewNpc = null;
            return;
        }

        if (hasResident) {
            if (!alive) {
                previewNpc = null;
                return;
            }
            previewNpc = new WandscapeNpc(Wandscape.WANDSCAPE_NPC.get(), mc.level);
            previewNpc.guiDisplayMode = true;
            previewNpc.setSkinVariant(skinVariant >= 0 ? skinVariant : 0);
            previewNpc.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Wandscape.WAND.get()));
        } else {
            if (candidates.isEmpty() || selectedCandidate < 0 || selectedCandidate >= candidates.size()) {
                previewNpc = null;
                return;
            }
            previewNpc = new WandscapeNpc(Wandscape.WANDSCAPE_NPC.get(), mc.level);
            previewNpc.guiDisplayMode = true;
            previewNpc.setSkinVariant(0);
            previewNpc.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Wandscape.WAND.get()));
        }
    }

    private boolean canOperate() {
        return hasResident && alive;
    }

    @Override
    protected void init() {
        super.init();
        rebuildPreviewNpc();

        int contentTop = topPos + headerHeight + 6;

        if (hasResident) {
            initOccupied(contentTop);
        } else {
            initEmpty(contentTop);
        }

        // Open the colony warehouse (element counts & stored items) — left of Close
        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54 - 44 - 4, topPos + PH - 20, 44, 16,
                I18n.name("gui.wandscape.common.open_warehouse", "Open Warehouse"), this::onOpenWarehouse));

        // Close button at bottom right
        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54, topPos + PH - 20, 44, 16,
                I18n.name("gui.wandscape.common.close", "Close"), this::onClose));
    }

    private void initOccupied(int contentTop) {
        int rx = leftPos + 214;
        int ry = contentTop;
        int rw = 154;

        // ── Card 1: Stat Training Button ──
        AttributeType selType = MageHutAttributes.ORDER.get(selectedTrain);
        boolean canTrain = canOperate() && MageHutAttributes.canTrain(selType, base[selType.ordinal()]);
        Component trainLabel = canTrain
                ? I18n.name("gui.wandscape.mage_hut.train_btn", "强化特训")
                : I18n.name("gui.wandscape.mage_hut.maxed_attr", "已达属性上限");

        MedievalButton trainBtn = new MedievalButton(
                rx + 8, ry + 60, rw - 16, 20, trainLabel,
                () -> onTrain(selType));
        trainBtn.active = canTrain;
        addRenderableWidget(trainBtn);

        // ── Card 2: Promotion & Quick Commands ──
        boolean canLvl = canOperate() && MageHutAttributes.canLevelUp(mageLevel, colonyLevel);
        Component upLabel = canLvl
                ? I18n.name("gui.wandscape.mage_hut.upgrade", "⬆ 晋升等阶 (Lv.%d)", mageLevel + 1)
                : (mageLevel >= colonyLevel
                        ? I18n.name("gui.wandscape.mage_hut.maxed_level", "等阶已达小镇上限")
                        : Component.literal("Lv." + mageLevel));

        MedievalButton upgradeBtn = new MedievalButton(
                rx + 8, ry + 118, rw - 16, 20, upLabel,
                this::onUpgrade);
        upgradeBtn.active = canLvl;
        addRenderableWidget(upgradeBtn);

        int bY = ry + 144;
        int bW = 43;
        int bH = 18;
        int bGap = 4;

        MedievalButton equipBtn = new MedievalButton(
                rx + 8, bY, bW, bH,
                I18n.name("gui.wandscape.mage_hut.equip", "装备"),
                () -> sendAction("open_equip"));
        equipBtn.active = canOperate();
        addRenderableWidget(equipBtn);

        MedievalButton strategyBtn = new MedievalButton(
                rx + 8 + bW + bGap, bY, bW, bH,
                I18n.name("gui.wandscape.mage_hut.strategy", "策略"),
                () -> sendAction("open_strategy"));
        strategyBtn.active = canOperate();
        addRenderableWidget(strategyBtn);

        MedievalButton restBtn = new MedievalButton(
                rx + 8 + (bW + bGap) * 2, bY, bW, bH,
                I18n.name("gui.wandscape.mage_hut.rest", "休息"),
                this::onRest);
        restBtn.active = canOperate() && !resting;
        addRenderableWidget(restBtn);
    }

    private void initEmpty(int contentTop) {
        int rx = leftPos + 178;
        int ry = contentTop;
        int rw = 190;
        int rh = 180;

        if (!candidates.isEmpty() && selectedCandidate >= 0 && selectedCandidate < candidates.size()) {
            int abW = 154;
            int abH = 22;
            int abX = rx + (rw - abW) / 2;
            int abY = ry + rh - 30;
            MageCandidate c = candidates.get(selectedCandidate);
            MedievalButton assignBtn = new MedievalButton(
                    abX, abY, abW, abH,
                    I18n.name("gui.wandscape.mage_hut.assign_btn", "指派入住小屋"),
                    () -> onAssign(c));
            addRenderableWidget(assignBtn);
        }
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        int contentTop = topPos + headerHeight + 6;

        if (hasResident) {
            renderOccupied(g, font, mouseX, mouseY, contentTop);
        } else {
            renderEmpty(g, font, mouseX, mouseY, contentTop);
        }

        // Toast feedback — top-right (only while an action toast is showing)
        if (toastMessage != null && System.currentTimeMillis() < toastExpireTick) {
            String toast = toastMessage.getString();
            g.drawString(font, toast, leftPos + PW - font.width(toast) - 12,
                    topPos + headerHeight + 8, toastColor);
        }
    }

    @Override
    protected void renderForeground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        int contentTop = topPos + headerHeight + 6;

        // Tooltip rendering for attributes in occupied mode
        if (hasResident) {
            int lx = leftPos + 12;
            int ly = contentTop;
            int lw = 196;
            int rx = lx + 4;
            int rw = lw - 8;
            for (int i = 0; i < MageHutAttributes.ORDER.size(); i++) {
                int rowY = ly + 68 + i * 16;
                if (isInRect(mouseX, mouseY, rx, rowY, rw, 15)) {
                    renderAttributeTooltip(g, font, mouseX, mouseY, MageHutAttributes.ORDER.get(i), i);
                    break;
                }
            }
        }
    }

    private void renderOccupied(GuiGraphics g, net.minecraft.client.gui.Font font,
                                int mouseX, int mouseY, int contentTop) {
        // ══════════════════════════════════════════════════════════
        // ── Left: Mage Dossier & Interactive 7-Attribute Rows ──
        // ══════════════════════════════════════════════════════════
        int lx = leftPos + 12;
        int ly = contentTop;
        int lw = 196;
        int lh = 180;

        drawInsetField(g, lx, ly, lw, lh);
        drawGlowBorder(g, lx, ly, lw, lh, MedievalColors.BORDER_GOLD_DARK);

        // 3D Avatar Box
        int px = lx + 6;
        int py = ly + 6;
        int pw = 46;
        int ph = 56;
        g.fill(px, py, px + pw, py + ph, MedievalColors.PARCHMENT_DEEPEST);
        drawGlowBorder(g, px, py, pw, ph, MedievalColors.BORDER_GOLD);
        if (previewNpc != null) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    g, px + 2, py + 2, px + pw - 2, py + ph - 2, 22, 0.0625f, mouseX, mouseY, previewNpc);
        }

        // Profile Identity info
        int hx = lx + 56;
        String nameText = mageName;
        if (font.width(nameText) > 130) {
            nameText = font.plainSubstrByWidth(nameText, 122) + "…";
        }
        g.drawString(font, nameText, hx, ly + 8, MedievalColors.ACCENT_GOLD);
        g.drawString(font, I18n.name("gui.wandscape.mage_hut.level", "等级 Lv.%d", mageLevel).getString(),
                hx, ly + 21, MedievalColors.TEXT_WARM_WHITE);
        g.drawString(font, I18n.name("gui.wandscape.mage_hut.colony_level", "城镇 Lv.%d", colonyLevel).getString(),
                hx, ly + 34, MedievalColors.TEXT_MUTED);

        String status;
        int statusCol;
        if (!alive) {
            status = I18n.name("gui.wandscape.mage_hut.status_dead", "已阵亡").getString();
            statusCol = 0xFFFF5555;
        } else if (resting) {
            status = I18n.name("gui.wandscape.mage_hut.status_resting", "休息中").getString();
            statusCol = MedievalColors.INFO_BLUE;
        } else {
            status = I18n.name("gui.wandscape.mage_hut.status_idle", "正常执勤").getString();
            statusCol = MedievalColors.SUCCESS_GREEN;
        }
        g.drawString(font, status, hx, ly + 47, statusCol);

        // Divider
        g.fill(lx + 4, ly + 65, lx + lw - 4, ly + 66, MedievalColors.BORDER_GOLD_DARK);

        // 7 Attributes Rows
        int rx = lx + 4;
        int rw = lw - 8;
        for (int i = 0; i < MageHutAttributes.ORDER.size(); i++) {
            AttributeType type = MageHutAttributes.ORDER.get(i);
            int rowY = ly + 68 + i * 16;
            boolean isSel = (i == selectedTrain);
            boolean isHov = isInRect(mouseX, mouseY, rx, rowY, rw, 15);

            // Card highlight
            if (isSel) {
                g.fill(rx, rowY, rx + rw, rowY + 15, 0x66C8A040);
                drawGlowBorder(g, rx, rowY, rw, 15, MedievalColors.BORDER_GOLD);
                g.fill(rx + 1, rowY + 2, rx + 3, rowY + 13, MedievalColors.ACCENT_GOLD);
            } else if (isHov) {
                g.fill(rx, rowY, rx + rw, rowY + 15, 0x33C8A040);
                g.fill(rx, rowY, rx + rw, rowY + 1, MedievalColors.BORDER_GOLD_DARK);
                g.fill(rx, rowY + 14, rx + rw, rowY + 15, MedievalColors.BORDER_GOLD_DARK);
            } else {
                g.fill(rx, rowY, rx + rw, rowY + 15, (i % 2 == 0) ? 0x22000000 : 0x11000000);
            }

            // Stat name
            String attrName = attrKeyLabel(type);
            g.drawString(font, attrName, rx + 6, rowY + 4,
                    isSel ? MedievalColors.ACCENT_GOLD : MedievalColors.TEXT_WARM_WHITE);

            // Mini progress bar for base training
            int barX = rx + 44;
            int barY = rowY + 5;
            int barW = 46;
            int barH = 6;
            g.fill(barX, barY, barX + barW, barY + barH, 0xFF0E0804);
            g.fill(barX, barY, barX + barW, barY + 1, 0xFF3D2A14);
            g.fill(barX, barY + barH - 1, barX + barW, barY + barH, 0xFF3D2A14);
            g.fill(barX, barY, barX + 1, barY + barH, 0xFF3D2A14);
            g.fill(barX + barW - 1, barY, barX + barW, barY + barH, 0xFF3D2A14);

            float b = base[i];
            float lower = MageHutAttributes.lower(type);
            float upper = MageHutAttributes.upper(type);
            float pct = (upper > lower) ? (b - lower) / (upper - lower) : 1.0f;
            pct = Math.max(0f, Math.min(1.0f, pct));
            int fillW = (int) ((barW - 2) * pct);
            int barColor = (b >= upper - 0.001f) ? 0xFFE0B030 : MedievalColors.MANA_BLUE;
            if (fillW > 0) {
                g.fill(barX + 1, barY + 1, barX + 1 + fillW, barY + barH - 1, barColor);
            }

            // Base value
            g.drawString(font, fmt(b), rx + 94, rowY + 4, MedievalColors.TEXT_MUTED);

            // Bonus value
            float lvl = MageHutAttributes.perLevel(type) * Math.max(0, mageLevel - 1);
            float eq = equip[i];
            if (lvl + eq > 0.001f) {
                String bonusStr = "+" + fmt(lvl + eq);
                g.drawString(font, bonusStr, rx + 124, rowY + 4, MedievalColors.SUCCESS_GREEN);
            }

            // Total effective
            float eff = b + lvl + eq;
            String effStr = fmt(eff);
            g.drawString(font, effStr, rx + rw - font.width(effStr) - 4, rowY + 4,
                    isSel ? MedievalColors.ACCENT_GOLD : MedievalColors.TEXT_WARM_WHITE);
        }

        // ══════════════════════════════════════════════════════════
        // ── Right: Attribute Training & Commands Modules ──
        // ══════════════════════════════════════════════════════════
        int rightX = leftPos + 214;
        int rightY = contentTop;
        int rightW = 154;
        int rightH = 180;

        drawInsetField(g, rightX, rightY, rightW, rightH);
        drawGlowBorder(g, rightX, rightY, rightW, rightH, MedievalColors.BORDER_GOLD_DARK);

        // ── Card 1: Stat Training Module ──
        drawMinimalBox(g, rightX + 4, rightY + 4, rightW - 8, 80, false, false);
        g.drawCenteredString(font, I18n.name("gui.wandscape.mage_hut.training_title", "属性特训"),
                rightX + rightW / 2, rightY + 8, MedievalColors.ACCENT_GOLD);

        AttributeType selType = MageHutAttributes.ORDER.get(selectedTrain);
        float selBase = base[selType.ordinal()];
        float selUpper = MageHutAttributes.upper(selType);
        float selStep = MageHutAttributes.trainStep(selType);

        String curStr = attrKeyLabel(selType) + ": " + fmt(selBase) + " / " + fmt(selUpper);
        g.drawString(font, curStr, rightX + 8, rightY + 22, MedievalColors.TEXT_WARM_WHITE);

        boolean canTrain = canOperate() && MageHutAttributes.canTrain(selType, selBase);
        if (canTrain) {
            String stepStr = I18n.name("gui.wandscape.mage_hut.train_step", "单次特训: +%s", fmt(selStep)).getString();
            g.drawString(font, stepStr, rightX + 8, rightY + 34, MedievalColors.SUCCESS_GREEN);
        } else {
            String maxStr = I18n.name("gui.wandscape.mage_hut.maxed_attr", "已达属性上限").getString();
            g.drawString(font, maxStr, rightX + 8, rightY + 34, MedievalColors.ACCENT_GOLD);
        }

        if (canTrain) {
            List<ElementType> els = MageHutAttributes.trainElements(selType);
            long cost = MageHutAttributes.trainCostPerElement(selType, selBase);
            int cx = rightX + 8;
            for (ElementType t : els) {
                WandscapeTheme.drawIcon(g, WandscapeTheme.elementIcon(t.getId()),
                        cx, rightY + 46, 14, 14, WandscapeTheme.elementColor(t.getId()));
                g.drawString(font, "×" + cost, cx + 17, rightY + 47, MedievalColors.TEXT_DIM);
                cx += 17 + font.width("×" + cost) + 6;
            }
        }

        // ── Card 2: Promotion & Commands Module ──
        drawMinimalBox(g, rightX + 4, rightY + 88, rightW - 8, 88, false, false);
        g.drawCenteredString(font, I18n.name("gui.wandscape.mage_hut.command_title", "晋升与指令"),
                rightX + rightW / 2, rightY + 94, MedievalColors.ACCENT_GOLD);
        if (MageHutAttributes.canLevelUp(mageLevel, colonyLevel)) {
            String upCost = I18n.name("gui.wandscape.mage_hut.upgrade_cost", "消耗 7 系元素各 %d",
                    MageHutAttributes.upgradeCostPerElement(mageLevel)).getString();
            g.drawString(font, upCost, rightX + 8, rightY + 104, MedievalColors.TEXT_DIM);
        }
    }

    private void renderEmpty(GuiGraphics g, net.minecraft.client.gui.Font font,
                            int mouseX, int mouseY, int contentTop) {
        // ══════════════════════════════════════════════════════════
        // ── Left: Candidate Mages List ──
        // ══════════════════════════════════════════════════════════
        int lx = leftPos + 12;
        int ly = contentTop;
        int lw = 160;
        int lh = 180;

        drawInsetField(g, lx, ly, lw, lh);
        drawGlowBorder(g, lx, ly, lw, lh, MedievalColors.BORDER_GOLD_DARK);

        String listTitle = I18n.name("gui.wandscape.mage_hut.candidate_list", "小镇法师名单").getString()
                + " (" + candidates.size() + ")";
        g.drawString(font, listTitle, lx + 6, ly + 6, MedievalColors.ACCENT_GOLD);
        g.fill(lx + 4, ly + 17, lx + lw - 4, ly + 18, MedievalColors.BORDER_GOLD_DARK);

        if (candidates.isEmpty()) {
            int cx = lx + lw / 2;
            g.drawCenteredString(font, I18n.name("gui.wandscape.mage_hut.no_candidate", "暂无可指派法师"),
                    cx, ly + 50, MedievalColors.TEXT_MUTED);
            String[] hintLines = I18n.name("gui.wandscape.mage_hut.no_candidate_sub",
                    "在酒馆招募法师后\n可在此指派入住").getString().split("\n", 2);
            g.drawCenteredString(font, hintLines[0], cx, ly + 70, MedievalColors.TEXT_DIM);
            if (hintLines.length > 1) {
                g.drawCenteredString(font, hintLines[1], cx, ly + 82, MedievalColors.TEXT_DIM);
            }
        } else {
            int cardH = 26;
            int cardGap = 3;
            int itemH = cardH + cardGap;
            int viewX = lx + 3;
            int viewY = ly + 20;
            int viewH = lh - 24;
            int totalH = candidates.size() * itemH;
            int maxScroll = Math.max(0, totalH - viewH);
            candidateScrollOffset = Math.clamp(candidateScrollOffset, 0, maxScroll);
            boolean hasScrollbar = maxScroll > 0;
            int sbW = 4;
            int cw = hasScrollbar ? (lw - 6 - sbW - 3) : (lw - 6);

            g.enableScissor(viewX, viewY, viewX + (hasScrollbar ? cw + sbW + 3 : cw), viewY + viewH);
            for (int i = 0; i < candidates.size(); i++) {
                int cy = viewY + i * itemH - candidateScrollOffset;
                if (cy + cardH < viewY || cy > viewY + viewH) continue;
                int cx = viewX;
                MageCandidate c = candidates.get(i);
                boolean isSel = (i == selectedCandidate);
                boolean isHov = isInRect(mouseX, mouseY, cx, cy, cw, cardH)
                        && mouseY >= viewY && mouseY <= viewY + viewH;

                if (isSel) {
                    g.fill(cx, cy, cx + cw, cy + cardH, 0x66C8A040);
                    drawGlowBorder(g, cx, cy, cw, cardH, MedievalColors.BORDER_GOLD);
                    g.fill(cx + 1, cy + 2, cx + 3, cy + cardH - 2, MedievalColors.ACCENT_GOLD);
                } else if (isHov) {
                    g.fill(cx, cy, cx + cw, cy + cardH, 0x33C8A040);
                    g.fill(cx, cy, cx + cw, cy + 1, MedievalColors.BORDER_GOLD_DARK);
                    g.fill(cx, cy + cardH - 1, cx + cw, cy + cardH, MedievalColors.BORDER_GOLD_DARK);
                } else {
                    g.fill(cx, cy, cx + cw, cy + cardH, 0x22000000);
                }

                String cName = (i + 1) + ". " + c.name();
                if (font.width(cName) > cw - 12) {
                    cName = font.plainSubstrByWidth(cName, cw - 18) + "…";
                }
                g.drawString(font, cName, cx + 6, cy + 3,
                        isSel ? MedievalColors.ACCENT_GOLD : MedievalColors.TEXT_WARM_WHITE);

                String cStatus = c.idle() ? "空闲待命" : "正在忙碌";
                g.drawString(font, cStatus, cx + 6, cy + 14,
                        c.idle() ? MedievalColors.SUCCESS_GREEN : MedievalColors.TEXT_MUTED);
            }
            g.disableScissor();

            if (hasScrollbar) {
                int sbX = lx + lw - sbW - 3;
                int thumbH = Math.max(14, viewH * viewH / totalH);
                int thumbY = viewY + candidateScrollOffset * (viewH - thumbH) / maxScroll;
                // Track
                g.fill(sbX, viewY, sbX + sbW, viewY + viewH, MedievalColors.SCROLLBAR_TRACK);
                // Thumb
                g.fill(sbX, thumbY, sbX + sbW, thumbY + thumbH,
                        candidateScrollDragging ? MedievalColors.BORDER_GOLD : MedievalColors.SCROLLBAR_THUMB);
            }
        }

        // ══════════════════════════════════════════════════════════
        // ── Right: Assignment Dossier & Confirmation ──
        // ══════════════════════════════════════════════════════════
        int rx = leftPos + 178;
        int ry = contentTop;
        int rw = 190;
        int rh = 180;

        drawInsetField(g, rx, ry, rw, rh);
        drawGlowBorder(g, rx, ry, rw, rh, MedievalColors.BORDER_GOLD_DARK);

        if (!candidates.isEmpty() && selectedCandidate >= 0 && selectedCandidate < candidates.size()) {
            MageCandidate c = candidates.get(selectedCandidate);

            g.drawCenteredString(font, I18n.name("gui.wandscape.mage_hut.assign_title", "法师入住指派"),
                    rx + rw / 2, ry + 6, MedievalColors.ACCENT_GOLD);

            // 3D Avatar Box
            int px = rx + 6;
            int py = ry + 18;
            int pw = 44;
            int ph = 52;
            g.fill(px, py, px + pw, py + ph, MedievalColors.PARCHMENT_DEEPEST);
            drawGlowBorder(g, px, py, pw, ph, MedievalColors.BORDER_GOLD);
            if (previewNpc != null) {
                InventoryScreen.renderEntityInInventoryFollowsMouse(
                        g, px + 2, py + 2, px + pw - 2, py + ph - 2, 22, 0.0625f, mouseX, mouseY, previewNpc);
            }

            // Summary text
            int hx = rx + 56;
            g.drawString(font, c.name(), hx, ry + 22, MedievalColors.ACCENT_GOLD);
            g.drawString(font, c.idle() ? "状态: 空闲待命" : "状态: 任务执行中",
                    hx, ry + 36, MedievalColors.TEXT_WARM_WHITE);
            g.drawString(font, "居所: 尚未分配专属居所", hx, ry + 50, MedievalColors.TEXT_MUTED);

            // Description Box
            drawMinimalBox(g, rx + 6, ry + 74, rw - 12, 64, false, false);
            int dx = rx + 10;
            int dy = ry + 78;
            g.drawString(font, I18n.name("gui.wandscape.mage_hut.assign_desc_1", "指派后该法师将以此小屋为居所。").getString(),
                    dx, dy, MedievalColors.TEXT_WARM_WHITE);
            g.drawString(font, I18n.name("gui.wandscape.mage_hut.assign_desc_2", "• 开启 7 项属性专项特训").getString(),
                    dx, dy + 13, MedievalColors.TEXT_MUTED);
            g.drawString(font, I18n.name("gui.wandscape.mage_hut.assign_desc_3", "• 随小镇等级晋升职业等阶").getString(),
                    dx, dy + 25, MedievalColors.TEXT_MUTED);
            g.drawString(font, I18n.name("gui.wandscape.mage_hut.assign_desc_4", "• 获得专属休息与恢复据点").getString(),
                    dx, dy + 37, MedievalColors.TEXT_MUTED);
        } else {
            int cx = rx + rw / 2;
            g.drawCenteredString(font, I18n.name("gui.wandscape.mage_hut.title", "法师专属住宅").getString(),
                    cx, ry + 35, MedievalColors.ACCENT_GOLD);
            g.drawCenteredString(font, "法师小屋是小镇法师的成长居所", cx, ry + 58, MedievalColors.TEXT_WARM_WHITE);
            g.drawCenteredString(font, "• 招募法师后在此完成入住指派", cx, ry + 78, MedievalColors.TEXT_MUTED);
            g.drawCenteredString(font, "• 定向强化法师的施法与建造属性", cx, ry + 94, MedievalColors.TEXT_MUTED);
            g.drawCenteredString(font, "• 随小镇等级提升职业等阶", cx, ry + 110, MedievalColors.TEXT_MUTED);
        }
    }

    private void renderAttributeTooltip(GuiGraphics g, net.minecraft.client.gui.Font font,
                                        int mouseX, int mouseY, AttributeType type, int index) {
        float b = base[index];
        float upper = MageHutAttributes.upper(type);
        float perLvl = MageHutAttributes.perLevel(type);
        float lvl = perLvl * Math.max(0, mageLevel - 1);
        float eq = equip[index];
        float eff = b + lvl + eq;
        float step = MageHutAttributes.trainStep(type);

        List<Component> tooltip = new ArrayList<>();
        tooltip.add(Component.literal(attrKeyLabel(type) + " " + I18n.name("gui.wandscape.mage_hut.dossier_title", "属性详情").getString())
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        tooltip.add(Component.literal(String.format("• 基础数值: %s / %s%s",
                fmt(b), fmt(upper), b >= upper - 0.001f ? " (已达上限)" : "")).withStyle(ChatFormatting.WHITE));
        tooltip.add(Component.literal(String.format("• 等阶加成: +%s (Lv.%d, 每级+%s)",
                fmt(lvl), mageLevel, fmt(perLvl))).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal(String.format("• 装备加成: +%s", fmt(eq))).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.literal("──────────────────────").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.literal(String.format("综合生效: %s", fmt(eff))).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));

        if (canOperate() && MageHutAttributes.canTrain(type, b)) {
            tooltip.add(Component.literal(String.format("点击该行可特训 (每次+%s)", fmt(step)))
                    .withStyle(ChatFormatting.GREEN, ChatFormatting.ITALIC));
        } else if (b >= upper - 0.001f) {
            tooltip.add(Component.literal("该属性基础已达特训极限")
                    .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
        }

        g.renderComponentTooltip(font, tooltip, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int contentTop = topPos + headerHeight + 6;
            if (hasResident) {
                int lx = leftPos + 12;
                int ly = contentTop;
                int lw = 196;
                int rx = lx + 4;
                int rw = lw - 8;
                for (int i = 0; i < MageHutAttributes.ORDER.size(); i++) {
                    int rowY = ly + 68 + i * 16;
                    if (isInRect(mouseX, mouseY, rx, rowY, rw, 15)) {
                        if (selectedTrain != i) {
                            selectedTrain = i;
                            clearWidgets();
                            init();
                            Minecraft.getInstance().getSoundManager().play(
                                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
                        }
                        return true;
                    }
                }
            } else if (!candidates.isEmpty()) {
                int lx = leftPos + 12;
                int ly = contentTop;
                int lw = 160;
                int lh = 180;
                int viewX = lx + 3;
                int viewY = ly + 20;
                int viewH = lh - 24;
                int cardH = 26;
                int cardGap = 3;
                int itemH = cardH + cardGap;
                int totalH = candidates.size() * itemH;
                int maxScroll = Math.max(0, totalH - viewH);
                int sbW = 4;
                int sbX = lx + lw - sbW - 3;

                // Check scrollbar click
                if (maxScroll > 0 && mouseX >= sbX - 2 && mouseX <= sbX + sbW + 2
                        && mouseY >= viewY && mouseY <= viewY + viewH) {
                    int thumbH = Math.max(14, viewH * viewH / totalH);
                    int thumbY = viewY + candidateScrollOffset * (viewH - thumbH) / maxScroll;
                    if (mouseY >= thumbY && mouseY <= thumbY + thumbH) {
                        candidateScrollDragging = true;
                        candidateDragStartMouseY = mouseY;
                        candidateDragStartScrollOffset = candidateScrollOffset;
                    } else {
                        double clickRatio = (mouseY - viewY - thumbH / 2.0) / (viewH - thumbH);
                        candidateScrollOffset = (int) Math.clamp(clickRatio * maxScroll, 0, maxScroll);
                        candidateScrollDragging = true;
                        candidateDragStartMouseY = mouseY;
                        candidateDragStartScrollOffset = candidateScrollOffset;
                    }
                    return true;
                }

                // Check candidate card click
                int cw = (maxScroll > 0) ? (lw - 6 - sbW - 3) : (lw - 6);
                if (mouseX >= viewX && mouseX <= viewX + cw && mouseY >= viewY && mouseY <= viewY + viewH) {
                    for (int i = 0; i < candidates.size(); i++) {
                        int cy = viewY + i * itemH - candidateScrollOffset;
                        if (isInRect(mouseX, mouseY, viewX, cy, cw, cardH)) {
                            if (selectedCandidate != i) {
                                selectedCandidate = i;
                                rebuildPreviewNpc();
                                clearWidgets();
                                init();
                                Minecraft.getInstance().getSoundManager().play(
                                        SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
                            }
                            return true;
                        }
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!hasResident && !candidates.isEmpty()) {
            int contentTop = topPos + headerHeight + 6;
            int lx = leftPos + 12;
            int ly = contentTop;
            int lw = 160;
            int lh = 180;
            if (isInRect(mouseX, mouseY, lx, ly, lw, lh)) {
                int cardH = 26;
                int cardGap = 3;
                int itemH = cardH + cardGap;
                int viewH = lh - 24;
                int maxScroll = Math.max(0, candidates.size() * itemH - viewH);
                if (maxScroll > 0) {
                    candidateScrollOffset = (int) Math.clamp(
                            candidateScrollOffset - scrollY * itemH * 2, 0, maxScroll);
                    return true;
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (candidateScrollDragging && !hasResident && !candidates.isEmpty()) {
            int contentTop = topPos + headerHeight + 6;
            int ly = contentTop;
            int lh = 180;
            int viewH = lh - 24;
            int cardH = 26;
            int cardGap = 3;
            int itemH = cardH + cardGap;
            int totalH = candidates.size() * itemH;
            int maxScroll = Math.max(0, totalH - viewH);
            int thumbH = Math.max(14, viewH * viewH / totalH);
            int trackH = viewH - thumbH;
            if (trackH > 0) {
                double deltaY = mouseY - candidateDragStartMouseY;
                candidateScrollOffset = (int) Math.clamp(
                        candidateDragStartScrollOffset + (deltaY * maxScroll / trackH),
                        0, maxScroll);
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        candidateScrollDragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void onTrain(AttributeType type) {
        sendAction("train:" + type.name());
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.1f));
        setToast(I18n.name("gui.wandscape.mage_hut.toast_trained", "已完成属性特训！"), MedievalColors.SUCCESS_GREEN);
    }

    private void onUpgrade() {
        sendAction("upgrade");
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.0f));
        setToast(I18n.name("gui.wandscape.mage_hut.toast_upgraded", "法师已晋升至 Lv.%d！", mageLevel + 1), MedievalColors.SUCCESS_GREEN);
    }

    private void onRest() {
        sendAction("rest");
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 0.8f));
        setToast(I18n.name("gui.wandscape.mage_hut.toast_resting", "法师已前往小屋休息..."), MedievalColors.INFO_BLUE);
    }

    private void onAssign(MageCandidate c) {
        sendAction("assign:" + c.npcId());
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.VILLAGER_YES, 1.0f));
        setToast(I18n.name("gui.wandscape.mage_hut.toast_assigned", "法师已成功入住小屋！"), MedievalColors.SUCCESS_GREEN);
    }

    /** Open the colony warehouse to check remaining element counts. */
    private void onOpenWarehouse() {
        if (buildingPos == null) return;
        PacketDistributor.sendToServer(new OpenWarehousePacket(buildingPos));
    }

    private void sendAction(String action) {
        PacketDistributor.sendToServer(new MageHutActionPacket(buildingPos, action));
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    private String attrKeyLabel(AttributeType type) {
        return I18n.name("gui.wandscape.mage_hut." + attrKey(type), fallbackLabel(type)).getString();
    }

    private static String attrKey(AttributeType type) {
        return switch (type) {
            case MAX_HP -> "attr_hp";
            case MOVE_SPEED -> "attr_speed";
            case SPELL_POWER -> "attr_power";
            case WORK_SPEED -> "attr_work";
            case SPELL_SPEED -> "attr_cast";
            case ARMOR_VALUE -> "attr_armor";
            case MAX_MANA -> "attr_mana";
            default -> "";
        };
    }

    private static String fallbackLabel(AttributeType type) {
        return switch (type) {
            case MAX_HP -> "生命";
            case MOVE_SPEED -> "速度";
            case SPELL_POWER -> "法强";
            case WORK_SPEED -> "工速";
            case SPELL_SPEED -> "施速";
            case ARMOR_VALUE -> "护甲";
            case MAX_MANA -> "魔力";
            default -> "";
        };
    }

    private static String fmt(float v) {
        if (Math.abs(v - Math.floor(v)) < 0.001f) {
            return String.valueOf((int) v);
        }
        return String.format("%.2f", v);
    }
}
