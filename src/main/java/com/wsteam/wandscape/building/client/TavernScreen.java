package com.wsteam.wandscape.building.client;

import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.building.network.TavernRecruitPacket;
import com.wsteam.wandscape.shared.data.MageResume;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
/**
 * Tavern GUI — recruit NPCs and review mage resumes.
 *
 * <p>Two tabs:
 * <ul>
 *   <li><b>Recruit</b> — spawn a generic NPC for the colony</li>
 *   <li><b>Mages</b> — list mage tourists who left resumes at 100% satisfaction</li>
 * </ul>
 */
public class TavernScreen extends MedievalScreen {

    private static final int PW = 300;
    private static final int PH = 230;

    private final BlockPos buildingPos;
    private final UUID colonyId;
    private final List<MageResume> mageResumes;

    private int activeTab = 0; // 0 = Recruit, 1 = Mages

    public TavernScreen(BlockPos buildingPos, UUID colonyId, List<MageResume> mageResumes) {
        super(Component.literal("Tavern"), PW, PH);
        setTitleBar("Adventurer's Tavern");
        this.buildingPos = buildingPos;
        this.colonyId = colonyId;
        this.mageResumes = mageResumes;
    }

    @Override
    protected void init() {
        super.init();

        // Tab buttons at top
        int tabW = 80;
        int tabH = 18;
        int tabY = topPos + headerHeight + 4;

        addRenderableWidget(new MedievalButton(
                leftPos + 16, tabY, tabW, tabH,
                Component.literal("Recruit NPC"),
                () -> activeTab = 0));
        addRenderableWidget(new MedievalButton(
                leftPos + 16 + tabW + 8, tabY, tabW, tabH,
                Component.literal("Mages (" + mageResumes.size() + ")"),
                () -> activeTab = 1));

        if (activeTab == 0) {
            initRecruitTab();
        } else {
            initMageTab();
        }

        // Close button
        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54, topPos + PH - 22, 46, 16,
                Component.literal("Close"), this::onClose));
    }

    private void initRecruitTab() {
        int centerX = leftPos + PW / 2;
        int btnW = 120;
        int btnH = 20;
        addRenderableWidget(new MedievalButton(
                centerX - btnW / 2, topPos + headerHeight + 36,
                btnW, btnH,
                Component.literal("Recruit NPC"),
                this::onRecruit));
    }

    private void initMageTab() {
        // Mage list entries are rendered in render(), buttons added per entry
        int y = topPos + headerHeight + 36;
        for (int i = 0; i < mageResumes.size(); i++) {
            final int index = i;
            addRenderableWidget(new MedievalButton(
                    leftPos + PW - 60, y, 42, 16,
                    Component.literal("Hire"),
                    () -> onRecruitMage(index)));
            y += 22;
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Rebuild if tab changed (crude but works for this simple UI)
        if (children().stream().filter(w -> w instanceof MedievalButton).count() <= 1) {
            clearWidgets();
            init();
        }
        super.render(g, mouseX, mouseY, partialTick);

        var font = Minecraft.getInstance().font;

        if (activeTab == 0) {
            String subtitle = "Recruit a new adventurer";
            int textW = font.width(subtitle);
            g.drawString(font, subtitle,
                    leftPos + (PW - textW) / 2, topPos + headerHeight + 60,
                    MedievalColors.TEXT_MUTED);

            String costText = "No elemental cost";
            int costW = font.width(costText);
            g.drawString(font, costText,
                    leftPos + (PW - costW) / 2, topPos + headerHeight + 76,
                    MedievalColors.TEXT_DIM);
        } else {
            // Mages tab
            if (mageResumes.isEmpty()) {
                String msg = "No mage resumes available.";
                g.drawString(font, msg, leftPos + 16, topPos + headerHeight + 40,
                        MedievalColors.TEXT_MUTED);
                String hint = "Mages reach 100% satisfaction to leave resumes.";
                g.drawString(font, hint, leftPos + 16, topPos + headerHeight + 54,
                        MedievalColors.TEXT_DIM);
            } else {
                int y = topPos + headerHeight + 34;
                for (int i = 0; i < mageResumes.size(); i++) {
                    MageResume r = mageResumes.get(i);
                    String line = (i + 1) + ". " + r.touristName()
                            + "  Lv." + r.level()
                            + "  MP:" + r.maxMana()
                            + "  SP:" + r.spellPower();
                    g.drawString(font, line, leftPos + 16, y, MedievalColors.TEXT_WARM_WHITE);
                    y += 22;
                    if (y > topPos + PH - 30) break;
                }
            }
        }

        // Colony info at bottom
        String colText = "Colony: " + colonyId.toString().substring(0, 8);
        g.drawString(font, colText, leftPos + 16, topPos + PH - 28, MedievalColors.TEXT_DIM);
    }

    private void onRecruit() {
        PacketDistributor.sendToServer(new TavernRecruitPacket(buildingPos, "spawn_npc"));
    }

    private void onRecruitMage(int index) {
        PacketDistributor.sendToServer(new TavernRecruitPacket(buildingPos, "recruit_mage:" + index));
    }
}
