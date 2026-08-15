package com.wsteam.wandscape.building.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.building.network.TavernRecruitPacket;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.data.MageResume;
import com.wsteam.wandscape.shared.registry.WandscapeConstants;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

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

/**
 * Tavern GUI — recruit NPCs and review mage resumes.
 *
 * <p>Two tabs:
 * <ul>
 *   <li><b>Mage Resumes</b> — candidate list with detailed dossier, 3D wizard preview, and individual hiring</li>
 *   <li><b>Direct Recruit</b> — publish recruitment order to summon a random mage for elements</li>
 * </ul>
 */
public class TavernScreen extends MedievalScreen {

    private static final int PW = 340;
    private static final int PH = 230;

    private final BlockPos buildingPos;
    private final UUID colonyId;
    private int recruitCount;
    private final List<MageResume> mageResumes = new ArrayList<>();

    private int activeTab = 0; // 0 = Resumes, 1 = Direct Recruit
    private int selectedIndex = 0;

    private WandscapeNpc previewNpc;

    private Component toastMessage;
    private int toastColor = MedievalColors.ACCENT_GOLD;
    private long toastExpireTick = 0;

    public TavernScreen(BlockPos buildingPos, UUID colonyId, int recruitCount,
                        List<MageResume> mageResumes) {
        super(Component.literal("Tavern"), PW, PH);
        setTitleBar(I18n.name("gui.wandscape.tavern.title", "Adventurer's Tavern"));
        this.showCloseButton = true;
        this.showHelpButton = true;
        this.helpDocumentPath = "tavern_guide";
        this.buildingPos = buildingPos;
        this.colonyId = colonyId;
        this.recruitCount = recruitCount;
        this.mageResumes.addAll(mageResumes);
    }

    /** Smoothly update data from server sync packet without reopening screen. */
    public void updateData(int newRecruitCount, List<MageResume> newResumes) {
        this.recruitCount = newRecruitCount;
        this.mageResumes.clear();
        this.mageResumes.addAll(newResumes);
        if (selectedIndex >= this.mageResumes.size()) {
            selectedIndex = Math.max(0, this.mageResumes.size() - 1);
        }
        rebuildPreviewNpc();
        clearWidgets();
        init();
    }

    public void setToast(Component msg, int color) {
        this.toastMessage = msg;
        this.toastColor = color;
        this.toastExpireTick = System.currentTimeMillis() + 3500L;
    }

