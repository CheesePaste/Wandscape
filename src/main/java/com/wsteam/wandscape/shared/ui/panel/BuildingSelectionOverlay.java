package com.wsteam.wandscape.shared.ui.panel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.projection.client.ProjectionClientState;
import com.wsteam.wandscape.projection.data.BuildingSlot;
import com.wsteam.wandscape.shared.ui.util.BuildingPreviewRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Compact building selection bar shown during Build projection mode BAR phase.
 *
 * <p>Layout (bottom of screen, above the tab bar):
 * <pre>
 * ┌──────────────────────────────────────────┐
 * │ [All][basic][node]...  [Search...]       │ ← Category tabs row
 * │ [icon] [icon] [icon] [icon] ...    [■]   │ ← Scrollable multi-row grid
 * │ [icon] [icon] [icon] [icon] ...    [■]   │
 * │ [icon] [icon] [icon] [icon] ...    [■]   │
 * └──────────────────────────────────────────┘
 * </pre>
 */
public final class BuildingSelectionOverlay {

    static final int BAR_HEIGHT = 140;
    static final int CATEGORY_ROW_H = 16;
    static final int GRID_TOP_OFFSET = CATEGORY_ROW_H + 2;
    static final int CELL_W = 48;
    static final int CELL_H = 40;
    static final int PREVIEW_PAD = 3;  // margin inside cell for 3D preview
    static final int NAME_H = 11;      // reserved height for building name
    static final int GRID_PAD_X = 4;
    static final int SEARCH_W = 80;
    static final int SEARCH_H = 12;
    static final int SCROLLBAR_W = 6;

    private static final int VISIBLE_ROWS = (BAR_HEIGHT - GRID_TOP_OFFSET) / CELL_H;

    private static final int BAR_BG = 0xDD1A0E08;
    private static final int BAR_BORDER = 0xFF4A3020;
    private static final int CAT_SELECTED_BG = 0xFF3D2060;
    private static final int CAT_HOVER_BG = 0xFF3D2A10;
    private static final int CAT_INACTIVE_BG = 0xFF2A1A0A;
    private static final int CAT_TEXT_SELECTED = 0xFFFFE0A0;
    private static final int CAT_TEXT_NORMAL = 0xFFAAAAAA;
    private static final int CELL_BG = 0xFF1E1208;
    private static final int CELL_SELECTED_BG = 0xFF3D2060;
    private static final int CELL_HOVER_BG = 0xFF332010;
    private static final int TEXT_WHITE = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFF999999;
    private static final int SCROLLBAR_TRACK = 0x33000000;
    private static final int SCROLLBAR_THUMB = 0x88AAAAAA;

    private static final String TAG = "BuildingSelectionOverlay";
    private static boolean registered = false;

    private BuildingSelectionOverlay() {}

    public static void register() {
        if (registered) return;
        registered = true;
    }

    public static boolean isActive() {
        return WandscapePanelState.isPanelOpen()
                && ProjectionClientState.isProjecting()
                && WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.BUILD_PROJECTION
                && WandscapePanelState.isBuildingBarOpen();
    }

    static int getSlotsSize() {
        return ProjectionClientState.getBuildingSlots().size();
    }

