package com.wsteam.wandscape.building.client;

import java.util.UUID;

import com.wsteam.wandscape.building.network.TavernRecruitPacket;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Tavern GUI — recruit NPCs for the colony.
 *
 * <p>Opened when a player right-clicks a tavern building.
 * Shows a single "Recruit NPC" button.
 */
public class TavernScreen extends MedievalScreen {

    private static final int PW = 220;
    private static final int PH = 110;

    private final BlockPos buildingPos;
    private final UUID colonyId;

    public TavernScreen(BlockPos buildingPos, UUID colonyId) {
        super(Component.literal("Tavern"), PW, PH);
        setTitleBar("Adventurer's Tavern");
        this.buildingPos = buildingPos;
        this.colonyId = colonyId;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = leftPos + PW / 2;

        // Recruit button — centered
        int btnW = 120;
        int btnH = 20;
        addRenderableWidget(new MedievalButton(
                centerX - btnW / 2, topPos + headerHeight + 24,
                btnW, btnH,
                Component.literal("Recruit NPC"),
                this::onRecruit));

        // Close button
        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54, topPos + PH - 22, 46, 16,
                Component.literal("Close"), this::onClose));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        // Subtitle text
        String subtitle = "Recruit a new adventurer";
        int textW = Minecraft.getInstance().font.width(subtitle);
        g.drawString(Minecraft.getInstance().font, subtitle,
                leftPos + (PW - textW) / 2, topPos + headerHeight + 8,
                MedievalColors.TEXT_MUTED);

        // Colony info
        String colText = "Colony: " + colonyId.toString().substring(0, 8);
        int colW = Minecraft.getInstance().font.width(colText);
        g.drawString(Minecraft.getInstance().font, colText,
                leftPos + (PW - colW) / 2, topPos + headerHeight + 52,
                MedievalColors.TEXT_DIM);
    }

    private void onRecruit() {
        PacketDistributor.sendToServer(new TavernRecruitPacket(buildingPos, "spawn_npc"));
    }
}
