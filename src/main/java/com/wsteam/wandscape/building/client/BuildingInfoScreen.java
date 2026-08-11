package com.wsteam.wandscape.building.client;

import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.building.network.BuildingInfoPacket;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.shared.ui.theme.WandscapeTheme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
/**
 * Generic right-click info panel for tourist-building categories without a
 * dedicated screen (service non-hotel / relax / decoration / atm). Panel size
 * matches the town-hall UI (300×230).
 */
public class BuildingInfoScreen extends MedievalScreen {

    private static final String TAG = "BuildingInfoScreen";
    private static final int PW = 300;
    private static final int PH = 230;
    private static final int ICON_SIZE = 16;
    private static final int ROW_H = 18;

    private final String buildingTypeId;
    private final String category;
    private final Map<String, Integer> elementOutput;
    private final int energyPerUse;
    private final int energyRestore;
    private final int interactionDurationTicks;
    private final String creator;

    public BuildingInfoScreen(BuildingInfoPacket packet) {
        super(Component.literal("Info"), PW, PH);
        setTitleBar(I18n.name("building.wandscape." + packet.buildingTypeId(), packet.buildingTypeId()));
        this.showCloseButton = true;
        this.buildingTypeId = packet.buildingTypeId();
        this.category = packet.category();
        this.elementOutput = packet.elementOutput();
        this.energyPerUse = packet.energyPerUse();
        this.energyRestore = packet.energyRestore();
        this.interactionDurationTicks = packet.interactionDurationTicks();
        this.creator = packet.creator();
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54, topPos + PH - 22, 46, 16,
                I18n.name("gui.wandscape.common.close", "Close"), this::onClose));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        var font = Minecraft.getInstance().font;
        int x = leftPos + 16;
        int y = topPos + headerHeight + 12;

        switch (category) {
            case "service" -> renderService(g, font, x, y);
            case "relax" -> renderRelax(g, font, x, y);
            case "decoration" -> renderIntro(g, font, x, y, "gui.wandscape.info.decoration_intro");
            case "atm" -> renderIntro(g, font, x, y, "gui.wandscape.info.atm_intro");
            default -> Log.warn(TAG, "Unexpected category '{}' for building {}", category, buildingTypeId);
        }

        if (creator != null && !creator.isBlank()) {
            String creatorText = I18n.name("gui.wandscape.common.creator_label", "Creator").getString()
                    + ": " + creator;
            g.drawString(font, creatorText, leftPos + 16, topPos + PH - 26, MedievalColors.TEXT_DIM);
        }
    }

    private void renderService(GuiGraphics g, Font font, int x, int y) {
        g.drawString(font, i18n("gui.wandscape.info.element_output", "Element Output"),
                x, y, MedievalColors.ACCENT_GOLD);
        y += 12;

        for (ElementType type : ElementType.values()) {
            Integer amount = elementOutput.get(type.getId());
            if (amount == null || amount <= 0) continue;

            // Same element icon texture + tint as the warehouse panel
            WandscapeTheme.drawIcon(g, WandscapeTheme.elementIcon(type.getId()),
                    x, y + 1, ICON_SIZE, ICON_SIZE, WandscapeTheme.elementColor(type.getId()));

            Component name = I18n.name("element.wandscape." + type.getId(), capitalize(type.getId()));
            g.drawString(font, name, x + ICON_SIZE + 4, y + 4, MedievalColors.TEXT_WARM_WHITE);

            String amountStr = String.valueOf(amount);
            int amountWidth = font.width(amountStr);
            g.drawString(font, amountStr, leftPos + PW - 32 - amountWidth, y + 4,
                    MedievalColors.TEXT_WARM_WHITE);

            y += ROW_H;
            if (y > topPos + PH - 58) break; // keep room for the two stat lines + creator
        }

        y += 4;
        drawInfoLine(g, font, x, y, i18n("gui.wandscape.info.energy_cost", "Energy Cost"),
                String.valueOf(energyPerUse));
        y += 12;
        drawInfoLine(g, font, x, y, i18n("gui.wandscape.info.duration", "Duration"),
                formatDuration(interactionDurationTicks));
    }

    private void renderRelax(GuiGraphics g, Font font, int x, int y) {
        drawInfoLine(g, font, x, y, i18n("gui.wandscape.info.energy_restore", "Energy Restore"),
                String.valueOf(energyRestore));
        y += 12;
        drawInfoLine(g, font, x, y, i18n("gui.wandscape.info.duration", "Duration"),
                formatDuration(interactionDurationTicks));
    }

    private void renderIntro(GuiGraphics g, Font font, int x, int y, String key) {
        String text = I18n.name(key, "").getString();
        List<FormattedCharSequence> lines = font.split(Component.literal(text), PW - 32);
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, x, y, MedievalColors.TEXT_WARM_WHITE);
            y += 12;
            if (y > topPos + PH - 40) break;
        }
    }

    private void drawInfoLine(GuiGraphics g, Font font, int x, int y, String label, String value) {
        g.drawString(font, label, x, y, MedievalColors.TEXT_WARM_WHITE);
        g.drawString(font, value, x + 140, y, MedievalColors.TEXT_MUTED);
    }

    private static String i18n(String key, String fallback, Object... args) {
        return I18n.name(key, fallback, args).getString();
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String formatDuration(int ticks) {
        if (ticks <= 0) return "—";
        return String.format("%.1fs", ticks / 20.0);
    }
}
