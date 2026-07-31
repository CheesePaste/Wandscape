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
                g.fill(RenderType.guiOverlay(), 0, TOP_BAR_H - 1, screenW, TOP_BAR_H, 0, 0xFFC8A040);
            }
        }

        // 2. Sidebar
        renderSidebar(g, screenW, screenH, mx, my);

        // 3. Warning overlay (quick preview — full screen opened from sidebar click)
        if (WandscapePanelState.isWarningOverlayActive() && WandscapePanelState.getTotalAnomalyCount() > 0) {
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
            int ix = (SIDEBAR_W - SIDEBAR_ICON_S) / 2;
            int color = isTabActive(i, activeMode) ? WandscapeTheme.COLOR_TEXT_ACTIVE : WandscapeTheme.COLOR_TEXT_NORMAL;
            WandscapeTheme.drawIcon(g, tabIcons[i], ix, iy, SIDEBAR_ICON_S, SIDEBAR_ICON_S, color);
        }

        // Warning icon (with gap below tabs)
        int warnY = startY + 3 * totalIconH + 12;
        int ix = (SIDEBAR_W - SIDEBAR_ICON_S) / 2;
        WandscapeTheme.drawIcon(g, WandscapeTheme.ICON_WARNING, ix, warnY, SIDEBAR_ICON_S, SIDEBAR_ICON_S, WandscapeTheme.COLOR_TEXT_NORMAL);
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Warning overlay ──
    // ═══════════════════════════════════════════════════════════════

    private static void renderWarningOverlay(GuiGraphics g, Font font, int screenH) {
        List<String> shutdownNames = WandscapePanelState.getShutdownBuildingNames();
        List<String> brokenNames = WandscapePanelState.getBrokenBuildingNames();
        int total = WandscapePanelState.getTotalAnomalyCount();

        int w = 220;
        int lineH = font.lineHeight + 1;
        int maxLines = 12;
        int h = 12 + lineH * 2 + 4 + lineH * Math.min(maxLines, total) + 4;
        int x = SIDEBAR_W;
        int startIconY = TOP_BAR_H + 8 + 3 * (SIDEBAR_ICON_S + SIDEBAR_GAP) + 12;
        int y = startIconY;

        if (y + h > screenH) y = screenH - h;

        g.fill(RenderType.guiOverlay(), x, y, x + w, y + h, 0, OVERLAY_BG);
        g.fill(RenderType.guiOverlay(), x, y, x + w, y + 1, 0, WandscapeTheme.COLOR_BORDER_ACTIVE);
        g.fill(RenderType.guiOverlay(), x, y + h - 1, x + w, y + h, 0, WandscapeTheme.COLOR_BORDER_ACTIVE);
        g.fill(RenderType.guiOverlay(), x, y + 1, x + 1, y + h - 1, 0, WandscapeTheme.COLOR_BORDER_ACTIVE);
        g.fill(RenderType.guiOverlay(), x + w - 1, y + 1, x + w, y + h - 1, 0, WandscapeTheme.COLOR_BORDER_ACTIVE);

        int tx = x + 8;
        int ty = y + 6;
        drawText(g, font, "异常报告 (" + total + ")", tx, ty, WandscapeTheme.COLOR_TEXT_ACTIVE);
        ty += lineH + 4;

        int shown = 0;
        int maxPerType = maxLines / 2;
        if (!shutdownNames.isEmpty()) {
            drawText(g, font, "§c[关闭]", tx + 2, ty, 0xFFB22222);
            ty += lineH;
            for (int i = 0; i < Math.min(shutdownNames.size(), maxPerType); i++) {
                drawText(g, font, "  - " + shutdownNames.get(i), tx + 4, ty, WandscapeTheme.COLOR_TEXT_DIM);
                ty += lineH;
                shown++;
            }
        }
        if (!brokenNames.isEmpty()) {
            drawText(g, font, "§e[损坏]", tx + 2, ty, 0xFFB8860B);
            ty += lineH;
            for (int i = 0; i < Math.min(brokenNames.size(), maxPerType); i++) {
                drawText(g, font, "  - " + brokenNames.get(i), tx + 4, ty, WandscapeTheme.COLOR_TEXT_DIM);
                ty += lineH;
                shown++;
            }
        }
        if (shown >= maxLines && total > shown) {
            drawText(g, font, "... 还有 " + (total - shown) + " 个异常", tx + 4, ty, WandscapeTheme.COLOR_TEXT_DIM);
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

        // First-time guidance
        if (WandscapePanelState.shouldShowGuidance()) {
            renderGuidance(g, font, screenW, screenH);
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

        // 8. Warning icon+count at far right of first row (total anomalies)
        int anomalyCount = WandscapePanelState.getTotalAnomalyCount();
        if (anomalyCount > 0) {
            String warnStr = String.valueOf(anomalyCount);
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
    // ── First-time guidance ──
    // ═══════════════════════════════════════════════════════════════

    private static void renderGuidance(GuiGraphics g, Font font, int screenW, int screenH) {
        int pad = 10;
        int lineH = font.lineHeight;
        String title = "Getting Started";
        String line1 = "Build a Town Hall to manage your colony";
        String line2 = "Build a Warehouse to store resources";
        String hint = "Select a building from the bar above";

        int titleW = font.width(title);
        int line1W = font.width(line1);
        int line2W = font.width(line2);
        int hintW = font.width(hint);
        int boxW = Math.max(Math.max(titleW, Math.max(line1W, line2W)), hintW) + pad * 2;
        int boxH = pad * 2 + lineH * 4 + 6;

        int x = screenW - boxW - 8;
        int y = TOP_BAR_H + 4;

        // Background
        g.fill(RenderType.guiOverlay(), x, y, x + boxW, y + boxH, 0, 0xDD1A1C22);
        // Border
        int col = 0xFFC8A040;
        g.fill(RenderType.guiOverlay(), x, y, x + boxW, y + 1, 0, col);
        g.fill(RenderType.guiOverlay(), x, y + boxH - 1, x + boxW, y + boxH, 0, col);
        g.fill(RenderType.guiOverlay(), x, y, x + 1, y + boxH, 0, col);
        g.fill(RenderType.guiOverlay(), x + boxW - 1, y, x + boxW, y + boxH, 0, col);

        int tx = x + pad;
        int ty = y + pad;
        drawText(g, font, "§e" + title, tx, ty, 0xFFFFC040);
        ty += lineH + 3;
        drawText(g, font, " §7- " + line1, tx, ty, WandscapeTheme.COLOR_TEXT_DIM);
        ty += lineH;
        drawText(g, font, " §7- " + line2, tx, ty, WandscapeTheme.COLOR_TEXT_DIM);
        ty += lineH + 3;
        drawText(g, font, "§8" + hint, tx, ty, WandscapeTheme.COLOR_TEXT_DIM);
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Stats content ──
    // ═══════════════════════════════════════════════════════════════

    private static void renderStatsContent(GuiGraphics g, Font font, int screenW, int screenH) {
        var stats = WandscapePanelState.getStatsSummary();

        int boxW = 380;
        int boxH = 165;
        int leftX = SIDEBAR_W + 4;
        int topY = TOP_BAR_H + 4;
        WandscapeTheme.drawRtsBox(g, leftX, topY, boxW, boxH, true, false);

        int pad = 10;
        int lineH = font.lineHeight + 3;
        int colW = (boxW - pad * 3) / 2;

        if (stats == null || stats.snapshotCount() == 0) {
            drawText(g, font, "No statistics available yet.", leftX + pad, topY + pad,
                    WandscapeTheme.COLOR_TEXT_DIM);
            return;
        }

        // ── Header (full width) ──
        String header = "Colony Statistics  |  Day " + stats.currentDay();
        drawText(g, font, header, leftX + pad, topY + pad, WandscapeTheme.COLOR_TEXT_NORMAL);
        int sepY = topY + pad + font.lineHeight + 2;
        g.fill(leftX + pad, sepY, leftX + boxW - pad, sepY + 1, WandscapeTheme.COLOR_BORDER_NORMAL);
        int y0 = sepY + 6;

        // ── Left column: maintenance + tourists ──
        int lx = leftX + pad;
        int y = y0;

        drawText(g, font, "Maintenance", lx, y, WandscapeTheme.COLOR_TEXT_ACTIVE);
        y += lineH;
        drawText(g, font, "  Paid: " + stats.buildingsPaid(), lx, y, WandscapeTheme.COLOR_TEXT_DIM);
        y += lineH;
        drawText(g, font, "  Shut: " + stats.buildingsShutdown(), lx, y, WandscapeTheme.COLOR_TEXT_DIM);
        y += lineH + 4;

        drawText(g, font, "Tourists", lx, y, WandscapeTheme.COLOR_TEXT_ACTIVE);
        y += lineH;
        drawText(g, font, "  In:  " + stats.touristsArrived(), lx, y, WandscapeTheme.COLOR_TEXT_DIM);
        y += lineH;
        drawText(g, font, "  Out: " + stats.touristsDeparted(), lx, y, WandscapeTheme.COLOR_TEXT_DIM);
        y += lineH;
        drawText(g, font, "  Sat:  " + stats.avgSatisfaction() + "%", lx, y, WandscapeTheme.COLOR_TEXT_DIM);

        // ── Right column: elements consumed ──
        int rx = lx + colW + pad;
        y = y0;

        drawText(g, font, "Elements (30d)", rx, y, WandscapeTheme.COLOR_TEXT_ACTIVE);
        y += lineH;

        var elements = stats.totalElementsConsumed();
        if (elements.isEmpty()) {
            drawText(g, font, "  —", rx, y, WandscapeTheme.COLOR_TEXT_DIM);
        } else {
            ElementType[] types = ElementType.values();
            for (int i = 0; i < types.length; i++) {
                long amount = elements.getOrDefault(types[i], 0L);
                // Draw colored dot
                int dotColor = elementColor(types[i]);
                g.fill(rx + 2, y + 4, rx + 10, y + 12, dotColor);
                // Element name + count
                drawText(g, font,
                        types[i].getId() + ": " + formatElementCount(amount),
                        rx + 14, y, WandscapeTheme.COLOR_TEXT_DIM);
                y += lineH;
                if (i == 3) { y += 1; } // small gap after fire
            }
        }
    }

    private static int elementColor(ElementType type) {
        return switch (type) {
            case EARTH -> 0xFF8B6914;
            case WOOD  -> 0xFF2E8B57;
            case WATER -> 0xFF4A90D9;
            case FIRE  -> 0xFFB22222;
            case METAL -> 0xFF808080;
            case WIND  -> 0xFF87CEEB;
            case DARK  -> 0xFF4B0082;
        };
    }

    private static String formatElementCount(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
        return String.format("%.1fM", n / 1_000_000.0);
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
