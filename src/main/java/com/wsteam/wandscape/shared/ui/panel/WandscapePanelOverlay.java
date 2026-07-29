package com.wsteam.wandscape.shared.ui.panel;

import java.util.List;
import java.util.UUID;

import com.wsteam.wandscape.projection.client.BuildingDebugClientState;
import com.wsteam.wandscape.road.client.RoadPlacementOverlay;
import com.wsteam.wandscape.road.client.RoadPlacementState;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.ui.theme.WandscapeTheme;

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
 * Layout: full-width top HUD bar + left sidebar with mode tabs.
 */
public final class WandscapePanelOverlay {

    private static final String TAG = "WandscapePanelOverlay";

    // ── Layout constants ──
    public static final int TOP_BAR_H = 28;
    public static final int SIDEBAR_W = 28;
    public static final int SIDEBAR_ICON_S = 24;
    public static final int SIDEBAR_GAP = 8;

    private static final int BAR_BG = 0xEE14161C;
    private static final int SIDEBAR_BG = 0xAA111214;
    private static final int OVERLAY_BG = 0xEE111214;

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

        double guiScale = mc.getWindow().getGuiScale();
        double mx = mc.mouseHandler.xpos() / guiScale;
        double my = mc.mouseHandler.ypos() / guiScale;

        // Building selection bar
        BuildingSelectionOverlay.render(g, mc.font, screenW, screenH, mx, my);

        // Road placement preset bar
        if (WandscapePanelState.isCursorLifted()
                && WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.ROAD_PROJECTION
                && RoadPlacementState.getRoadPhase() == RoadPlacementState.RoadPhase.BAR) {
            RoadPlacementOverlay.render(g, mc.font, screenW, screenH, mx, my);
        }

        renderFills(g, mc.font, screenW, screenH, mx, my);
        g.bufferSource().endBatch(RenderType.guiOverlay());
        renderTexts(g, mc.font, screenW, screenH, mx, my);
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Fills ──
    // ═══════════════════════════════════════════════════════════════

