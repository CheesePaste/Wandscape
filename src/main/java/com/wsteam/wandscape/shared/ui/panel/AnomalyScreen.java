package com.wsteam.wandscape.shared.ui.panel;

import com.wsteam.wandscape.projection.network.BuildingActionPacket;
import com.wsteam.wandscape.shared.ui.component.MedievalScreen;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.shared.ui.util.RenderUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Screen showing all building anomalies (shutdown/broken) with action buttons.
 * Uses {@link MedievalScreen} MINIMAL theme with {@link MedievalColors}.
 * Opened by clicking the warning icon in the V-panel sidebar.
 */
public class AnomalyScreen extends MedievalScreen {

    private static final int PANEL_W = 300;
    private static final int PANEL_H = 230;
    private static final int ROW_H = 26;
    private static final int CONTENT_PAD = 10;
    private static final int BTN_W = 50;
    private static final int BTN_H = 18;

    private record AnomalyEntry(UUID buildingId, String buildingName, Type type, boolean started) {
        enum Type { BROKEN, SHUTDOWN, UNDER_CONSTRUCTION }
    }

    private List<AnomalyEntry> entries = List.of();
    private int scrollOffset;

    public AnomalyScreen() {
        super(Component.literal("Anomaly Report"), PANEL_W, PANEL_H);
        setTitleBar(Component.literal("异常报告"));
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
        var shutdownIds = WandscapePanelState.getShutdownBuildingIds();
        var shutdownNames = WandscapePanelState.getShutdownBuildingNames();
        int n = Math.min(shutdownIds.size(), shutdownNames.size());
        for (int i = 0; i < n; i++) {
            list.add(new AnomalyEntry(shutdownIds.get(i), shutdownNames.get(i),
                    AnomalyEntry.Type.SHUTDOWN, false));
        }
        var brokenIds = WandscapePanelState.getBrokenBuildingIds();
        var brokenNames = WandscapePanelState.getBrokenBuildingNames();
        n = Math.min(brokenIds.size(), brokenNames.size());
        for (int i = 0; i < n; i++) {
            list.add(new AnomalyEntry(brokenIds.get(i), brokenNames.get(i),
                    AnomalyEntry.Type.BROKEN, false));
        }
        // Under-construction buildings are NOT anomalies but are listed so the
        // player sees they're still being built (等待材料/建造中) instead of mistaking
        // them for damaged.
        var ucIds = WandscapePanelState.getUnderConstructionBuildingIds();
        var ucNames = WandscapePanelState.getUnderConstructionBuildingNames();
        var ucStarted = WandscapePanelState.getUnderConstructionStarted();
        n = Math.min(ucIds.size(), Math.min(ucNames.size(), ucStarted.size()));
        for (int i = 0; i < n; i++) {
            list.add(new AnomalyEntry(ucIds.get(i), ucNames.get(i),
                    AnomalyEntry.Type.UNDER_CONSTRUCTION, ucStarted.get(i)));
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
        g.drawString(font, "建筑名称", cx, cy, MedievalColors.BORDER_GOLD, false);
        g.drawString(font, "状态", cx + cw - 125, cy, MedievalColors.BORDER_GOLD, false);
        g.drawString(font, "操作", cx + cw - BTN_W, cy, MedievalColors.BORDER_GOLD, false);

        int sepY = cy + 12;
        g.fill(cx, sepY, cx + cw, sepY + 1, MedievalColors.BORDER_GOLD_DARK);

        // List area
        int listY = sepY + 4;
        int listH = panelHeight - headerHeight - 16 - 14;

        g.enableScissor(cx, listY, cx + cw, listY + listH);

        for (int i = 0; i < entries.size(); i++) {
            int rowY = listY + i * ROW_H - scrollOffset;
            if (rowY + ROW_H < listY || rowY > listY + listH) continue;

            var entry = entries.get(i);
            boolean hovered = mouseX >= cx && mouseX < cx + cw
                    && mouseY >= rowY && mouseY < rowY + ROW_H;

            if (hovered) {
                g.fill(cx, rowY, cx + cw, rowY + ROW_H, MedievalColors.BUTTON_BG_HOVER);
            }

            // Building name
            g.drawString(font, entry.buildingName, cx + 4, rowY + (ROW_H - 9) / 2,
                    MedievalColors.TEXT_WARM_WHITE, false);

            // Type badge
            boolean isUnderConstruction = entry.type == AnomalyEntry.Type.UNDER_CONSTRUCTION;
            boolean isBroken = entry.type == AnomalyEntry.Type.BROKEN;
            int badgeColor;
            String badgeText;
            if (isUnderConstruction) {
                badgeColor = MedievalColors.INFO_BLUE;
                badgeText = entry.started() ? "建造中" : "等待材料";
            } else if (isBroken) {
                badgeColor = MedievalColors.DANGER_RED;
                badgeText = "损坏";
            } else {
                badgeColor = MedievalColors.BORDER_GOLD_DARK;
                badgeText = "关闭";
            }
            int badgeW = font.width(badgeText) + 8;
            int badgeX = cx + cw - 125;
            int badgeY = rowY + (ROW_H - 14) / 2;
            g.fill(badgeX, badgeY, badgeX + badgeW, badgeY + 14, badgeColor | 0xCC000000);
            g.drawString(font, badgeText, badgeX + 4, badgeY + 3, MedievalColors.TEXT_WARM_WHITE, false);

            // Action button — under-construction buildings have no repair/restart action
            if (!isUnderConstruction) {
                String btnText = isBroken ? "修复" : "营业";
                int btnX = cx + cw - BTN_W;
                int btnY = rowY + (ROW_H - BTN_H) / 2;
                int btnColor = isBroken ? MedievalColors.SUCCESS_GREEN : MedievalColors.INFO_BLUE;
                boolean btnHovered = mouseX >= btnX && mouseX < btnX + BTN_W
                        && mouseY >= btnY && mouseY < btnY + BTN_H;
                int fillColor = btnHovered ? btnColor : (btnColor & 0x00FFFFFF) | 0xAA000000;
                g.fill(btnX, btnY, btnX + BTN_W, btnY + BTN_H, fillColor);
                g.drawString(font, btnText, btnX + (BTN_W - font.width(btnText)) / 2,
                        btnY + (BTN_H - 9) / 2, MedievalColors.TEXT_WARM_WHITE, false);
            }
        }

        g.disableScissor();

        // Scrollbar
        int totalH = entries.size() * ROW_H;
        int maxScroll = Math.max(0, totalH - listH);
        if (maxScroll > 0) {
            RenderUtil.drawScrollbar(g, cx + cw - 4, listY, 4, listH, totalH, scrollOffset);
        }

        // Summary footer — 建造中 buildings are listed but are not anomalies
        String summary = "共 " + entries.size() + " 项  |  关闭: " + WandscapePanelState.getShutdownCount()
                + "  |  损坏: " + WandscapePanelState.getBrokenCount()
                + "  |  建造中: " + WandscapePanelState.getUnderConstructionCount();
        g.drawString(font, summary, cx, listY + listH + 4, MedievalColors.TEXT_MUTED, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0) return false;

        int cx = leftPos + CONTENT_PAD;
        int cy = topPos + headerHeight + 6;
        int cw = panelWidth - CONTENT_PAD * 2;
        int sepY = cy + 12;
        int listY = sepY + 4;
        int listH = panelHeight - headerHeight - 16 - 14;

        for (int i = 0; i < entries.size(); i++) {
            int rowY = listY + i * ROW_H - scrollOffset;
            if (rowY + ROW_H < listY || rowY > listY + listH) continue;

            var entry = entries.get(i);
            if (entry.type == AnomalyEntry.Type.UNDER_CONSTRUCTION) continue;
            boolean isBroken = entry.type == AnomalyEntry.Type.BROKEN;
            int btnX = cx + cw - BTN_W;
            int btnY = rowY + (ROW_H - BTN_H) / 2;

            if (mouseX >= btnX && mouseX < btnX + BTN_W
                    && mouseY >= btnY && mouseY < btnY + BTN_H) {
                String action = isBroken ? "repair" : "restart";
                PacketDistributor.sendToServer(new BuildingActionPacket(entry.buildingId, action));
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal(isBroken ? "§a已发送修复指令" : "§a已发送营业指令"), true);
                }
                onClose();
                return true;
            }
        }

        return false;
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
