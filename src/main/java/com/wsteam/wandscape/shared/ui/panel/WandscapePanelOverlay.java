package com.wsteam.wandscape.shared.ui.panel;

import java.util.Map;
import java.util.UUID;

import com.wsteam.wandscape.projection.client.BuildingDebugClientState;
import com.wsteam.wandscape.road.client.RoadPlacementOverlay;
import com.wsteam.wandscape.road.client.RoadPlacementState;
import com.wsteam.wandscape.shared.data.ElementType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Renders the Wandscape panel overlay on top of the game HUD.
 * Uses {@link RenderType#guiOverlay()} (NO_DEPTH_TEST) for fills
 * to cover the vanilla hotbar unconditionally.
 *
 * <p>Text is rendered with {@link Font.DisplayMode#SEE_THROUGH}
 * (also NO_DEPTH_TEST). An explicit {@code endBatch(RenderType)}
 * between fills and text guarantees correct draw order regardless
 * of HashMap iteration in MultiBufferSource.
 */
public final class WandscapePanelOverlay {

    private static final String TAG = "WandscapePanelOverlay";

    private static final int BAR_BG = 0xEE14161C;
    private static final int TEXT_WHITE = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFAAAAAA;
    private static final int TAB_INACTIVE_BG = 0xFF15181C;
    private static final int TAB_HOVER_BG = 0xFF282C34;
    private static final int TAB_ACTIVE_BG = 0xFF2B62C8;
    private static final int TAB_ACTIVE_BORDER = 0xFF4FA0FF;
    private static final int COMFORT_COLOR = 0xFF4CAF50;
    private static final int MAGIC_COLOR = 0xFF42A5F5;
    private static final int WONDER_COLOR = 0xFFC8A040;

    private static final String[] TAB_LABELS = { "Build", "Road", "Editor", "Stats" };
    private static final int TAB_W = WandscapePanelController.TAB_W;
    private static final int TAB_GAP = WandscapePanelController.TAB_GAP;
    private static final int TAB_COUNT = WandscapePanelController.TAB_COUNT;
    private static final int BOTTOM_BAR_H = WandscapePanelController.BOTTOM_BAR_HEIGHT;
    private static final int TOP_BAR_H = WandscapePanelController.TOP_BAR_HEIGHT;

    private static boolean registered = false;

    private WandscapePanelOverlay() {}

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.register(WandscapePanelOverlay.class);
        Log.info(TAG, "[Panel] Overlay registered");
    }

    @SubscribeEvent
    public static void onRenderGuiPost(RenderGuiEvent.Post event) {
        if (!WandscapePanelState.isPanelOpen()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.screen != null) return;

        GuiGraphics g = event.getGuiGraphics();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // Mouse position for hover effects (GuiGraphics doesn't carry mouse coords in overlay)
        double guiScale = mc.getWindow().getGuiScale();
        double mx = mc.mouseHandler.xpos() / guiScale;
        double my = mc.mouseHandler.ypos() / guiScale;

        // Building selection bar (above bottom bar when active)
        BuildingSelectionOverlay.render(g, mc.font, screenW, screenH, mx, my);

        // Road placement preset bar (when road mode is in BAR phase — cursor lifted)
        if (WandscapePanelState.isCursorLifted()
                && WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.ROAD_PROJECTION
                && RoadPlacementState.getRoadPhase() == RoadPlacementState.RoadPhase.BAR) {
            RoadPlacementOverlay.render(g, mc.font, screenW, screenH, mx, my);
        }

        renderFills(g, mc.font, screenW, screenH);

        // Force-flush fills before text so HashMap iteration order
        // in BufferSource can't put guiOverlay after text_see_through.
        g.bufferSource().endBatch(RenderType.guiOverlay());

        renderTexts(g, mc.font, screenW, screenH);
    }

    // ── Fills (guiOverlay — NO_DEPTH_TEST, covers hotbar) ──

    private static void renderFills(GuiGraphics g, Font font, int screenW, int screenH) {
        boolean lookingAtBuilding = BuildingDebugClientState.getCachedData() != null;

        // Top bar – hidden when debug overlay is showing building info
        if (!lookingAtBuilding) {
            g.fill(RenderType.guiOverlay(), 0, 0, screenW, TOP_BAR_H, 0, BAR_BG);
        }

        // Bottom bar
        int barY = screenH - BOTTOM_BAR_H;
        g.fill(RenderType.guiOverlay(), 0, barY, screenW, screenH, 0, BAR_BG);

        // Tabs
        int totalTabsW = TAB_COUNT * TAB_W + (TAB_COUNT - 1) * TAB_GAP;
        int tabStartX = (screenW - totalTabsW) / 2;
        int tabY = barY + 8;
        int tabH = BOTTOM_BAR_H - 16;

        WandscapePanelState.SubMode activeMode = WandscapePanelState.getActiveSubMode();
        int hoveredTab = getHoveredTab(screenW);

        for (int i = 0; i < TAB_COUNT; i++) {
            int tx = tabStartX + i * (TAB_W + TAB_GAP);
            boolean active = isTabActive(i, activeMode);
            boolean hovered = (i == hoveredTab);
            int bgColor = active ? TAB_ACTIVE_BG : (hovered ? TAB_HOVER_BG : TAB_INACTIVE_BG);

            g.fill(RenderType.guiOverlay(), tx, tabY, tx + TAB_W, tabY + tabH, 0, bgColor);
            if (active) {
                g.fill(RenderType.guiOverlay(), tx, tabY + tabH - 2, tx + TAB_W, tabY + tabH, 0,
                        TAB_ACTIVE_BORDER);
            }
        }
    }

    // ── Text (SEE_THROUGH — NO_DEPTH_TEST, always visible) ──

    private static void renderTexts(GuiGraphics g, Font font, int screenW, int screenH) {
        boolean lookingAtBuilding = BuildingDebugClientState.getCachedData() != null;

        // Top bar – hidden when debug overlay is showing building info
        if (!lookingAtBuilding) {
            int topY = (TOP_BAR_H - font.lineHeight) / 2;

            String colonyText;
            java.util.UUID cid = WandscapePanelState.getColonyId();
            if (cid != null) {
                colonyText = "Colony: " + cid.toString().substring(0, 8);
            } else {
                colonyText = "Colony: ---";
            }

            String comfortText = "Comfort: " + WandscapePanelState.getComfort();
            String magicText = "Magic: " + WandscapePanelState.getMagic();
            String wonderText = "Wonder: " + WandscapePanelState.getWonder();

            int x = 8;
            drawText(g, font, colonyText, x, topY, TEXT_DIM);
            x += font.width(colonyText) + 24;
            drawText(g, font, comfortText, x, topY, COMFORT_COLOR);
            x += font.width(comfortText) + 24;
            drawText(g, font, magicText, x, topY, MAGIC_COLOR);
            x += font.width(magicText) + 24;
            drawText(g, font, wonderText, x, topY, WONDER_COLOR);
        }

        // Stats content (center area when Stats tab is active)
        if (WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.STATS) {
            renderStatsContent(g, font, screenW, screenH);
        }
        // Bottom bar
        int barY = screenH - BOTTOM_BAR_H;
        int totalTabsW = TAB_COUNT * TAB_W + (TAB_COUNT - 1) * TAB_GAP;
        int tabStartX = (screenW - totalTabsW) / 2;
        int tabY = barY + 8;
        int tabH = BOTTOM_BAR_H - 16;

        WandscapePanelState.SubMode activeMode = WandscapePanelState.getActiveSubMode();

        for (int i = 0; i < TAB_COUNT; i++) {
            int tx = tabStartX + i * (TAB_W + TAB_GAP);
            boolean active = isTabActive(i, activeMode);
            int textColor = active ? TEXT_WHITE : TEXT_DIM;
            drawCenteredText(g, font, TAB_LABELS[i],
                    tx + TAB_W / 2, tabY + (tabH - font.lineHeight) / 2, textColor);
        }

    }

    // ── Stats content ──

    private static void renderStatsContent(GuiGraphics g, Font font, int screenW, int screenH) {
        var stats = WandscapePanelState.getStatsSummary();
        int leftX = 12;
        int y = TOP_BAR_H + 8;
        int lineH = font.lineHeight + 3;

        if (stats == null || stats.snapshotCount() == 0) {
            drawText(g, font, "No statistics available yet. Data will appear after the next daily settlement.",
                    leftX, y, TEXT_DIM);
            return;
        }

        // Header: day
        String header = "=== Colony Statistics ===  Day " + stats.currentDay();
        drawText(g, font, header, leftX, y, TEXT_WHITE);
        y += lineH + 4;

        // Maintenance line
        String maint = "Maintenance:  " + stats.buildingsPaid() + " paid  |  "
                + stats.buildingsShutdown() + " shutdown  |  "
                + stats.buildingsRestarted() + " restarted";
        drawText(g, font, maint, leftX, y, TEXT_DIM);
        y += lineH;

        // Tourism line
        String tourist = "Tourists:     " + stats.touristsArrived() + " arrived  |  "
                + stats.touristsDeparted() + " departed  |  Ø " + stats.avgSatisfaction() + "% satisfaction";
        drawText(g, font, tourist, leftX, y, TEXT_DIM);
        y += lineH + 4;

        // Element consumption (3 per row)
        if (!stats.totalElementsConsumed().isEmpty()) {
            drawText(g, font, "Elements consumed (30d):", leftX, y, TEXT_WHITE);
            y += lineH;

            var elements = stats.totalElementsConsumed();
            ElementType[] types = ElementType.values();
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < types.length; i++) {
                long amount = elements.getOrDefault(types[i], 0L);
                if (i > 0 && i % 3 == 0) {
                    drawText(g, font, line.toString(), leftX + 8, y, TEXT_DIM);
                    line = new StringBuilder();
                    y += lineH;
                }
                if (line.length() > 0) line.append("    ");
                line.append(String.format("%-8s: %d", types[i].getId(), amount));
            }
            if (!line.isEmpty()) {
                drawText(g, font, line.toString(), leftX + 8, y, TEXT_DIM);
                y += lineH;
            }
        }

        // Coverage info
        y += 4;
        String coverage = "Based on " + stats.snapshotCount() + " day(s) of data (max 30)";
        drawText(g, font, coverage, leftX, y, TEXT_DIM);
    }

    // ── Text helpers (SEE_THROUGH = NO_DEPTH_TEST) ──

    private static void drawText(GuiGraphics g, Font font, String text, float x, float y, int color) {
        font.drawInBatch(text, x, y, color, false,
                g.pose().last().pose(), g.bufferSource(),
                Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
    }

    private static void drawCenteredText(GuiGraphics g, Font font, String text, int x, int y, int color) {
        drawText(g, font, text, x - font.width(text) / 2f, y, color);
    }

    // ── Helpers ──

    private static int getHoveredTab(int screenW) {
        if (!WandscapePanelState.isCursorLifted()) return -1;
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        double mx = Minecraft.getInstance().mouseHandler.xpos() / guiScale;
        return WandscapePanelController.getTabAt(mx, screenW);
    }

    private static boolean isTabActive(int tabIndex, WandscapePanelState.SubMode activeMode) {
        return switch (tabIndex) {
            case 0 -> activeMode == WandscapePanelState.SubMode.BUILD_PROJECTION;
            case 1 -> activeMode == WandscapePanelState.SubMode.ROAD_PROJECTION;
            case 2 -> activeMode == WandscapePanelState.SubMode.BUILD_EDITOR;
            case 3 -> activeMode == WandscapePanelState.SubMode.STATS;
            default -> false;
        };
    }
}
