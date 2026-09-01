package com.wsteam.wandscape.content.building.ui;
import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelState;
import com.wsteam.wandscape.foundation.ui.panel.WandscapePanelOverlay;

import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.content.building.internal.BuildingUnlockChecker;
import com.wsteam.wandscape.content.building.projection.client.ProjectionClientState;
import com.wsteam.wandscape.content.building.projection.data.BuildingSlot;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.content.building.preview.BuildingPreviewGifCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

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

    public static final int BAR_HEIGHT = 58;
    public static final int CATEGORY_ROW_H = 16;
    public static final int GRID_TOP_OFFSET = CATEGORY_ROW_H + 2;
    static final int CELL_W = 42;
    static final int CELL_H = 36;
    static final int PREVIEW_PAD = 2;  // margin inside cell for 3D preview
    static final int NAME_H = 10;      // reserved height for building name
    static final int GRID_PAD_X = 4;
    static final int SEARCH_W = 80;
    static final int SEARCH_H = 12;
    static final int SCROLLBAR_W = 6;

    private static final int GRID_LEFT = WandscapePanelOverlay.SIDEBAR_W + GRID_PAD_X; // Clear sidebar
    private static final int VISIBLE_ROWS = (BAR_HEIGHT - GRID_TOP_OFFSET) / CELL_H;

    private static final int BAR_BG = 0xEE14161C;
    private static final int BAR_BORDER = 0xFF3A3E47;
    private static final int CAT_SELECTED_BG = 0xFF2B62C8;
    private static final int CAT_HOVER_BG = 0xFF282C34;
    private static final int CAT_INACTIVE_BG = 0xFF15181C;
    private static final int CAT_TEXT_SELECTED = 0xFFFFFFFF;
    private static final int CAT_TEXT_NORMAL = 0xFFAAAAAA;
    private static final int CELL_BG = 0xFF1C1F26;
    private static final int CELL_SELECTED_BG = 0xFF2B62C8;
    private static final int CELL_HOVER_BG = 0xFF282C34;
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
        Minecraft mc = Minecraft.getInstance();
        boolean rightDown = false;
        if (mc != null && mc.getWindow() != null && mc.getWindow().getWindow() != 0L) {
            rightDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                    mc.getWindow().getWindow(),
                    org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        }
        return WandscapePanelState.isPanelOpen()
                && ProjectionClientState.isProjecting()
                && WandscapePanelState.getActiveSubMode() == WandscapePanelState.SubMode.BUILD_PROJECTION
                && WandscapePanelState.isBuildingBarOpen()
                && !rightDown
                && !ProjectionClientState.isPinned();
    }

    static int getSlotsSize() {
        return ProjectionClientState.getBuildingSlots().size();
    }

    public static void render(GuiGraphics g, Font font, int screenW, int screenH,
                               double mouseX, double mouseY) {
        if (!isActive()) {
            return;
        }

        int barY = screenH - BAR_HEIGHT;

        // Background
        com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme.drawRtsBox(g, 0, barY, screenW, BAR_HEIGHT, false, false);

        List<BuildingSlot> filtered = getFilteredSlots();

        // Category tabs + search on same line
        List<String> categories = getCategories();
        int searchX = renderCategoryTabs(g, font, categories, barY, screenW, mouseX, mouseY);
        renderSearchBar(g, font, searchX, barY + 2, mouseX, mouseY);

        // Scrollable multi-row building grid
        int gridY = barY + GRID_TOP_OFFSET;
        int cols = Math.max(1, (screenW - GRID_LEFT - GRID_PAD_X - SCROLLBAR_W) / CELL_W);
        renderBuildingGrid(g, font, filtered, GRID_LEFT, gridY, cols, screenW, mouseX, mouseY);
        renderScrollbar(g, filtered, cols, screenW, barY, gridY);
    }

    // ═══════════════════════════════════════════════════════════════
    // ── Hit testing ──
    // ═══════════════════════════════════════════════════════════════

    public static int getBarY(int screenH) {
        return screenH - BAR_HEIGHT;
    }

    /** Check if the given slot's building type is unlocked for the current colony. */
    private static boolean isSlotUnlocked(BuildingSlot slot) {
        UUID colonyId = WandscapePanelState.getColonyId();
        BuildingConfig config = BuildingConfigLoader.getInstance().get(slot.id());
        if (config == null) return false;
        return BuildingUnlockChecker.isUnlocked(colonyId, config);
    }

    public static int getSlotAt(double mouseX, double mouseY, int screenW, int screenH) {
        if (!isActive()) return -1;
        int barY = getBarY(screenH);
        int gridY = barY + GRID_TOP_OFFSET;
        if (mouseY < gridY || mouseY >= barY + BAR_HEIGHT) return -1;

        List<BuildingSlot> filtered = getFilteredSlots();
        int cols = Math.max(1, (screenW - GRID_LEFT - GRID_PAD_X - SCROLLBAR_W) / CELL_W);
        int scrollOffset = WandscapePanelState.getBuildingBarScrollOffset();
        int col = (int) ((mouseX - GRID_LEFT) / CELL_W);
        int row = (int) ((mouseY - gridY) / CELL_H);
        if (col < 0 || col >= cols || row < 0 || row >= VISIBLE_ROWS) return -1;

        int index = (scrollOffset + row) * cols + col;
        if (index < 0 || index >= filtered.size()) return -1;

        BuildingSlot slot = filtered.get(index);
        if (!isSlotUnlocked(slot)) return -1;

        List<BuildingSlot> all = ProjectionClientState.getBuildingSlots();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(slot.id())) return i;
        }
        return -1;
    }

    /** 分类标签从左到右：全部 + 基础设施（市政厅/仓库/工作站等未单列者）+ 其余专属标签。 */
    private static final List<String> CATEGORY_TABS = List.of(
            "infrastructure", "node", "decoration", "shop", "service", "relax", "atm"
    );

    public static String getCategoryDisplayName(String cat) {
        if (cat == null) cat = "infrastructure";
        return com.wsteam.wandscape.foundation.ui.I18n.name("category.wandscape." + cat.toLowerCase(), cat).getString();
    }

    /** @return true if the mouse is over the search box (click focuses it for keyboard input). */
    public static boolean isOverSearchBox(double mouseX, double mouseY, int screenW, int screenH) {
        if (!isActive()) return false;
        int barY = getBarY(screenH);
        int searchX = screenW - GRID_PAD_X - SEARCH_W;
        return mouseX >= searchX && mouseX <= searchX + SEARCH_W
                && mouseY >= barY + 2 && mouseY <= barY + 2 + SEARCH_H;
    }

    public static int getCategoryAt(double mouseX, double mouseY, int screenW, int screenH) {
        if (!isActive()) return -1;
        int barY = getBarY(screenH);
        if (mouseY < barY || mouseY >= barY + CATEGORY_ROW_H) return -1;

        List<String> cats = getCategories();
        int x = GRID_LEFT;
        Font font = Minecraft.getInstance().font;
        int searchX = screenW - GRID_PAD_X - SEARCH_W;
        for (int i = 0; i < cats.size(); i++) {
            String label = getCategoryDisplayName(cats.get(i));
            int w = font.width(label) + 8;
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
        int cols = Math.max(1, (screenW - GRID_LEFT - GRID_PAD_X - SCROLLBAR_W) / CELL_W);
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

    public static List<String> getCategories() {
        Set<String> present = new LinkedHashSet<>();
        for (BuildingSlot slot : ProjectionClientState.getBuildingSlots()) {
            present.add(BuildingSort.tabOf(slot.category()));
        }
        List<String> sorted = new ArrayList<>();
        sorted.add("All");
        for (String tab : CATEGORY_TABS) {
            if (present.contains(tab)) {
                sorted.add(tab);
            }
        }
        return sorted;
    }

    private static List<BuildingSlot> getFilteredSlots() {
        String cat = WandscapePanelState.getBuildingBarCategory();
        String search = WandscapePanelState.getBuildingBarSearch().toLowerCase();
        return ProjectionClientState.getBuildingSlots().stream()
                .filter(s -> "All".equals(cat) || matchesCategory(s.category(), cat))
                .filter(s -> search.isEmpty() || s.displayName().toLowerCase().contains(search))
                .sorted(BuildingSelectionOverlay::compareSlots)
                .toList();
    }

    /** A slot belongs to the selected tab when its merged tab matches. */
    private static boolean matchesCategory(String slotCategory, String selectedTab) {
        return BuildingSort.tabOf(slotCategory).equals(selectedTab);
    }

    /** 排序：解锁等级 → 种类 → 显示名（中文按拼音，英文按字母）。 */
    private static int compareSlots(BuildingSlot a, BuildingSlot b) {
        return BuildingSort.compare(unlockLevel(a), a.category(), sortName(a),
                unlockLevel(b), b.category(), sortName(b));
    }

    private static int unlockLevel(BuildingSlot slot) {
        BuildingConfig config = BuildingConfigLoader.getInstance().get(slot.id());
        if (config == null) return 1;
        return config.unlockRequirement().minColonyLevel();
    }

    private static String sortName(BuildingSlot slot) {
        return com.wsteam.wandscape.foundation.ui.I18n.name(
                "building.wandscape." + slot.id(), slot.displayName()).getString();
    }

    private static int renderCategoryTabs(GuiGraphics g, Font font, List<String> cats,
                                           int barY, int screenW, double mouseX, double mouseY) {
        int x = GRID_LEFT;
        int y = barY + 1;
        int searchX = screenW - GRID_PAD_X - SEARCH_W;
        String activeCat = WandscapePanelState.getBuildingBarCategory();

        for (String cat : cats) {
            String label = getCategoryDisplayName(cat);
            int w = font.width(label) + 8;
            if (x + w > searchX - 4) break;
            boolean active = cat.equals(activeCat);
            boolean hovered = mouseY >= barY && mouseY < barY + CATEGORY_ROW_H
                    && mouseX >= x && mouseX < x + w;

            com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme.drawRtsBox(g, x, y, w, CATEGORY_ROW_H - 2, active, hovered);
            int textColor = active ? com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme.COLOR_TEXT_NORMAL : com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme.COLOR_TEXT_DIM;
            g.drawString(font, label, x + 4, y + 2, textColor);
            x += w + 2;
        }

        return searchX;
    }

    private static void renderSearchBar(GuiGraphics g, Font font, int x, int y,
                                         double mouseX, double mouseY) {
        boolean focused = WandscapePanelState.isBuildingBarSearchFocused();
        com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme.drawRtsBox(g, x, y, SEARCH_W, SEARCH_H, focused, false);

        String text = WandscapePanelState.getBuildingBarSearch();
        // Placeholder hint only shows while unfocused; a focused empty box shows just the cursor
        String display = text.isEmpty()
                ? (focused ? "" : com.wsteam.wandscape.foundation.ui.I18n.name("gui.wandscape.common.search", "Search").getString())
                : text;
        int textColor = text.isEmpty() ? 0xFF666666 : 0xFFFFFFFF;

        int maxChars = (SEARCH_W - 4) / 6;
        if (display.length() > maxChars) {
            display = display.substring(0, maxChars);
        }
        g.drawString(font, display, x + 3, y + 2, textColor);
        if (focused) {
            g.fill(net.minecraft.client.renderer.RenderType.guiOverlay(),
                    x + 3 + font.width(display), y + 2, x + 4 + font.width(display), y + SEARCH_H - 2,
                    0, 0xFFFFFFFF);
        }
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
                boolean locked = !isSlotUnlocked(slot);
                boolean selected = slot.id().equals(selectedId) && !locked;
                boolean hovered = mouseX >= cellX && mouseX < cellX + CELL_W
                        && mouseY >= cellY && mouseY < cellY + CELL_H;

                com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme.drawRtsBox(g, cellX, cellY, CELL_W - 2, CELL_H - 2, selected, hovered);
                if (selected) {
                    g.fill(net.minecraft.client.renderer.RenderType.guiOverlay(), cellX, cellY + CELL_H - 4,
                            cellX + CELL_W - 2, cellY + CELL_H - 2, 0, com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme.COLOR_WONDER);
                }
            }
        }

        // Flush cell backgrounds before thumbnails so they render on top
        g.bufferSource().endBatch(net.minecraft.client.renderer.RenderType.gui());

        // Pass 2: Blit cached building thumbnail frames (pure 2D; baking runs on the central per-frame pump)
        g.flush();
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();

        for (int row = startRow; row < endRow; row++) {
            for (int col = 0; col < cols; col++) {
                int i = row * cols + col;
                if (i >= slots.size()) break;

                int cellX = gridX + col * CELL_W;
                int cellY = gridY + (row - startRow) * CELL_H;

                // Viewport Culling: Skip cells outside screen bounds
                if (cellX + CELL_W < GRID_LEFT || cellX > screenW - SCROLLBAR_W) {
                    continue;
                }

                BuildingSlot slot = slots.get(i);
                boolean locked = !isSlotUnlocked(slot);

                BuildingConfig config = BuildingConfigLoader.getInstance().get(slot.id());
                if (config != null && !locked) {
                    BuildingPreviewGifCache.request(config);
                    ResourceLocation frameLoc = BuildingPreviewGifCache.getFrameLocation(config);
                    if (frameLoc != null) {
                        int px = cellX + PREVIEW_PAD;
                        int py = cellY + PREVIEW_PAD;
                        int pw = CELL_W - PREVIEW_PAD * 2;
                        int ph = CELL_H - NAME_H - PREVIEW_PAD;
                        int size = Math.min(pw, ph);
                        int bx = px + (pw - size) / 2;
                        int by = py + (ph - size) / 2;
                        g.blit(frameLoc, bx, by, size, size, 0.0F, 0.0F,
                                BuildingPreviewGifCache.RES, BuildingPreviewGifCache.RES,
                                BuildingPreviewGifCache.RES, BuildingPreviewGifCache.RES);
                    }
                }
            }
        }

        com.mojang.blaze3d.systems.RenderSystem.disableBlend();

        g.bufferSource().endBatch();
        com.mojang.blaze3d.platform.Lighting.setupForFlatItems();
        com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();

        // Pass 3: Labels, lock overlays and badges (2D GUI pass)
        for (int row = startRow; row < endRow; row++) {
            for (int col = 0; col < cols; col++) {
                int i = row * cols + col;
                if (i >= slots.size()) break;

                int cellX = gridX + col * CELL_W;
                int cellY = gridY + (row - startRow) * CELL_H;

                BuildingSlot slot = slots.get(i);
                boolean locked = !isSlotUnlocked(slot);
                boolean selected = slot.id().equals(selectedId) && !locked;
                BuildingConfig config = BuildingConfigLoader.getInstance().get(slot.id());

                if (config == null) {
                    Log.warn(TAG, "[Bar] Config not found for slot '{}'", slot.id());
                    g.drawCenteredString(font, "?", cellX + CELL_W / 2, cellY + (CELL_H - NAME_H) / 2 - 4, 0xFF666666);
                }

                // Locked overlay: darken cell + show lock icon + level requirement
                if (locked) {
                    g.fill(net.minecraft.client.renderer.RenderType.guiOverlay(),
                            cellX, cellY, cellX + CELL_W - 2, cellY + CELL_H - 2,
                            0, 0x88000000);
                    int lockS = 10;
                    int lockX = cellX + CELL_W - lockS - 5;
                    int lockY = cellY + 4;
                    com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme.drawIcon(
                            g,
                            com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme.ICON_LOCK,
                            lockX, lockY, lockS, lockS,
                            0xFFFFFFFF);
                    // Required level text centered below the lock icon
                    if (config != null && config.unlockRequirement() != BuildingConfig.UnlockRequirement.NONE) {
                        String lvlText = "Lv." + config.unlockRequirement().minColonyLevel();
                        g.drawCenteredString(font, lvlText,
                                cellX + CELL_W / 2,
                                cellY + (CELL_H - NAME_H) / 2 + 2,
                                0xFFAAAAAA);
                    }
                }

                // First-free still available: green "首免" badge top-left
                if (!locked && slot.firstFreeAvailable()) {
                    String tag = com.wsteam.wandscape.foundation.ui.I18n.name(
                            "gui.wandscape.badge.first_free", "First Free").getString();
                    int tagW = font.width(tag) + 4;
                    int tagH = 9;
                    int tagX = cellX + 1;
                    int tagY = cellY + 1;
                    g.fill(net.minecraft.client.renderer.RenderType.guiOverlay(),
                            tagX, tagY, tagX + tagW, tagY + tagH, 0, 0xEE1B5E20);
                    g.fill(net.minecraft.client.renderer.RenderType.guiOverlay(),
                            tagX, tagY, tagX + tagW, tagY + 1, 0, 0xFF66BB6A);
                    g.drawString(font, tag, tagX + 2, tagY + 1, 0xFFFFFFFF);
                }

                // Localized name (lang key, fallback to display_name); truncate by component width
                net.minecraft.network.chat.Component nameComp =
                        com.wsteam.wandscape.foundation.ui.I18n.name("building.wandscape." + slot.id(), slot.displayName());
                int nameW = font.width(nameComp);
                if (nameW > CELL_W - 4) {
                    String text = nameComp.getString();
                    int tW = font.width(text);
                    while (tW > CELL_W - 8 && text.length() > 1) {
                        text = text.substring(0, text.length() - 1);
                        tW = font.width(text + ".");
                    }
                    nameComp = net.minecraft.network.chat.Component.literal(text + ".");
                    nameW = font.width(nameComp);
                }
                int nameX = cellX + (CELL_W - nameW) / 2;
                int nameY = cellY + CELL_H - 12;
                boolean hovered = mouseX >= cellX && mouseX < cellX + CELL_W
                        && mouseY >= cellY && mouseY < cellY + CELL_H;
                int nameColor;
                if (locked) {
                    nameColor = 0xFF666666;
                } else {
                    nameColor = selected ? com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme.COLOR_WONDER : (hovered ? com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme.COLOR_TEXT_NORMAL : com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme.COLOR_TEXT_DIM);
                }
                g.drawString(font, nameComp, nameX, nameY, nameColor);
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
        g.fill(net.minecraft.client.renderer.RenderType.guiOverlay(), scrollbarX, gridY, scrollbarX + SCROLLBAR_W, gridY + gridH, 0, com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme.COLOR_BG_MAIN);

        // Thumb
        int thumbH = Math.max(12, gridH * VISIBLE_ROWS / totalRows);
        int thumbY = gridY + (int) ((gridH - thumbH) * ratio);
        g.fill(net.minecraft.client.renderer.RenderType.guiOverlay(), scrollbarX + 1, thumbY, scrollbarX + SCROLLBAR_W - 1, thumbY + thumbH, 0, com.wsteam.wandscape.foundation.ui.theme.WandscapeTheme.COLOR_BORDER_NORMAL);
    }

}
