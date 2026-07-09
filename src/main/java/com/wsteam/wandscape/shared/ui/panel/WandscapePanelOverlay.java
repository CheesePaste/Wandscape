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

        // 1. Top-Left Colony Info Widget (Hidden if looking at building debug info)
        if (!lookingAtBuilding) {
            java.util.UUID cid = WandscapePanelState.getColonyId();
            if (cid != null) {
                // Determine text sizes
                int lvl = WandscapePanelState.getColonyLevel();
                String name = WandscapePanelState.getColonyName();
                if (name == null || name.isEmpty()) name = cid.toString().substring(0, 8);
                String colonyText = name + " Lv." + lvl;

                int cx = 10, cy = 10;
                int iconS = 12;
                int gap = 8;
                
                int comfortW = font.width(String.valueOf(WandscapePanelState.getComfort()));
                int magicW = font.width(String.valueOf(WandscapePanelState.getMagic()));
                int wonderW = font.width(String.valueOf(WandscapePanelState.getWonder()));
                
                int totalW = 4 + iconS + 4 + font.width(colonyText) + gap 
                           + iconS + 2 + comfortW + gap 
                           + iconS + 2 + magicW + gap 
                           + iconS + 2 + wonderW + 4;

                int boxH = 18;
                com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.drawRtsBox(g, cx, cy, totalW, boxH, false, false);
            }
        }

        // 2. Bottom Main Command Bar (Tabs)
        int barY = screenH - BOTTOM_BAR_H;
        int totalTabsW = TAB_COUNT * TAB_W + (TAB_COUNT - 1) * TAB_GAP;
        int tabStartX = (screenW - totalTabsW) / 2;

        WandscapePanelState.SubMode activeMode = WandscapePanelState.getActiveSubMode();
        int hoveredTab = getHoveredTab(screenW, screenH);

        for (int i = 0; i < TAB_COUNT; i++) {
            int tx = tabStartX + i * (TAB_W + TAB_GAP);
            boolean active = isTabActive(i, activeMode);
            boolean hovered = (i == hoveredTab);
            
            com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.drawRtsBox(g, tx, barY, TAB_W, TAB_W, active, hovered);
        }
    }

    // ── Text (SEE_THROUGH — NO_DEPTH_TEST, always visible) ──

    private static void renderTexts(GuiGraphics g, Font font, int screenW, int screenH) {
        boolean lookingAtBuilding = BuildingDebugClientState.getCachedData() != null;

        // 1. Top-Left Colony Info Text & Icons
        if (!lookingAtBuilding) {
            java.util.UUID cid = WandscapePanelState.getColonyId();
            if (cid != null) {
                int lvl = WandscapePanelState.getColonyLevel();
                String name = WandscapePanelState.getColonyName();
                if (name == null || name.isEmpty()) name = cid.toString().substring(0, 8);
                String colonyText = name + " Lv." + lvl;

                int x = 14;
                int y = 11;
                int iconS = 12;
                int textY = y + 2;

                com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.drawIcon(g, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.ICON_COLONY, x, y, iconS, iconS, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_NORMAL);
                x += iconS + 4;
                drawText(g, font, colonyText, x, textY, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_NORMAL);
                x += font.width(colonyText) + 8;
                
                com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.drawIcon(g, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.ICON_COMFORT, x, y, iconS, iconS, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_COMFORT);
                x += iconS + 2;
                String comfortStr = String.valueOf(WandscapePanelState.getComfort());
                drawText(g, font, comfortStr, x, textY, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_NORMAL);
                x += font.width(comfortStr) + 8;

                com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.drawIcon(g, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.ICON_MAGIC, x, y, iconS, iconS, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_MAGIC);
                x += iconS + 2;
                String magicStr = String.valueOf(WandscapePanelState.getMagic());
                drawText(g, font, magicStr, x, textY, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_NORMAL);
                x += font.width(magicStr) + 8;

                com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.drawIcon(g, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.ICON_WONDER, x, y, iconS, iconS, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_WONDER);
                x += iconS + 2;
                drawText(g, font, String.valueOf(WandscapePanelState.getWonder()), x, textY, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_NORMAL);
            }
        }

        // 2. Stats content (if active)
        if (WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.STATS) {
            renderStatsContent(g, font, screenW, screenH);
        }

        // 3. Bottom Main Command Bar Icons & Status Text
        int barY = screenH - BOTTOM_BAR_H;
        int totalTabsW = TAB_COUNT * TAB_W + (TAB_COUNT - 1) * TAB_GAP;
        int tabStartX = (screenW - totalTabsW) / 2;

        WandscapePanelState.SubMode activeMode = WandscapePanelState.getActiveSubMode();
        int hoveredTab = getHoveredTab(screenW, screenH);

        net.minecraft.resources.ResourceLocation[] ICONS = {
            com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.ICON_TAB_BUILD,
            com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.ICON_TAB_ROAD,
            com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.ICON_TAB_EDITOR,
            com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.ICON_TAB_STATS
        };

        for (int i = 0; i < TAB_COUNT; i++) {
            int tx = tabStartX + i * (TAB_W + TAB_GAP);
            boolean active = isTabActive(i, activeMode);
            boolean hovered = (i == hoveredTab);
            
            int iconColor = active ? com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_ACTIVE : (hovered ? com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_NORMAL : com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_DIM);
            // Draw 16x16 icon centered in 24x24 box
            int iconS = 16;
            int offset = (TAB_W - iconS) / 2;
            com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.drawIcon(g, ICONS[i], tx + offset, barY + offset, iconS, iconS, iconColor);
        }

        // Status Bar (Mode Name / Help Text) below tabs
        if (activeMode != WandscapePanelState.SubMode.NONE || hoveredTab >= 0) {
            int idx = hoveredTab >= 0 ? hoveredTab : getTabIndex(activeMode);
            if (idx >= 0) {
                String helpText = switch (idx) {
                    case 0 -> "Mode: Build  (LMB: Place/Select, RMB: Cancel)";
                    case 1 -> "Mode: Road  (LMB: Point, RMB: Confirm)";
                    case 2 -> "Mode: Editor";
                    case 3 -> "Mode: Stats  (Overview)";
                    default -> "";
                };
                drawCenteredText(g, font, helpText, screenW / 2, barY + TAB_W + 4, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_ACTIVE);
            }
        }
    }

    // ── Stats content ──

    private static void renderStatsContent(GuiGraphics g, Font font, int screenW, int screenH) {
        var stats = WandscapePanelState.getStatsSummary();
        
        // Draw a slim floating window on the left side
        int boxW = 280;
        int boxH = 140;
        int leftX = 10;
        int topY = 40;
        com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.drawRtsBox(g, leftX, topY, boxW, boxH, true, false);

        int textX = leftX + 10;
        int y = topY + 10;
        int lineH = font.lineHeight + 3;

        if (stats == null || stats.snapshotCount() == 0) {
            drawText(g, font, "No statistics available yet.", textX, y, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_DIM);
            return;
        }

        String header = "Colony Statistics  |  Day " + stats.currentDay();
        drawText(g, font, header, textX, y, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_NORMAL);
        y += lineH + 4;

        String maint = "Maintenance: " + stats.buildingsPaid() + " paid | " + stats.buildingsShutdown() + " shut";
        drawText(g, font, maint, textX, y, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_DIM);
        y += lineH;

        String tourist = "Tourists: " + stats.touristsArrived() + " in | " + stats.touristsDeparted() + " out | " + stats.avgSatisfaction() + "% ok";
        drawText(g, font, tourist, textX, y, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_DIM);
        y += lineH + 4;

        if (!stats.totalElementsConsumed().isEmpty()) {
            drawText(g, font, "Elements consumed (30d):", textX, y, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_NORMAL);
            y += lineH;

            var elements = stats.totalElementsConsumed();
            ElementType[] types = ElementType.values();
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < types.length; i++) {
                long amount = elements.getOrDefault(types[i], 0L);
                if (i > 0 && i % 3 == 0) {
                    drawText(g, font, line.toString(), textX + 4, y, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_DIM);
                    line = new StringBuilder();
                    y += lineH;
                }
                if (line.length() > 0) line.append("   ");
                line.append(String.format("%-6s: %d", types[i].getId(), amount));
            }
            if (!line.isEmpty()) {
                drawText(g, font, line.toString(), textX + 4, y, com.wsteam.wandscape.shared.ui.theme.WandscapeTheme.COLOR_TEXT_DIM);
                y += lineH;
            }
        }
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

    private static int getHoveredTab(int screenW, int screenH) {
        if (!WandscapePanelState.isCursorLifted()) return -1;
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        double mx = Minecraft.getInstance().mouseHandler.xpos() / guiScale;
        double my = Minecraft.getInstance().mouseHandler.ypos() / guiScale;
        return WandscapePanelController.getTabAt(mx, my, screenW, screenH);
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
    
    private static int getTabIndex(WandscapePanelState.SubMode activeMode) {
        if (activeMode == WandscapePanelState.SubMode.BUILD_PROJECTION) return 0;
        if (activeMode == WandscapePanelState.SubMode.ROAD_PROJECTION) return 1;
        if (activeMode == WandscapePanelState.SubMode.BUILD_EDITOR) return 2;
        if (activeMode == WandscapePanelState.SubMode.STATS) return 3;
        return -1;
    }
}
