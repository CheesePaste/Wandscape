package com.wsteam.wandscape.content.building.client;

import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.component.MedievalButton;
import com.wsteam.wandscape.foundation.ui.component.MedievalScreen;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;
/**
 * Hotel GUI — view occupancy and checked-in guest names.
 */
public class HotelScreen extends MedievalScreen {

    private static final int PW = 300;
    private static final int PH = 230;

    private final int maxOccupancy;
    private final int currentOccupancy;
    private final List<String> guestNames;

    public HotelScreen(BlockPos buildingPos, UUID colonyId, UUID buildingId, String creator,
                       int maxOccupancy, int currentOccupancy, List<String> guestNames) {
        super(Component.literal("Hotel"), PW, PH);
        setTitleBar(I18n.name("gui.wandscape.hotel.title", "Hotel / Inn"));
        this.showCloseButton = true;
        this.showHelpButton = true;
        this.helpDocumentPath = "service_guide";
        setCreator(creator);
        this.maxOccupancy = maxOccupancy;
        this.currentOccupancy = currentOccupancy;
        this.guestNames = guestNames;
        setBuildingContext(buildingId, buildingPos);
    }

    @Override
    protected void init() {
        super.init();

        // Close button
        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54, topPos + PH - 22, 46, 16,
                I18n.name("gui.wandscape.common.close", "Close"), this::onClose));
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        int x = leftPos + 16;
        int y = topPos + headerHeight + 10;

        // Occupancy header card
        int headerCardX = leftPos + 14;
        int headerCardW = PW - 28;
        int headerCardH = 22;
        g.fill(headerCardX, y, headerCardX + headerCardW, y + headerCardH, MedievalColors.CARD_BG);
        g.fill(headerCardX, y, headerCardX + headerCardW, y + 1, MedievalColors.CARD_BORDER);
        g.fill(headerCardX, y + headerCardH - 1, headerCardX + headerCardW, y + headerCardH, MedievalColors.CARD_BORDER);
        g.fill(headerCardX, y, headerCardX + 1, y + headerCardH, MedievalColors.CARD_BORDER);
        g.fill(headerCardX + headerCardW - 1, y, headerCardX + headerCardW, y + headerCardH, MedievalColors.CARD_BORDER);

        String occText = I18n.name("gui.wandscape.hotel.guests", "Guests").getString()
                + ": " + currentOccupancy + " / " + maxOccupancy;
        g.drawString(font, occText, x + 4, y + 7, MedievalColors.ACCENT_GOLD);

        // Occupancy mini progress bar
        int barW = 80;
        int barH = 6;
        int barX = headerCardX + headerCardW - barW - 8;
        int barY = y + 8;
        float ratio = maxOccupancy > 0 ? (float) currentOccupancy / maxOccupancy : 0f;
        g.fill(barX, barY, barX + barW, barY + barH, MedievalColors.PROGRESS_BG);
        int fillW = (int) (barW * Math.min(1.0f, ratio));
        if (fillW > 0) {
            int fillColor = ratio >= 1.0f ? 0xFFEF5350 : 0xFF42A5F5;
            g.fill(barX, barY, barX + fillW, barY + barH, fillColor);
        }
        g.fill(barX, barY, barX + barW, barY + 1, MedievalColors.BORDER_GOLD_DARK);
        g.fill(barX, barY + barH - 1, barX + barW, barY + barH, MedievalColors.BORDER_GOLD_DARK);
        g.fill(barX, barY, barX + 1, barY + barH, MedievalColors.BORDER_GOLD_DARK);
        g.fill(barX + barW - 1, barY, barX + barW, barY + barH, MedievalColors.BORDER_GOLD_DARK);

        y += headerCardH + 8;
        com.wsteam.wandscape.foundation.ui.util.RenderUtil.drawHLineDecorative(g, leftPos + 16, y, PW - 32);
        y += 8;

        // Guest list
        if (guestNames.isEmpty()) {
            g.drawString(font, I18n.name("gui.wandscape.hotel.no_guests", "No guests checked in."),
                    x + 4, y + 4, MedievalColors.TEXT_MUTED);
        } else {
            for (int i = 0; i < guestNames.size(); i++) {
                int rowY = y;
                boolean hovered = mouseX >= leftPos + 14 && mouseX < leftPos + PW - 14
                        && mouseY >= rowY && mouseY < rowY + 16;
                g.fill(leftPos + 14, rowY, leftPos + PW - 14, rowY + 14, hovered ? 0x44283550 : 0x22121724);
                g.fill(leftPos + 14, rowY, leftPos + 16, rowY + 14, MedievalColors.ACCENT_GOLD);
                String line = (i + 1) + ". " + guestNames.get(i);
                g.drawString(font, line, x + 6, rowY + 3, hovered ? MedievalColors.ACCENT_GOLD : MedievalColors.TEXT_WARM_WHITE);
                y += 16;
                if (y > topPos + PH - 42) break; // overflow guard
            }
        }
    }
}