    private void switchTab(int tab) {
        if (this.activeTab != tab) {
            this.activeTab = tab;
            clearWidgets();
            init();
            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
    }

    private void rebuildPreviewNpc() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mageResumes.isEmpty() || selectedIndex < 0 || selectedIndex >= mageResumes.size()) {
            previewNpc = null;
            return;
        }
        MageResume r = mageResumes.get(selectedIndex);
        previewNpc = new WandscapeNpc(Wandscape.WANDSCAPE_NPC.get(), mc.level);
        previewNpc.guiDisplayMode = true;
        previewNpc.setSkinVariant(r.skinVariant() >= 0 ? r.skinVariant() : 0);
        previewNpc.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Wandscape.WAND.get()));
    }

    @Override
    protected void init() {
        super.init();
        rebuildPreviewNpc();

        int tabY = topPos + headerHeight + 3;

        // Tab 0: Resumes
        int tab0W = 114;
        String tab0Title = I18n.name("gui.wandscape.tavern.tab_resumes", "Mage Resumes").getString()
                + " (" + mageResumes.size() + ")";
        MedievalButton tab0Btn = new MedievalButton(
                leftPos + 12, tabY, tab0W, 18,
                Component.literal("📜 " + tab0Title),
                () -> switchTab(0));
        tab0Btn.active = (activeTab != 0);
        addRenderableWidget(tab0Btn);

        // Tab 1: Direct Recruit
        int tab1W = 100;
        String tab1Title = I18n.name("gui.wandscape.tavern.tab_recruit", "Direct Recruit").getString();
        MedievalButton tab1Btn = new MedievalButton(
                leftPos + 130, tabY, tab1W, 18,
                Component.literal("🎲 " + tab1Title),
                () -> switchTab(1));
        tab1Btn.active = (activeTab != 1);
        addRenderableWidget(tab1Btn);

        int contentTop = topPos + headerHeight + 25;

        if (activeTab == 0) {
            // Tab 0: Hire button for selected candidate
            if (!mageResumes.isEmpty() && selectedIndex >= 0 && selectedIndex < mageResumes.size()) {
                int rx = leftPos + 144;
                int rw = 184;
                int ry = contentTop;
                int btnW = 116;
                int btnH = 20;
                addRenderableWidget(new MedievalButton(
                        rx + (rw - btnW) / 2, ry + 138, btnW, btnH,
                        I18n.name("gui.wandscape.tavern.hire", "Hire Mage"),
                        () -> onRecruitMage(selectedIndex)));
            }
        } else {
            // Tab 1: Direct Recruit Button
            int cx = leftPos + PW / 2;
            int btnW = 140;
            int btnH = 22;
            addRenderableWidget(new MedievalButton(
                    cx - btnW / 2, contentTop + 120, btnW, btnH,
                    I18n.name("gui.wandscape.tavern.publish_recruit", "Publish Recruit Order"),
                    this::onRecruit));
        }

        // Close button at bottom right
        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54, topPos + PH - 20, 44, 16,
                I18n.name("gui.wandscape.common.close", "Close"), this::onClose));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        var font = Minecraft.getInstance().font;
        int contentTop = topPos + headerHeight + 25;

        if (activeTab == 0) {
            renderResumesTab(g, font, mouseX, mouseY, contentTop);
        } else {
            renderDirectRecruitTab(g, font, contentTop);
        }

        // Bottom status & colony label
        if (toastMessage != null && System.currentTimeMillis() < toastExpireTick) {
            g.drawString(font, toastMessage, leftPos + 12, topPos + PH - 16, toastColor);
        } else {
            String colText = I18n.name("gui.wandscape.common.colony_label", "Colony").getString()
                    + ": " + colonyId.toString().substring(0, Math.min(8, colonyId.toString().length()));
            g.drawString(font, colText, leftPos + 12, topPos + PH - 16, MedievalColors.TEXT_DIM);
        }
    }

    private void renderResumesTab(GuiGraphics g, net.minecraft.client.gui.Font font,
                                 int mouseX, int mouseY, int contentTop) {
        int lx = leftPos + 12;
        int ly = contentTop;
        int lw = 126;
        int lh = 164;

        // ── Left: Candidate Selection Box ──
        drawInsetField(g, lx, ly, lw, lh);
        drawGlowBorder(g, lx, ly, lw, lh, MedievalColors.BORDER_GOLD_DARK);

        String headerText = I18n.name("gui.wandscape.tavern.candidates_list", "Applicants").getString()
                + " (" + mageResumes.size() + "/5)";
        g.drawString(font, headerText, lx + 6, ly + 6, MedievalColors.ACCENT_GOLD);
        g.fill(lx + 4, ly + 17, lx + lw - 4, ly + 18, MedievalColors.BORDER_GOLD_DARK);

        if (mageResumes.isEmpty()) {
            int cx = lx + lw / 2;
            g.drawCenteredString(font, I18n.name("gui.wandscape.tavern.no_resumes", "No Resumes"),
                    cx, ly + 50, MedievalColors.TEXT_MUTED);
            String[] hintLines = I18n.name("gui.wandscape.tavern.resume_hint",
                    "满条法师游客离城时\n会在此留下求职简历").getString().split("\n", 2);
            g.drawCenteredString(font, hintLines[0], cx, ly + 68, MedievalColors.TEXT_DIM);
            if (hintLines.length > 1) {
                g.drawCenteredString(font, hintLines[1], cx, ly + 80, MedievalColors.TEXT_DIM);
            }
        } else {
            int cardH = 26;
            int cardGap = 2;
            for (int i = 0; i < mageResumes.size(); i++) {
                int cy = ly + 20 + i * (cardH + cardGap);
                MageResume r = mageResumes.get(i);
                boolean isSel = (i == selectedIndex);
                boolean isHov = isInRect(mouseX, mouseY, lx + 3, cy, lw - 6, cardH);

                // Card background & borders
                if (isSel) {
                    g.fill(lx + 3, cy, lx + lw - 3, cy + cardH, 0x66C8A040);
                    drawGlowBorder(g, lx + 3, cy, lw - 6, cardH, MedievalColors.BORDER_GOLD);
                    // Gold vertical indicator
                    g.fill(lx + 4, cy + 2, lx + 6, cy + cardH - 2, MedievalColors.ACCENT_GOLD);
                } else if (isHov) {
                    g.fill(lx + 3, cy, lx + lw - 3, cy + cardH, 0x33C8A040);
                    g.fill(lx + 3, cy, lx + lw - 3, cy + 1, MedievalColors.BORDER_GOLD_DARK);
                    g.fill(lx + 3, cy + cardH - 1, lx + lw - 3, cy + cardH, MedievalColors.BORDER_GOLD_DARK);
                } else {
                    g.fill(lx + 3, cy, lx + lw - 3, cy + cardH, 0x22000000);
                }

                // Card text
                String nameText = (i + 1) + ". " + r.touristName();
                if (font.width(nameText) > lw - 14) {
                    nameText = font.plainSubstrByWidth(nameText, lw - 20) + "…";
                }
                g.drawString(font, nameText, lx + 8, cy + 3,
                        isSel ? MedievalColors.ACCENT_GOLD : MedievalColors.TEXT_WARM_WHITE);

                String statsSummary = "Lv." + r.level() + " ⚡" + fmt(r.spellPower()) + " ⚒" + fmt(r.workSpeed());
                g.drawString(font, statsSummary, lx + 8, cy + 14,
                        isSel ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_MUTED);
            }
        }

        // ── Right: Selected Candidate Dossier ──
        int rx = leftPos + 144;
        int ry = contentTop;
        int rw = 184;
        int rh = 164;

        drawInsetField(g, rx, ry, rw, rh);
        drawGlowBorder(g, rx, ry, rw, rh, MedievalColors.BORDER_GOLD_DARK);

        if (!mageResumes.isEmpty() && selectedIndex >= 0 && selectedIndex < mageResumes.size()) {
            MageResume r = mageResumes.get(selectedIndex);

            // 3D Preview Box
            int px = rx + 6;
            int py = ry + 6;
            int pw = 52;
            int ph = 64;
            g.fill(px, py, px + pw, py + ph, MedievalColors.PARCHMENT_DEEPEST);
            drawGlowBorder(g, px, py, pw, ph, MedievalColors.BORDER_GOLD);
            if (previewNpc != null) {
                InventoryScreen.renderEntityInInventoryFollowsMouse(
                        g, px + 2, py + 2, px + pw - 2, py + ph - 2, 24, 0.0625f, mouseX, mouseY, previewNpc);
            }

            // Header info next to 3D model
            int hx = rx + 64;
            g.drawString(font, r.touristName(), hx, ry + 8, MedievalColors.ACCENT_GOLD);
            g.drawString(font, I18n.name("gui.wandscape.tavern.rank", "等阶: Lv.%d", r.level()).getString(), hx, ry + 22, MedievalColors.TEXT_WARM_WHITE);

            String specialty = getSpecialtyTag(r);
            g.drawString(font, specialty, hx, ry + 36, MedievalColors.SUCCESS_GREEN);
            g.drawString(font, I18n.name("gui.wandscape.tavern.status_pending", "状态: 待聘用").getString(), hx, ry + 50, MedievalColors.TEXT_MUTED);

            // Divider
            g.fill(rx + 6, ry + 73, rx + rw - 6, ry + 74, MedievalColors.BORDER_GOLD_DARK);

            // Attributes Grid
            int ay = ry + 78;
            int col1 = rx + 8;
            int col2 = rx + 94;

            // Row 1
            g.drawString(font, I18n.name("gui.wandscape.tavern.attr_hp", "❤️ 生命: %s", (int) r.maxHp()).getString(), col1, ay, MedievalColors.TEXT_WARM_WHITE);
            g.drawString(font, I18n.name("gui.wandscape.tavern.attr_mana", "💧 魔力: %s", (int) r.maxMana()).getString(), col2, ay, MedievalColors.MANA_BLUE);

            // Row 2
            g.drawString(font, I18n.name("gui.wandscape.tavern.attr_spell_power", "⚡ 强度: %s", fmt(r.spellPower())).getString(), col1, ay + 13, MedievalColors.ACCENT_GOLD);
            g.drawString(font, I18n.name("gui.wandscape.tavern.attr_work_speed", "⚒️ 工速: %s", fmt(r.workSpeed())).getString(), col2, ay + 13, MedievalColors.TEXT_WARM_WHITE);

            // Row 3
            g.drawString(font, I18n.name("gui.wandscape.tavern.attr_spell_speed", "⏳ 施速: %s", fmt(r.spellSpeed())).getString(), col1, ay + 26, MedievalColors.TEXT_WARM_WHITE);
            g.drawString(font, I18n.name("gui.wandscape.tavern.attr_armor", "🛡️ 护甲: %s", fmt(r.armorValue())).getString(), col2, ay + 26, MedievalColors.TEXT_MUTED);

            // Row 4
            g.drawString(font, I18n.name("gui.wandscape.tavern.attr_move_speed", "✦ 速度: %s", fmt(r.moveSpeed())).getString(), col1, ay + 39, MedievalColors.TEXT_MUTED);
        } else {
            int cx = rx + rw / 2;
            g.drawCenteredString(font, I18n.name("gui.wandscape.tavern.no_selection", "Please select a candidate"),
                    cx, ry + 70, MedievalColors.TEXT_MUTED);
        }
    }

    private void renderDirectRecruitTab(GuiGraphics g, net.minecraft.client.gui.Font font, int contentTop) {
        int cx = leftPos + PW / 2;

        // Title & Description
        g.drawCenteredString(font, I18n.name("gui.wandscape.tavern.recruit_npc", "Recruit Mage"),
                cx, contentTop + 8, MedievalColors.ACCENT_GOLD);
        g.drawCenteredString(font, I18n.name("gui.wandscape.tavern.recruit_subtitle", "Recruit a new adventurer for the colony"),
                cx, contentTop + 22, MedievalColors.TEXT_MUTED);

        // Center Inset Card
        int bx = cx - 116;
        int by = contentTop + 38;
        int bw = 232;
        int bh = 74;

        drawInsetField(g, bx, by, bw, bh);
        drawGlowBorder(g, bx, by, bw, bh, MedievalColors.BORDER_GOLD);

        if (recruitCount == 0) {
            g.drawCenteredString(font, I18n.name("gui.wandscape.tavern.first_free", "✦ 首次招募免费 ✦").getString(),
                    cx, by + 14, MedievalColors.SUCCESS_GREEN);
            g.drawCenteredString(font, I18n.name("gui.wandscape.tavern.recruit_arrive",
                    "招募后法师将即刻抵达酒馆并加入小镇").getString(),
                    cx, by + 32, MedievalColors.TEXT_WARM_WHITE);
            g.drawCenteredString(font, I18n.name("gui.wandscape.tavern.recruit_cost_future",
                    "（此后每次招募将消耗 6 种元素各 %d）",
                    WandscapeConstants.TAVERN_RECRUIT_COST_PER_ELEMENT).getString(),
                    cx, by + 48, MedievalColors.TEXT_DIM);
        } else {
            g.drawCenteredString(font, I18n.name("gui.wandscape.tavern.recruit_cost_now",
                    "招募代价：6 种基础元素各 %d",
                    WandscapeConstants.TAVERN_RECRUIT_COST_PER_ELEMENT).getString(),
                    cx, by + 14, MedievalColors.TEXT_WARM_WHITE);
            g.drawCenteredString(font, I18n.name("gui.wandscape.tavern.recruit_count",
                    "已累计招募：%d 位法师", recruitCount).getString(),
                    cx, by + 32, MedievalColors.ACCENT_GOLD);
            g.drawCenteredString(font, I18n.name("gui.wandscape.tavern.recruit_deduct",
                    "将自动从小镇仓库中结算扣除所需元素").getString(),
                    cx, by + 48, MedievalColors.TEXT_DIM);
        }
    }

    private String getSpecialtyTag(MageResume r) {
        float maxVal = Math.max(r.spellPower(), Math.max(r.workSpeed(), r.spellSpeed()));
        if (maxVal == r.spellPower() && r.spellPower() > 1.2f) {
            return I18n.name("gui.wandscape.tavern.specialty_power", "法术专精 (强度 %.1f)", r.spellPower()).getString();
        } else if (maxVal == r.workSpeed() && r.workSpeed() > 1.2f) {
            return I18n.name("gui.wandscape.tavern.specialty_work", "建造专精 (工速 %.1f)", r.workSpeed()).getString();
        } else if (maxVal == r.spellSpeed() && r.spellSpeed() > 1.2f) {
            return I18n.name("gui.wandscape.tavern.specialty_cast", "施法专精 (施速 %.1f)", r.spellSpeed()).getString();
        }
        return I18n.name("gui.wandscape.tavern.specialty_balanced", "全能均衡型").getString();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int contentTop = topPos + headerHeight + 25;

        // If clicking on candidate cards on Tab 0
        if (activeTab == 0 && !mageResumes.isEmpty()) {
            int lx = leftPos + 12;
            int ly = contentTop;
            int lw = 126;
            int cardH = 26;
            int cardGap = 2;

            for (int i = 0; i < mageResumes.size(); i++) {
                int cy = ly + 20 + i * (cardH + cardGap);
                if (isInRect(mouseX, mouseY, lx + 3, cy, lw - 6, cardH)) {
                    if (selectedIndex != i) {
                        selectedIndex = i;
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

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void onRecruit() {
        PacketDistributor.sendToServer(new TavernRecruitPacket(buildingPos, "spawn_npc"));
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.1f));
        setToast(I18n.name("gui.wandscape.tavern.recruit_published", "已发布招募令，正在派遣法师..."), MedievalColors.SUCCESS_GREEN);
    }

    private void onRecruitMage(int index) {
        if (index < 0 || index >= mageResumes.size()) return;
        MageResume r = mageResumes.get(index);
        PacketDistributor.sendToServer(new TavernRecruitPacket(buildingPos, "recruit_mage:" + index));
        Minecraft.getInstance().getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.VILLAGER_YES, 1.0f));
        setToast(I18n.name("gui.wandscape.tavern.hired_success", "已成功聘用法师：%s！", r.touristName()), MedievalColors.SUCCESS_GREEN);
    }

    private static String fmt(float v) {
        return String.format("%.1f", v);
    }
}
