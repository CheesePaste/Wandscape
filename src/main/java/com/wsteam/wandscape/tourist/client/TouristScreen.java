package com.wsteam.wandscape.tourist.client;

import java.util.List;

import com.wsteam.wandscape.shared.ui.component.MedievalButton;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.tourist.network.TouristDataPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
/**
 * Tourist info screen.
 *
 * <p>Shows stats (energy, satisfaction, level) and a list of recent
 * attraction visits with outcomes.
 */
public class TouristScreen extends MedievalScreen {

    private static final int PW = 280;
    private static final int PH = 248;

    private final int entityId;
    private String touristName;
    private int energy;
    private int satisfaction;
    private int level;
    private List<TouristDataPacket.VisitEntry> recentVisits;

    public TouristScreen(TouristDataPacket packet) {
        super(Component.literal("Tourist Info"), PW, PH);
        this.entityId = packet.entityId();
        apply(packet);
    }

    public void apply(TouristDataPacket packet) {
        this.touristName = packet.touristName();
        this.energy = packet.energy();
        this.satisfaction = packet.satisfaction();
        this.level = packet.level();
        this.recentVisits = packet.recentVisits();
        setTitleBar(touristName);
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(new MedievalButton(
                leftPos + PW - 54, topPos + PH - 22, 46, 16,
                Component.literal("关闭"), () -> Minecraft.getInstance().setScreen(null)));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        var font = Minecraft.getInstance().font;
        int leftCol = leftPos + 12;
        int contentTop = topPos + headerHeight + 6;

        // ── Stats ──
        g.drawString(font, "状态", leftCol, contentTop, MedievalColors.ACCENT_GOLD);
        int sepY = contentTop + 10;
        g.fill(leftCol, sepY, leftCol + 50, sepY + 1, MedievalColors.BORDER_GOLD_DARK);

        int statY = sepY + 6;
        int labelW = 32;
        int barW = 100;

        // Energy bar
        g.drawString(font, "精力:", leftCol, statY, MedievalColors.TEXT_WARM_WHITE);
        drawStatBar(g, leftCol + labelW, statY, barW, 10,
                Math.clamp((float) energy / 200f, 0f, 1f),
                energy + "/200",
                MedievalColors.SUCCESS_GREEN);
        statY += 12;

        // Satisfaction bar
        g.drawString(font, "满意:", leftCol, statY, MedievalColors.TEXT_WARM_WHITE);
        drawStatBar(g, leftCol + labelW, statY, barW, 10,
                Math.clamp((float) satisfaction / 100f, 0f, 1f),
                satisfaction + "%",
                MedievalColors.ACCENT_GOLD);
        statY += 12;

        // Level text
        g.drawString(font, "等级:", leftCol, statY, MedievalColors.TEXT_WARM_WHITE);
        g.drawString(font, String.valueOf(level), leftCol + labelW, statY, MedievalColors.TEXT_MUTED);

        // ── Visits ──
        int visitsTop = contentTop + 74;
        g.drawString(font, "行程", leftCol, visitsTop, MedievalColors.ACCENT_GOLD);
        g.fill(leftCol, visitsTop + 10, leftPos + PW - 12, visitsTop + 11, MedievalColors.BORDER_GOLD_DARK);

        if (recentVisits.isEmpty()) {
            g.drawString(font, "暂无行程记录", leftCol, visitsTop + 22, MedievalColors.TEXT_MUTED);
        } else {
            int visitY = visitsTop + 17;
            int maxLines = (topPos + PH - 24 - visitY) / 10;
            int count = 0;
            for (var visit : recentVisits) {
                if (count >= maxLines) break;

                String outcomes = formatDelta(visit.satDelta()) + "满意"
                        + ", " + formatDelta(visit.energyDelta()) + "精力";
                String line = visit.buildingName() + ": " + visit.whatHappened() + " (" + outcomes + ")";

                int color = visit.satDelta() > 0 ? MedievalColors.SUCCESS_GREEN
                        : visit.satDelta() < 0 ? MedievalColors.DANGER_RED
                        : MedievalColors.TEXT_MUTED;

                g.drawString(font, line, leftCol, visitY, color);
                visitY += 10;
                count++;
            }
        }
    }

    private static String formatDelta(int delta) {
        return delta >= 0 ? "+" + delta : String.valueOf(delta);
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
    public boolean isPauseScreen() {
        return false;
    }
}
