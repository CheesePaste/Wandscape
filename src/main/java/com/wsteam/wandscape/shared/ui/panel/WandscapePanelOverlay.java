package com.wsteam.wandscape.shared.ui.panel;

import com.mojang.blaze3d.systems.RenderSystem;
import com.wsteam.wandscape.content.building.projection.client.BuildPopPanelOverlay;
import com.wsteam.wandscape.content.building.projection.client.BuildingDebugClientState;
import com.wsteam.wandscape.content.building.projection.client.ProjectionClientState;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.theme.WandscapeTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.UUID;

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
        if (!WandscapePanelState.isPanelOpen() || WandscapePanelState.isPanelHidden()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.screen != null) return;

        GuiGraphics g = event.getGuiGraphics();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        double guiScale = mc.getWindow().getGuiScale();
        double mx = mc.mouseHandler.xpos() / guiScale;
        double my = mc.mouseHandler.ypos() / guiScale;

        // Flush any pending HUD batches (chat text etc.) BEFORE rendering the panel, so the
        // panel content composites above them instead of sharing a sorted text buffer.
        g.flush();

        // Clear the depth buffer so depth-tested panel elements (sidebar icons, 3D previews)
        // are not culled by depth written by earlier HUD/chat batches.
        RenderSystem.clearDepth(1.0);
        RenderSystem.clear(256, Minecraft.ON_OSX);

        // Building selection bar
        BuildingSelectionOverlay.render(g, mc.font, screenW, screenH, mx, my);

        // Build mode right pop panel
        BuildPopPanelOverlay.render(g, mc.font, screenW, screenH, mx, my);

        // Task & Mage Management Hub (dedicated spacious overlay — hides top bar & sidebar)
        if (WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.TASKS) {
            TaskManagementOverlay.render(g, mc.font, screenW, screenH, mx, my);
            g.bufferSource().endBatch(RenderType.guiOverlay());
            g.flush();
            return;
        }

        renderFills(g, mc.font, screenW, screenH, mx, my);
        g.bufferSource().endBatch(RenderType.guiOverlay());
        renderTexts(g, mc.font, screenW, screenH, mx, my);

        // Push the panel's remaining text/previews out in this pass so they stay above chat.
        g.flush();
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Fills ──
    // ═══════════════════════════════════════════════════════════════

    private static void renderFills(GuiGraphics g, Font font, int screenW, int screenH, double mx, double my) {
        // 建筑信息顶栏仅在俯瞰(OVERVIEW)模式显示——操作型子模式下不弹、也不隐藏殖民地带
        boolean lookingAtBuilding = BuildingDebugClientState.getDisplayData() != null
                && WandscapePanelState.isInspectContext();

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
            WandscapeTheme.ICON_TAB_STATS,
            WandscapeTheme.ICON_TAB_EDITOR
        };

        WandscapePanelState.SubMode activeMode = WandscapePanelState.getActiveSubMode();
        int hoveredIcon = getSidebarHoveredIcon(mx, my, screenH);

        // Build / Road / Stats / Tasks tabs
        for (int i = 0; i < 4; i++) {
            int iy = startY + i * totalIconH;
            int ix = (SIDEBAR_W - SIDEBAR_ICON_S) / 2;
            int color = isTabActive(i, activeMode) ? WandscapeTheme.COLOR_TEXT_ACTIVE : WandscapeTheme.COLOR_TEXT_NORMAL;
            WandscapeTheme.drawIcon(g, tabIcons[i], ix, iy, SIDEBAR_ICON_S, SIDEBAR_ICON_S, color);
        }

        // Warning icon (with gap below tabs)
        int warnY = startY + 4 * totalIconH + 12;
        int ix = (SIDEBAR_W - SIDEBAR_ICON_S) / 2;
        WandscapeTheme.drawIcon(g, WandscapeTheme.ICON_WARNING, ix, warnY, SIDEBAR_ICON_S, SIDEBAR_ICON_S, WandscapeTheme.COLOR_TEXT_NORMAL);
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Texts ──
    // ═══════════════════════════════════════════════════════════════

    private static void renderTexts(GuiGraphics g, Font font, int screenW, int screenH, double mx, double my) {
        // 与 renderFills 同口径：非俯瞰模式不视为「看着建筑」，殖民地带照常显示
        boolean lookingAtBuilding = BuildingDebugClientState.getDisplayData() != null
                && WandscapePanelState.isInspectContext();

        if (!lookingAtBuilding) {
            UUID cid = WandscapePanelState.getColonyId();
            if (cid != null) {
                renderTopBar(g, font, screenW, mx, my);
            }
        }

        // First-time guidance
        if (com.wsteam.wandscape.shared.ui.guidance.GuideSession.shouldShow()) {
            boolean buildMode = WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.BUILD_PROJECTION;
            boolean isPlacing = WandscapePanelState.getBuildPhase() == WandscapePanelState.BuildPhase.PLACING;
            boolean isBar = WandscapePanelState.getBuildPhase() == WandscapePanelState.BuildPhase.BAR;
            boolean isPinned = ProjectionClientState.isPinned();
            com.wsteam.wandscape.shared.ui.guidance.GuideRenderer.render(g, font, screenW, screenH, mx, my,
                    com.wsteam.wandscape.shared.ui.guidance.GuideRegistry.step(
                            com.wsteam.wandscape.shared.ui.guidance.GuideSession.currentStep()),
                    buildMode, isPlacing, isBar, isPinned);
        }

        // Stats content (shifted right of sidebar)
        if (WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.STATS) {
            renderStatsContent(g, font, screenW, screenH);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Top bar HUD ──
    // ═══════════════════════════════════════════════════════════════

    private static void renderTopBar(GuiGraphics g, Font font, int screenW, double mx, double my) {
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
        String dayText = I18n.name("gui.wandscape.panel.day", "Day %s", day).getString();
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
        String npcText = I18n.name("gui.wandscape.panel.npc_count", "%s/%s NPC",
                WandscapePanelState.getNpcIdleCount(), WandscapePanelState.getNpcTotalCount()).getString();
        drawText(g, font, npcText, x, textY1, WandscapeTheme.COLOR_TEXT_NORMAL);

        // 8. Help ? button at top right
        int helpX = screenW - rightMargin - 16;
        int helpY = 4;
        int helpW = 14;
        int helpH = 14;
        boolean helpHover = WandscapePanelState.isCursorLifted() && mx >= helpX && mx <= helpX + helpW && my >= helpY && my <= helpY + helpH;
        int helpState = helpHover ? 1 : 0;
        com.wsteam.wandscape.shared.ui.skin.SkinRender.drawHelpButton(g, helpX, helpY, helpW, helpH, helpState);

        if (helpHover) {
            String keyName = com.wsteam.wandscape.WandscapeClient.GUIDE_TOGGLE.getTranslatedKeyMessage().getString();
            g.renderTooltip(font, I18n.name("gui.wandscape.panel.open_guide", "打开指南 (%s)", keyName), (int) mx, (int) my);
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

        for (int i = 0; i < elementIds.length; i++) {
            var icon = WandscapeTheme.elementIcon(elementIds[i]);
            int color = WandscapeTheme.elementColor(elementIds[i]);
            WandscapeTheme.drawIcon(g, icon, x, y, s, s, color);
            x += s + 2;
            String val = String.valueOf(amounts[i]);
            drawText(g, font, val, x, textY, color);
            x += font.width(val) + 6;
        }
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

        if (stats == null || stats.snapshotCount() == 0) {
            drawText(g, font, I18n.name("gui.wandscape.stats.none", "No statistics available yet.").getString(),
                    leftX + pad, topY + pad, WandscapeTheme.COLOR_TEXT_DIM);
            return;
        }

        // ── Header (full width) ──
        String header = I18n.name("gui.wandscape.stats.title", "Colony Statistics  |  Day %s", stats.currentDay()).getString();
        drawText(g, font, header, leftX + pad, topY + pad, WandscapeTheme.COLOR_TEXT_NORMAL);
        int sepY = topY + pad + font.lineHeight + 2;
        g.fill(leftX + pad, sepY, leftX + boxW - pad, sepY + 1, WandscapeTheme.COLOR_BORDER_NORMAL);
        int y0 = sepY + 6;

        // ── Left column: tourists ──
        int lx = leftX + pad;
        int y = y0;

        drawText(g, font, I18n.name("gui.wandscape.stats.tourists", "Tourists").getString(), lx, y, WandscapeTheme.COLOR_TEXT_ACTIVE);
        y += lineH;
        drawText(g, font, "  " + I18n.name("gui.wandscape.stats.arrived", "In: %s", stats.touristsArrived()).getString(), lx, y, WandscapeTheme.COLOR_TEXT_DIM);
        y += lineH;
        drawText(g, font, "  " + I18n.name("gui.wandscape.stats.departed", "Out: %s", stats.touristsDeparted()).getString(), lx, y, WandscapeTheme.COLOR_TEXT_DIM);
        y += lineH;
        drawText(g, font, "  " + I18n.name("gui.wandscape.stats.tourist_comfort", "Fill C: %s%%", stats.avgComfortRatio()).getString(), lx, y, WandscapeTheme.COLOR_TEXT_DIM);
        y += lineH;
        drawText(g, font, "  " + I18n.name("gui.wandscape.stats.tourist_magic", "Fill M: %s%%", stats.avgMagicRatio()).getString(), lx, y, WandscapeTheme.COLOR_TEXT_DIM);
        y += lineH;
        drawText(g, font, "  " + I18n.name("gui.wandscape.stats.tourist_wonder", "Fill W: %s%%", stats.avgWonderRatio()).getString(), lx, y, WandscapeTheme.COLOR_TEXT_DIM);

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

        // Tabs 0–3 (Build, Road, Stats, Tasks)
        for (int i = 0; i < 4; i++) {
            int iy = startY + i * totalH;
            if (my >= iy && my <= iy + SIDEBAR_ICON_S) return i;
        }

        // Warning icon (index 4)
        int warnY = startY + 4 * totalH + 12;
        if (my >= warnY && my <= warnY + SIDEBAR_ICON_S) return 4;

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
            case 3 -> activeMode == WandscapePanelState.SubMode.TASKS;
            default -> false;
        };
    }
}