    public static void render(GuiGraphics g, Font font, int screenW, int screenH,
                               double mouseX, double mouseY) {
        if (!isActive()) {
            Log.debug(TAG, "[Bar] NOT active: panel={} projecting={} subMode={} barOpen={}",
                    WandscapePanelState.isPanelOpen(),
                    ProjectionClientState.isProjecting(),
                    WandscapePanelState.getActiveSubMode(),
                    WandscapePanelState.isBuildingBarOpen());
            return;
        }

        Log.debug(TAG, "[Bar] ACTIVE: slots={} filtered={}", getSlotsSize(), getFilteredSlots().size());
        int barY = screenH - WandscapePanelController.BOTTOM_BAR_HEIGHT - BAR_HEIGHT;

        // Background
        g.fill(0, barY, screenW, barY + BAR_HEIGHT, BAR_BG);
        g.fill(0, barY, screenW, barY + 1, BAR_BORDER);

        List<BuildingSlot> filtered = getFilteredSlots();

        // Category tabs + search on same line
        List<String> categories = getCategories();
        int searchX = renderCategoryTabs(g, font, categories, barY, screenW, mouseX, mouseY);
        renderSearchBar(g, font, searchX, barY + 2, mouseX, mouseY);

        // Scrollable multi-row building grid
        int gridY = barY + GRID_TOP_OFFSET;
        int cols = Math.max(1, (screenW - GRID_PAD_X * 2 - SCROLLBAR_W) / CELL_W);
        renderBuildingGrid(g, font, filtered, GRID_PAD_X, gridY, cols, screenW, mouseX, mouseY);
        renderScrollbar(g, filtered, cols, screenW, barY, gridY);
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Hit testing ──
    // ═══════════════════════════════════════════════════════════════

    public static int getBarY(int screenH) {
        return screenH - WandscapePanelController.BOTTOM_BAR_HEIGHT - BAR_HEIGHT;
    }

    public static int getSlotAt(double mouseX, double mouseY, int screenW, int screenH) {
        if (!isActive()) return -1;
        int barY = getBarY(screenH);
        int gridY = barY + GRID_TOP_OFFSET;
        if (mouseY < gridY || mouseY >= barY + BAR_HEIGHT) return -1;

        List<BuildingSlot> filtered = getFilteredSlots();
        int cols = Math.max(1, (screenW - GRID_PAD_X * 2 - SCROLLBAR_W) / CELL_W);
        int scrollOffset = WandscapePanelState.getBuildingBarScrollOffset();
        int col = (int) ((mouseX - GRID_PAD_X) / CELL_W);
        int row = (int) ((mouseY - gridY) / CELL_H);
        if (col < 0 || col >= cols || row < 0 || row >= VISIBLE_ROWS) return -1;

        int index = (scrollOffset + row) * cols + col;
        if (index < 0 || index >= filtered.size()) return -1;

        BuildingSlot slot = filtered.get(index);
        List<BuildingSlot> all = ProjectionClientState.getBuildingSlots();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(slot.id())) return i;
        }
        return -1;
    }

    public static int getCategoryAt(double mouseX, double mouseY, int screenW, int screenH) {
        if (!isActive()) return -1;
        int barY = getBarY(screenH);
        if (mouseY < barY || mouseY >= barY + CATEGORY_ROW_H) return -1;

        List<String> cats = getCategories();
        int x = GRID_PAD_X;
        Font font = Minecraft.getInstance().font;
        int searchX = screenW - GRID_PAD_X - SEARCH_W;
        for (int i = 0; i < cats.size(); i++) {
            int w = font.width(cats.get(i)) + 10;
            if (x + w > searchX - 4) break;
            if (mouseX >= x && mouseX < x + w) return i;
            x += w + 2;
        }
        return -1;
    }

    /** @return the maximum scroll offset (in rows) for the current filtered set. */
    public static int getMaxScrollOffset() {
        List<BuildingSlot> filtered = getFilteredSlots();
        if (filtered.isEmpty()) return 0;
        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int cols = Math.max(1, (screenW - GRID_PAD_X * 2 - SCROLLBAR_W) / CELL_W);
        int totalRows = (filtered.size() + cols - 1) / cols;
        return Math.max(0, totalRows - VISIBLE_ROWS);
    }

    /** @return true if the mouse is over the scrollbar area. */
    public static boolean isOverScrollbar(double mouseX, int screenW) {
        int scrollbarX = screenW - GRID_PAD_X - SCROLLBAR_W;
        return mouseX >= scrollbarX && mouseX < scrollbarX + SCROLLBAR_W;
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Rendering helpers ──
    // ═══════════════════════════════════════════════════════════════

    private static List<String> getCategories() {
        Set<String> seen = new LinkedHashSet<>();
        seen.add("All");
        for (BuildingSlot slot : ProjectionClientState.getBuildingSlots()) {
            seen.add(slot.category());
        }
        return new ArrayList<>(seen);
    }

    private static List<BuildingSlot> getFilteredSlots() {
        String cat = WandscapePanelState.getBuildingBarCategory();
        String search = WandscapePanelState.getBuildingBarSearch().toLowerCase();
        return ProjectionClientState.getBuildingSlots().stream()
                .filter(s -> "All".equals(cat) || s.category().equals(cat))
                .filter(s -> search.isEmpty() || s.displayName().toLowerCase().contains(search))
                .toList();
    }

    private static int renderCategoryTabs(GuiGraphics g, Font font, List<String> cats,
                                           int barY, int screenW, double mouseX, double mouseY) {
        int x = GRID_PAD_X;
        int y = barY + 1;
        int searchX = screenW - GRID_PAD_X - SEARCH_W;
        String activeCat = WandscapePanelState.getBuildingBarCategory();

        for (String cat : cats) {
            int w = font.width(cat) + 10;
            if (x + w > searchX - 4) break;
            boolean active = cat.equals(activeCat);
            boolean hovered = mouseY >= barY && mouseY < barY + CATEGORY_ROW_H
                    && mouseX >= x && mouseX < x + w;

            int bg = active ? CAT_SELECTED_BG : (hovered ? CAT_HOVER_BG : CAT_INACTIVE_BG);
            int textColor = active ? CAT_TEXT_SELECTED : CAT_TEXT_NORMAL;

            g.fill(x, y, x + w, y + CATEGORY_ROW_H - 2, bg);
            g.drawString(font, cat, x + 5, y + 2, textColor);
            x += w + 2;
        }

        return searchX;
    }

    private static void renderSearchBar(GuiGraphics g, Font font, int x, int y,
                                         double mouseX, double mouseY) {
        g.fill(x, y, x + SEARCH_W, y + SEARCH_H, 0xFF2A1A0A);

        String text = WandscapePanelState.getBuildingBarSearch();
        String display = text.isEmpty() ? "Search" : text;
        int textColor = text.isEmpty() ? 0xFF666666 : 0xFFFFFFFF;

        int maxChars = (SEARCH_W - 4) / 6;
        if (display.length() > maxChars) {
            display = display.substring(0, maxChars);
        }
        g.drawString(font, display, x + 3, y + 2, textColor);
    }

    private static void renderBuildingGrid(GuiGraphics g, Font font, List<BuildingSlot> slots,
                                            int gridX, int gridY, int cols, int screenW,
                                            double mouseX, double mouseY) {
        int selectedIdx = WandscapePanelState.getBuildingBarSelectedIndex();
        String selectedId = null;
        var allSlots = ProjectionClientState.getBuildingSlots();
        if (selectedIdx >= 0 && selectedIdx < allSlots.size()) {
            selectedId = allSlots.get(selectedIdx).id();
        }

        int scrollOffset = WandscapePanelState.getBuildingBarScrollOffset();
        int totalRows = (slots.size() + cols - 1) / cols;
        int startRow = Math.min(scrollOffset, Math.max(0, totalRows - VISIBLE_ROWS));
        int endRow = Math.min(startRow + VISIBLE_ROWS, totalRows);

        // Pass 1: render cell backgrounds only
        for (int row = startRow; row < endRow; row++) {
            for (int col = 0; col < cols; col++) {
                int i = row * cols + col;
                if (i >= slots.size()) break;

                int cellX = gridX + col * CELL_W;
                int cellY = gridY + (row - startRow) * CELL_H;

                BuildingSlot slot = slots.get(i);
                boolean selected = slot.id().equals(selectedId);
                boolean hovered = mouseX >= cellX && mouseX < cellX + CELL_W
                        && mouseY >= cellY && mouseY < cellY + CELL_H;

                int bg = selected ? CELL_SELECTED_BG : (hovered ? CELL_HOVER_BG : CELL_BG);
                g.fill(cellX, cellY, cellX + CELL_W - 2, cellY + CELL_H - 2, bg);
            }
        }

        // Flush cell backgrounds before 3D preview so blocks render on top
        g.bufferSource().endBatch(net.minecraft.client.renderer.RenderType.gui());

        // Pass 2: 3D previews + labels (rendered on top of backgrounds)
        for (int row = startRow; row < endRow; row++) {
            for (int col = 0; col < cols; col++) {
                int i = row * cols + col;
                if (i >= slots.size()) break;

                int cellX = gridX + col * CELL_W;
                int cellY = gridY + (row - startRow) * CELL_H;

                BuildingSlot slot = slots.get(i);
                boolean selected = slot.id().equals(selectedId);

                BuildingConfig config = BuildingConfigLoader.getInstance().get(slot.id());
                if (config != null) {
                    int px = cellX + PREVIEW_PAD;
                    int py = cellY + PREVIEW_PAD;
                    int pw = CELL_W - PREVIEW_PAD * 2;
                    int ph = CELL_H - NAME_H - PREVIEW_PAD;
                    BuildingPreviewRenderer.renderPreview(g, config, px, py, pw, ph);
                } else {
                    Log.warn(TAG, "[Bar] Config not found for slot '{}'", slot.id());
                    g.drawCenteredString(font, "?", cellX + CELL_W / 2, cellY + (CELL_H - NAME_H) / 2 - 4, 0xFF666666);
                }

                // Truncated name
                String name = slot.displayName();
                int nameW = font.width(name);
                if (nameW > CELL_W - 4) {
                    while (nameW > CELL_W - 8 && name.length() > 1) {
                        name = name.substring(0, name.length() - 1);
                        nameW = font.width(name + ".");
                    }
                    name = name + ".";
                }
                int nameX = cellX + (CELL_W - nameW) / 2;
                int nameY = cellY + CELL_H - 12;
                g.drawString(font, name, nameX, nameY, selected ? TEXT_WHITE : TEXT_DIM);
            }
        }
    }

    private static void renderScrollbar(GuiGraphics g, List<BuildingSlot> slots, int cols,
                                         int screenW, int barY, int gridY) {
        int totalRows = (slots.size() + cols - 1) / cols;
        if (totalRows <= VISIBLE_ROWS) return; // No scrollbar needed

        int scrollOffset = WandscapePanelState.getBuildingBarScrollOffset();
        int maxScroll = totalRows - VISIBLE_ROWS;
        float ratio = maxScroll > 0 ? (float) scrollOffset / maxScroll : 0;

        int gridH = BAR_HEIGHT - GRID_TOP_OFFSET;
        int scrollbarX = screenW - GRID_PAD_X - SCROLLBAR_W;

        // Track
        g.fill(scrollbarX, gridY, scrollbarX + SCROLLBAR_W, gridY + gridH, SCROLLBAR_TRACK);

        // Thumb
        int thumbH = Math.max(12, gridH * VISIBLE_ROWS / totalRows);
        int thumbY = gridY + (int) ((gridH - thumbH) * ratio);
        g.fill(scrollbarX + 1, thumbY, scrollbarX + SCROLLBAR_W - 1, thumbY + thumbH, SCROLLBAR_THUMB);
    }

}
