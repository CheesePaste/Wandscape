package com.wsteam.wandscape.shared.ui.panel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

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

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int BAR_BG = 0xCC000000;
    private static final int TEXT_WHITE = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFAAAAAA;
    private static final int TAB_INACTIVE_BG = 0xFF2A1A0A;
    private static final int TAB_HOVER_BG = 0xFF3D2A10;
    private static final int TAB_ACTIVE_BG = 0xFF3D2060;
    private static final int TAB_ACTIVE_BORDER = 0xFFC8A040;
    private static final int COMFORT_COLOR = 0xFF4CAF50;
    private static final int MAGIC_COLOR = 0xFF42A5F5;
    private static final int WONDER_COLOR = 0xFFC8A040;

    private static final String[] TAB_LABELS = { "Build", "Road", "Editor" };
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
        LOGGER.info("[Panel] Overlay registered");
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

        renderFills(g, mc.font, screenW, screenH);

        // Force-flush fills before text so HashMap iteration order
        // in BufferSource can't put guiOverlay after text_see_through.
        g.bufferSource().endBatch(RenderType.guiOverlay());

        renderTexts(g, mc.font, screenW, screenH);
    }

    // ── Fills (guiOverlay — NO_DEPTH_TEST, covers hotbar) ──

    private static void renderFills(GuiGraphics g, Font font, int screenW, int screenH) {
        // Top bar
        g.fill(RenderType.guiOverlay(), 0, 0, screenW, TOP_BAR_H, 0, BAR_BG);

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
        // Top bar
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

        String hint = "C: Lift  V: Close";
        int hintW = font.width(hint);
        drawText(g, font, hint, screenW - hintW - 8,
                barY + (BOTTOM_BAR_H - font.lineHeight) / 2, TEXT_DIM);
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
            default -> false;
        };
    }
}
