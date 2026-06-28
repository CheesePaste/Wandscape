package com.wsteam.wandscape.building.client;

import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
/**
 * Hotel GUI — view occupancy and checked-in guest names.
 */
public class HotelScreen extends MedievalScreen {

    private static final int PW = 240;
    private static final int PH = 180;

    private final BlockPos buildingPos;
    private final UUID colonyId;
    private final UUID buildingId;
    private final int maxOccupancy;
    private final int currentOccupancy;
    private final List<String> guestNames;

    public HotelScreen(BlockPos buildingPos, UUID colonyId, UUID buildingId,
                       int maxOccupancy, int currentOccupancy, List<String> guestNames) {
        super(Component.literal("Hotel"), PW, PH);
        setTitleBar("Hotel / Inn");
        this.buildingPos = buildingPos;
        this.colonyId = colonyId;
        this.buildingId = buildingId;
        this.maxOccupancy = maxOccupancy;
        this.currentOccupancy = currentOccupancy;
        this.guestNames = guestNames;
    }

    @Override
    protected void init() {
        super.init();

        // Close button
        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54, topPos + PH - 22, 46, 16,
                Component.literal("Close"), this::onClose));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        var font = Minecraft.getInstance().font;
        int x = leftPos + 16;
        int y = topPos + headerHeight + 12;

        // Occupancy header
        String occText = "Guests: " + currentOccupancy + " / " + maxOccupancy;
        g.drawString(font, occText, x, y, MedievalColors.ACCENT_GOLD);
        y += 16;

        // Divider
        g.drawString(font, "───────────────", x, y, MedievalColors.TEXT_DIM);
        y += 12;

        // Guest list
        if (guestNames.isEmpty()) {
            g.drawString(font, "No guests checked in.", x, y, MedievalColors.TEXT_MUTED);
        } else {
            for (int i = 0; i < guestNames.size(); i++) {
                String line = (i + 1) + ". " + guestNames.get(i);
                g.drawString(font, line, x, y, MedievalColors.TEXT_WARM_WHITE);
                y += 12;
                if (y > topPos + PH - 30) break; // overflow guard
            }
        }

        // Building info at bottom
        String bldText = "Building: " + buildingId.toString().substring(0, 8);
        g.drawString(font, bldText, leftPos + 16, topPos + PH - 28, MedievalColors.TEXT_DIM);
    }
}
