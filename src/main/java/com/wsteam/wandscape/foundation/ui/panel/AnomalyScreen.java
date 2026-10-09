package com.wsteam.wandscape.foundation.ui.panel;

import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.foundation.ui.component.MedievalScreen;
import com.wsteam.wandscape.foundation.ui.theme.MedievalColors;
import com.wsteam.wandscape.foundation.ui.util.RenderUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Screen listing buildings still under construction.
 * 建筑不再因损坏或手动关闭而停摆，异常报告只保留"建造中"类别（从未完工的建筑，
 * 带等待材料/建造中阶段标识）。
 * Uses {@link MedievalScreen} MINIMAL theme with {@link MedievalColors}.
 * Opened by clicking the warning icon in the V-panel sidebar.
 */
public class AnomalyScreen extends MedievalScreen {

    private static final int PANEL_W = 300;
    private static final int PANEL_H = 230;
    private static final int ROW_H = 26;
    private static final int CONTENT_PAD = 10;

    /** {@code buildingTypeId} 是建筑类型 id（不是显示名），行内按本地语言取名字。 */
    private record AnomalyEntry(UUID buildingId, String buildingTypeId, boolean started) {}

    private List<AnomalyEntry> entries = List.of();
    private int scrollOffset;

    public AnomalyScreen() {
        super(Component.literal("Anomaly Report"), PANEL_W, PANEL_H);
        setTitleBar(I18n.name("gui.wandscape.anomaly.title", "异常报告"));
        this.showCloseButton = true;
        this.showHelpButton = true;
        this.helpDocumentPath = "anomaly_guide";
    }

    @Override
    protected void init() {
        super.init();
        rebuildEntries();
    }

    private void rebuildEntries() {
        List<AnomalyEntry> list = new ArrayList<>();
        var ucIds = WandscapePanelState.getUnderConstructionBuildingIds();
        var ucNames = WandscapePanelState.getUnderConstructionBuildingNames();
        var ucStarted = WandscapePanelState.getUnderConstructionStarted();
        int n = Math.min(ucIds.size(), Math.min(ucNames.size(), ucStarted.size()));
        for (int i = 0; i < n; i++) {
            list.add(new AnomalyEntry(ucIds.get(i), ucNames.get(i), ucStarted.get(i)));
        }
        this.entries = list;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Base handles background + MINIMAL header + close button + widget loop
        super.render(g, mouseX, mouseY, partialTick);

        int cx = leftPos + CONTENT_PAD;
        int cy = topPos + headerHeight + 6;
        int cw = panelWidth - CONTENT_PAD * 2;

        // Header row
        g.drawString(font, I18n.name("gui.wandscape.anomaly.col_building", "建筑名称").getString(), cx, cy, MedievalColors.ACCENT_GOLD, false);
        g.drawString(font, I18n.name("gui.wandscape.anomaly.col_status", "状态").getString(), cx + cw - 125, cy, MedievalColors.ACCENT_GOLD, false);

        int sepY = cy + 12;
        RenderUtil.drawHLineDecorative(g, cx, sepY, cw);

        // List area
        int listY = sepY + 6;
        int listH = panelHeight - headerHeight - 16 - 16;

        g.enableScissor(cx, listY, cx + cw, listY + listH);

        for (int i = 0; i < entries.size(); i++) {
            int rowY = listY + i * ROW_H - scrollOffset;
            if (rowY + ROW_H < listY || rowY > listY + listH) continue;

            var entry = entries.get(i);
            boolean hovered = mouseX >= cx && mouseX < cx + cw
                    && mouseY >= rowY && mouseY < rowY + ROW_H;

            if (hovered) {
                g.fill(cx, rowY, cx + cw, rowY + ROW_H - 2, 0x33364560);
                g.fill(cx, rowY, cx + 2, rowY + ROW_H - 2, MedievalColors.ACCENT_GOLD);
            } else {
                g.fill(cx, rowY, cx + cw, rowY + ROW_H - 2, 0x22121724);
            }

            // Building name
            g.drawString(font, I18n.buildingName(entry.buildingTypeId(), entry.buildingTypeId()).getString(),
                    cx + 6, rowY + (ROW_H - 9) / 2,
                    hovered ? MedievalColors.ACCENT_GOLD : MedievalColors.TEXT_WARM_WHITE, false);

            // Status badge (建造中 / 等待材料)
            int badgeColor = entry.started() ? 0xFF42A5F5 : 0xFFFFA726;
            int badgeBg = entry.started() ? 0x3342A5F5 : 0x33FFA726;
            String badgeText = entry.started()
                    ? I18n.name("gui.wandscape.anomaly.badge_under_construction", "建造中").getString()
                    : I18n.name("gui.wandscape.anomaly.badge_waiting_materials", "等待材料").getString();

            int badgeW = font.width(badgeText) + 8;
            int badgeX = cx + cw - 125;
            int badgeY = rowY + (ROW_H - 14) / 2;
            g.fill(badgeX, badgeY, badgeX + badgeW, badgeY + 14, badgeBg);
            g.drawString(font, badgeText, badgeX + 4, badgeY + 3, badgeColor, false);
        }

        g.disableScissor();

        // Scrollbar
        int totalH = entries.size() * ROW_H;
        int maxScroll = Math.max(0, totalH - listH);
        if (maxScroll > 0) {
            RenderUtil.drawScrollbar(g, cx + cw - 4, listY, 4, listH, totalH, scrollOffset);
        }

        // Summary footer
        String summary = I18n.name("gui.wandscape.anomaly.summary", "建造中: %s 项",
                WandscapePanelState.getUnderConstructionCount()).getString();
        g.drawString(font, summary, cx, listY + listH + 4, MedievalColors.TEXT_MUTED, false);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int totalH = entries.size() * ROW_H;
        int listH = panelHeight - headerHeight - 16 - 14;
        int maxScroll = Math.max(0, totalH - listH);
        scrollOffset = (int) Math.clamp(scrollOffset - scrollY * ROW_H * 2, 0, maxScroll);
        return true;
    }
}