    private static void renderFills(GuiGraphics g, Font font, int screenW, int screenH, double mx, double my) {
        boolean lookingAtBuilding = BuildingDebugClientState.getCachedData() != null;

        // 1. Top bar background
        if (!lookingAtBuilding) {
            UUID cid = WandscapePanelState.getColonyId();
            if (cid != null) {
                g.fill(RenderType.guiOverlay(), 0, 0, screenW, TOP_BAR_H, 0, BAR_BG);
                g.fill(RenderType.guiOverlay(), 0, TOP_BAR_H - 1, screenW, TOP_BAR_H, 0, WandscapeTheme.COLOR_BORDER_NORMAL);
            }
        }

        // 2. Sidebar
        renderSidebar(g, screenW, screenH, mx, my);

        // 3. Warning overlay
        if (WandscapePanelState.isWarningOverlayActive() && WandscapePanelState.getShutdownCount() > 0) {
            renderWarningOverlay(g, font, screenH);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Sidebar ──
    // ═══════════════════════════════════════════════════════════════

    private static void renderSidebar(GuiGraphics g, int screenW, int screenH, double mx, double my) {
        int x = 0;
        int y = TOP_BAR_H;
        int h = screenH - TOP_BAR_H;

        g.fill(RenderType.guiOverlay(), x, y, x + SIDEBAR_W, y + h, 0, SIDEBAR_BG);

        int startY = y + 8;
        int totalIconH = SIDEBAR_ICON_S + SIDEBAR_GAP;

        net.minecraft.resources.ResourceLocation[] tabIcons = {
            WandscapeTheme.ICON_TAB_BUILD,
            WandscapeTheme.ICON_TAB_ROAD,
            WandscapeTheme.ICON_TAB_STATS
        };

        WandscapePanelState.SubMode activeMode = WandscapePanelState.getActiveSubMode();
        int hoveredIcon = getSidebarHoveredIcon(mx, my, screenH);

        // Build / Road / Stats tabs
        for (int i = 0; i < 3; i++) {
            int iy = startY + i * totalIconH;
            boolean active = isTabActive(i, activeMode);
            boolean hovered = (i == hoveredIcon);
            int color = active ? WandscapeTheme.COLOR_TEXT_ACTIVE
                      : hovered ? WandscapeTheme.COLOR_TEXT_NORMAL
                      : WandscapeTheme.COLOR_TEXT_DIM;
            int ix = (SIDEBAR_W - SIDEBAR_ICON_S) / 2;
            WandscapeTheme.drawIcon(g, tabIcons[i], ix, iy, SIDEBAR_ICON_S, SIDEBAR_ICON_S, color);
        }

        // Warning icon (with gap below tabs)
        int warnY = startY + 3 * totalIconH + 12;
        boolean hoveredWarn = (hoveredIcon == 3);
        int warnColor = WandscapePanelState.getShutdownCount() > 0
                ? (hoveredWarn ? WandscapeTheme.COLOR_TEXT_NORMAL : WandscapeTheme.COLOR_TEXT_ACTIVE)
                : WandscapeTheme.COLOR_TEXT_DIM;
        int ix = (SIDEBAR_W - SIDEBAR_ICON_S) / 2;
        WandscapeTheme.drawIcon(g, WandscapeTheme.ICON_WARNING, ix, warnY, SIDEBAR_ICON_S, SIDEBAR_ICON_S, warnColor);

        // Red dot badge
        if (WandscapePanelState.getShutdownCount() > 0) {
            int dotX = ix + SIDEBAR_ICON_S - 2;
            g.fill(RenderType.guiOverlay(), dotX, warnY, dotX + 6, warnY + 6, 0, 0xFFFF4444);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Warning overlay ──
    // ═══════════════════════════════════════════════════════════════

    private static void renderWarningOverlay(GuiGraphics g, Font font, int screenH) {
        List<String> names = WandscapePanelState.getShutdownBuildingNames();
        int count = WandscapePanelState.getShutdownCount();
        int maxShow = Math.min(names.size(), 10);

        int w = 200;
        int lineH = font.lineHeight + 1;
        int h = 12 + lineH * (2 + maxShow) + 4;
        int x = SIDEBAR_W;
        int startIconY = TOP_BAR_H + 8 + 3 * (SIDEBAR_ICON_S + SIDEBAR_GAP) + 12;
        int y = startIconY;

        // Clamp to screen bottom
        if (y + h > screenH) y = screenH - h;

        g.fill(RenderType.guiOverlay(), x, y, x + w, y + h, 0, OVERLAY_BG);
        // White border
        g.fill(RenderType.guiOverlay(), x, y, x + w, y + 1, 0, WandscapeTheme.COLOR_BORDER_ACTIVE);
        g.fill(RenderType.guiOverlay(), x, y + h - 1, x + w, y + h, 0, WandscapeTheme.COLOR_BORDER_ACTIVE);
        g.fill(RenderType.guiOverlay(), x, y + 1, x + 1, y + h - 1, 0, WandscapeTheme.COLOR_BORDER_ACTIVE);
        g.fill(RenderType.guiOverlay(), x + w - 1, y + 1, x + w, y + h - 1, 0, WandscapeTheme.COLOR_BORDER_ACTIVE);

        int tx = x + 8;
        int ty = y + 6;
        drawText(g, font, "Shutdown (" + count + ")", tx, ty, WandscapeTheme.COLOR_TEXT_ACTIVE);
        ty += lineH + 4;

        for (int i = 0; i < maxShow; i++) {
            drawText(g, font, "- " + names.get(i), tx, ty, WandscapeTheme.COLOR_TEXT_DIM);
            ty += lineH;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Texts ──
    // ═══════════════════════════════════════════════════════════════

    private static void renderTexts(GuiGraphics g, Font font, int screenW, int screenH, double mx, double my) {
        boolean lookingAtBuilding = BuildingDebugClientState.getCachedData() != null;

        if (!lookingAtBuilding) {
            UUID cid = WandscapePanelState.getColonyId();
            if (cid != null) {
                renderTopBar(g, font, screenW);
            }
        }

        // Stats content (shifted right of sidebar)
        if (WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.STATS) {
            renderStatsContent(g, font, screenW, screenH);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Top bar HUD ──
    // ═══════════════════════════════════════════════════════════════

    private static void renderTopBar(GuiGraphics g, Font font, int screenW) {
        int lvl = WandscapePanelState.getColonyLevel();
        String name = WandscapePanelState.getColonyName();
        UUID cid = WandscapePanelState.getColonyId();
        if (name == null || name.isEmpty()) name = cid != null ? cid.toString().substring(0, 8) : "?";

        Minecraft mc = Minecraft.getInstance();

        // ── Row 1: colony info + stats + day + tourists + NPC ──
        int y1 = 3;
        int iconS1 = 12;
        int textY1 = 5;
        int x = 4;
        int rightMargin = 8;

        // 1. Colony icon + name + level
        WandscapeTheme.drawIcon(g, WandscapeTheme.ICON_COLONY, x, y1, iconS1, iconS1, WandscapeTheme.COLOR_TEXT_NORMAL);
        x += iconS1 + 3;
        String colonyText = name + " Lv." + lvl;
        drawText(g, font, colonyText, x, textY1, WandscapeTheme.COLOR_TEXT_NORMAL);
        x += font.width(colonyText) + 6;

        // 2. Comfort
        WandscapeTheme.drawIcon(g, WandscapeTheme.ICON_COMFORT, x, y1, iconS1, iconS1, WandscapeTheme.COLOR_COMFORT);
        x += iconS1 + 2;
        String comfortStr = String.valueOf(WandscapePanelState.getComfort());
        drawText(g, font, comfortStr, x, textY1, WandscapeTheme.COLOR_TEXT_NORMAL);
        x += font.width(comfortStr) + 6;

        // 3. Magic
        WandscapeTheme.drawIcon(g, WandscapeTheme.ICON_MAGIC, x, y1, iconS1, iconS1, WandscapeTheme.COLOR_MAGIC);
        x += iconS1 + 2;
        String magicStr = String.valueOf(WandscapePanelState.getMagic());
        drawText(g, font, magicStr, x, textY1, WandscapeTheme.COLOR_TEXT_NORMAL);
        x += font.width(magicStr) + 6;

        // 4. Wonder
        WandscapeTheme.drawIcon(g, WandscapeTheme.ICON_WONDER, x, y1, iconS1, iconS1, WandscapeTheme.COLOR_WONDER);
        x += iconS1 + 2;
        String wonderStr = String.valueOf(WandscapePanelState.getWonder());
        drawText(g, font, wonderStr, x, textY1, WandscapeTheme.COLOR_TEXT_NORMAL);
        x += font.width(wonderStr) + 10;

        // 5. Day
        long day = mc.level != null ? mc.level.getDayTime() / 24000 + 1 : 1;
        String dayText = "Day " + day;
        drawText(g, font, dayText, x, textY1, WandscapeTheme.COLOR_TEXT_DIM);
        x += font.width(dayText) + 10;

        // 6. Tourist count (overnight stayers / total)
        WandscapeTheme.drawIcon(g, WandscapeTheme.ICON_TOURIST, x, y1, iconS1, iconS1, WandscapeTheme.COLOR_TEXT_NORMAL);
        x += iconS1 + 2;
        int totalT = WandscapePanelState.getTouristCount();
        int overnightT = WandscapePanelState.getOvernightStayerCount();
        String touristText = overnightT + "/" + totalT;
        drawText(g, font, touristText, x, textY1, WandscapeTheme.COLOR_TEXT_NORMAL);
        x += font.width(touristText) + 10;

        // 7. NPC idle/total
        String npcText = WandscapePanelState.getNpcIdleCount() + "/" + WandscapePanelState.getNpcTotalCount() + " NPC";
        drawText(g, font, npcText, x, textY1, WandscapeTheme.COLOR_TEXT_NORMAL);

        // 8. Warning icon+count at far right of first row
        int shutdownCount = WandscapePanelState.getShutdownCount();
        if (shutdownCount > 0) {
            String warnStr = String.valueOf(shutdownCount);
            int warnWidth = iconS1 + 2 + font.width(warnStr);
            int warnX = screenW - rightMargin - warnWidth;
            WandscapeTheme.drawIcon(g, WandscapeTheme.ICON_WARNING, warnX, y1, iconS1, iconS1, WandscapeTheme.COLOR_TEXT_ACTIVE);
            warnX += iconS1 + 2;
            drawText(g, font, warnStr, warnX, textY1, WandscapeTheme.COLOR_TEXT_ACTIVE);
        }

        // ── Row 2: element icons + amounts ──
        int y2 = 17;
        int s2 = 9;
        int textY2 = 19;
        renderElementIcons(g, font, 4, y2, textY2, s2);
    }

    private static void renderElementIcons(GuiGraphics g, Font font, int startX, int y, int textY, int s) {
        int x = startX;

        String[] elementIds = {"earth", "wood", "water", "fire", "metal", "wind", "dark"};
        int[] amounts = {
            WandscapePanelState.getEarthAmount(),
            WandscapePanelState.getWoodAmount(),
            WandscapePanelState.getWaterAmount(),
            WandscapePanelState.getFireAmount(),
            WandscapePanelState.getMetalAmount(),
            WandscapePanelState.getWindAmount(),
            WandscapePanelState.getDarkAmount()
        };
        int[] colors = {0xFF8B6914, 0xFF2E8B57, 0xFF4A90D9, 0xFFB22222, 0xFFA0A0A0, 0xFF87CEEB, 0xFF6B3FA0};

        for (int i = 0; i < elementIds.length; i++) {
            var icon = WandscapeTheme.elementIcon(elementIds[i]);
            WandscapeTheme.drawIcon(g, icon, x, y, s, s, colors[i]);
            x += s + 2;
            String val = String.valueOf(amounts[i]);
            drawText(g, font, val, x, textY, colors[i]);
            x += font.width(val) + 6;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Stats content ──
    // ═══════════════════════════════════════════════════════════════

    private static void renderStatsContent(GuiGraphics g, Font font, int screenW, int screenH) {
        var stats = WandscapePanelState.getStatsSummary();

        int boxW = 280;
        int boxH = 140;
        int leftX = SIDEBAR_W + 4;
        int topY = TOP_BAR_H + 4;
        WandscapeTheme.drawRtsBox(g, leftX, topY, boxW, boxH, true, false);

        int textX = leftX + 10;
        int y = topY + 10;
        int lineH = font.lineHeight + 3;

        if (stats == null || stats.snapshotCount() == 0) {
            drawText(g, font, "No statistics available yet.", textX, y, WandscapeTheme.COLOR_TEXT_DIM);
            return;
        }

        String header = "Colony Statistics  |  Day " + stats.currentDay();
        drawText(g, font, header, textX, y, WandscapeTheme.COLOR_TEXT_NORMAL);
        y += lineH + 4;

        String maint = "Maintenance: " + stats.buildingsPaid() + " paid | " + stats.buildingsShutdown() + " shut";
        drawText(g, font, maint, textX, y, WandscapeTheme.COLOR_TEXT_DIM);
        y += lineH;

        String tourist = "Tourists: " + stats.touristsArrived() + " in | " + stats.touristsDeparted() + " out | " + stats.avgSatisfaction() + "% ok";
        drawText(g, font, tourist, textX, y, WandscapeTheme.COLOR_TEXT_DIM);
        y += lineH + 4;

        if (!stats.totalElementsConsumed().isEmpty()) {
            drawText(g, font, "Elements consumed (30d):", textX, y, WandscapeTheme.COLOR_TEXT_NORMAL);
            y += lineH;

            var elements = stats.totalElementsConsumed();
            ElementType[] types = ElementType.values();
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < types.length; i++) {
                long amount = elements.getOrDefault(types[i], 0L);
                if (i > 0 && i % 3 == 0) {
                    drawText(g, font, line.toString(), textX + 4, y, WandscapeTheme.COLOR_TEXT_DIM);
                    line = new StringBuilder();
                    y += lineH;
                }
                if (line.length() > 0) line.append("   ");
                line.append(String.format("%-6s: %d", types[i].getId(), amount));
            }
            if (!line.isEmpty()) {
                drawText(g, font, line.toString(), textX + 4, y, WandscapeTheme.COLOR_TEXT_DIM);
                y += lineH;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Text helpers ──
    // ═══════════════════════════════════════════════════════════════

    private static void drawText(GuiGraphics g, Font font, String text, float x, float y, int color) {
        font.drawInBatch(text, x, y, color, false,
                g.pose().last().pose(), g.bufferSource(),
                Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Sidebar hit detection ──
    // ═══════════════════════════════════════════════════════════════

    public static int getSidebarHoveredIcon(double mx, double my, int screenH) {
        if (!WandscapePanelState.isCursorLifted()) return -1;
        if (mx < 0 || mx > SIDEBAR_W) return -1;
        if (my < TOP_BAR_H) return -1;

        int startY = TOP_BAR_H + 8;
        int totalH = SIDEBAR_ICON_S + SIDEBAR_GAP;

        // Tabs 0–2
        for (int i = 0; i < 3; i++) {
            int iy = startY + i * totalH;
            if (my >= iy && my <= iy + SIDEBAR_ICON_S) return i;
        }

        // Warning icon (index 3)
        int warnY = startY + 3 * totalH + 12;
        if (my >= warnY && my <= warnY + SIDEBAR_ICON_S) return 3;

        return -1;
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Tab helpers ──
    // ═══════════════════════════════════════════════════════════════

    private static boolean isTabActive(int tabIndex, WandscapePanelState.SubMode activeMode) {
        return switch (tabIndex) {
            case 0 -> activeMode == WandscapePanelState.SubMode.BUILD_PROJECTION;
            case 1 -> activeMode == WandscapePanelState.SubMode.ROAD_PROJECTION;
            case 2 -> activeMode == WandscapePanelState.SubMode.STATS;
            default -> false;
        };
    }
}
