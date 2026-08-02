package com.wsteam.wandscape.road.client;

import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.wsteam.wandscape.road.core.SplinePoint;
import com.wsteam.wandscape.road.core.SplineVec3;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.ui.component.TabBar;
import com.wsteam.wandscape.shared.ui.theme.MedievalColors;
import com.wsteam.wandscape.shared.ui.theme.WandscapeTheme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Native medieval-styled spline road editor panel, drawn as a HUD overlay on
 * the right side of the screen while {@link SplineEditorClientState#isEditing()}.
 *
 * <p>Replaces the former ImGui {@code SplineEditorImGui} panel. All controls are
 * drawn with the shared {@code shared/ui} theme (static draw + hit-test, the same
 * convention as {@code RoadPlacementOverlay}); the tab strip reuses {@link TabBar}.
 * World interaction (point picking / gizmo drag / freecam) stays in
 * {@code SplineEditorController} / {@code SplineEditorInputHandler} and is untouched.
 */
public final class SplineEditorOverlay {
    private static final String TAG = "SplineEditorOverlay";

    // ── Layout ──
    private static final int PANEL_W = 360;
    private static final int PANEL_MARGIN = 6;
    private static final int HEADER_H = 34;
    private static final int PAD = 10;
    private static final int BTN_H = 24;
    private static final int FIELD_H = 18;
    private static final int ROW_H = 18;
    private static final int POINT_LIST_ROWS = 5;
    /** Reserve room at the bottom for the ROAD placement bar while it is visible. */
    private static final int ROAD_BAR_RESERVE = 112;

    private static int panelX, panelY, panelW, panelH;

    // ── Tabs ──
    private static final String[] TABS = {"曲线编辑", "阵列生成", "模板工具"};
    private static final TabBar tabBar = new TabBar(0, 0, 0, List.of(TABS), 0, idx -> {});
    private static int tabIndex = 0;

    // ── Local field state (shift deltas, template name) ──
    private static double shiftX = 0, shiftY = 0, shiftZ = 0;
    private static String templateName = "";

    // ── Focused input field ──
    private static final int FIELD_SHIFT_X = 0, FIELD_SHIFT_Y = 1, FIELD_SHIFT_Z = 2;
    private static final int FIELD_COORD_X = 3, FIELD_COORD_Y = 4, FIELD_COORD_Z = 5;
    private static final int FIELD_STEP = 6;
    private static final int FIELD_NAME = 7;
    private static int activeField = -1;
    private static String fieldBuffer = "";

    // ── Slider drag state ──
    private static final int SLIDER_WIDTH = 0, SLIDER_DEPTH = 1, SLIDER_ROLL = 2, SLIDER_PITCH = 3, SLIDER_YAW = 4;
    private static int dragSlider = -1;
    private static int dragSliderX, dragSliderW;

    // ── Scroll state ──
    private static int pointListScroll = 0;
    private static int templateListScroll = 0;

    private static boolean registered = false;

    private SplineEditorOverlay() {}

    public static void register() {
        if (registered) return;
        registered = true;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(SplineEditorOverlay.class);
        Log.info(TAG, "[SplineEditor] Overlay registered");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Public helpers consumed by SplineEditorController
    // ═══════════════════════════════════════════════════════════════

    /** True if the cursor (GUI scale) currently sits over the spline panel. */
    public static boolean wantsMouseAt() {
        computePanelRect();
        double mx = guiMX(), my = guiMY();
        return mx >= panelX && mx <= panelX + panelW && my >= panelY && my <= panelY + panelH;
    }

    /** True while a text/number field is focused — blocks editor hotkeys. */
    public static boolean hasActiveTextInput() {
        return activeField != -1;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Coordinates / layout
    // ═══════════════════════════════════════════════════════════════

    private static double guiMX() {
        Minecraft mc = Minecraft.getInstance();
        return mc.mouseHandler.xpos() / mc.getWindow().getGuiScale();
    }

    private static double guiMY() {
        Minecraft mc = Minecraft.getInstance();
        return mc.mouseHandler.ypos() / mc.getWindow().getGuiScale();
    }

    private static void computePanelRect() {
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        panelW = Math.min(PANEL_W, sw - PANEL_MARGIN * 2);
        panelX = sw - panelW - PANEL_MARGIN;
        panelY = PANEL_MARGIN;
        int reserve = RoadPlacementState.isProjecting() ? ROAD_BAR_RESERVE : PANEL_MARGIN;
        panelH = sh - PANEL_MARGIN - reserve;
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Events
    // ═══════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onRenderGuiPost(RenderGuiEvent.Post event) {
        if (!SplineEditorClientState.isEditing()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.level == null) return;
        computePanelRect();

        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        double mx = guiMX(), my = guiMY();

        // Panel background + header
        WandscapeTheme.drawRtsBox(g, panelX, panelY, panelW, panelH, true, false);
        g.fill(RenderType.guiOverlay(), panelX, panelY, panelX + panelW, panelY + 1, 0, WandscapeTheme.COLOR_BORDER_ACTIVE);

        SplineModelHost model = new SplineModelHost();
        String modeStr = SplineEditorClientState.getEditMode() == SplineEditorClientState.EditMode.ADD ? "添加" : "编辑";
        String viewStr = SplineEditorClientState.isTopDown() ? "2D 俯瞰" : "3D 自由";
        g.drawString(font, "WANDSCAPE 道路制作工坊", panelX + PAD, panelY + 6, MedievalColors.ACCENT_GOLD);
        g.drawString(font, "节点: " + model.points().size() + "  |  模式: " + modeStr + "  |  视角: " + viewStr,
                panelX + PAD, panelY + 20, MedievalColors.TEXT_MUTED);

        // Tab strip
        tabBar.setX(panelX + PAD);
        tabBar.setY(panelY + HEADER_H);
        tabBar.setWidth(panelW - PAD * 2);
        tabBar.setSelectedIndex(tabIndex);
        g.bufferSource().endBatch(RenderType.guiOverlay());
        tabBar.render(g, (int) mx, (int) my, 0f);

        int contentY = panelY + HEADER_H + tabBar.getHeight() + 8;
        switch (tabIndex) {
            case 0 -> drawTabCurve(g, font, contentY, mx, my);
            case 1 -> drawTabArray(g, font, contentY, mx, my);
            default -> drawTabTemplates(g, font, contentY, mx, my);
        }
    }

    @SubscribeEvent
    public static void onMouseButtonPre(InputEvent.MouseButton.Pre event) {
        if (!SplineEditorClientState.isEditing()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        if (event.getButton() != 0) return; // panel widgets are left-click only; RMB drives the camera
        computePanelRect();
        double mx = guiMX(), my = guiMY();

        if (event.getAction() == GLFW.GLFW_RELEASE) {
            if (dragSlider != -1) dragSlider = -1;
            return;
        }
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        if (!inRect(mx, my, panelX, panelY, panelW, panelH)) {
            if (activeField != -1) commitField();
            return;
        }

        // Tab strip
        int tabH = tabBar.getHeight();
        int tabY = panelY + HEADER_H;
        if (inRect(mx, my, panelX + PAD, tabY, panelW - PAD * 2, tabH)) {
            float tw = (float) (panelW - PAD * 2) / TABS.length;
            int idx = (int) ((mx - (panelX + PAD)) / tw);
            if (idx >= 0 && idx < TABS.length && idx != tabIndex) {
                tabIndex = idx;
                tabBar.setSelectedIndex(idx);
                clearTransientState();
            }
            event.setCanceled(true);
            return;
        }

        int contentY = tabY + tabH + 8;
        boolean consumed = switch (tabIndex) {
            case 0 -> clickTabCurve(mx, my, contentY);
            case 1 -> clickTabArray(mx, my, contentY);
            default -> clickTabTemplates(mx, my, contentY);
        };
        if (consumed) {
            event.setCanceled(true);
        } else if (activeField != -1) {
            // Clicked empty panel space — commit pending field
            commitField();
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!SplineEditorClientState.isEditing()) return;
        if (Minecraft.getInstance().screen != null) return;
        computePanelRect();
        double mx = guiMX(), my = guiMY();
        if (!inRect(mx, my, panelX, panelY, panelW, panelH)) return;

        double delta = event.getScrollDeltaY();
        boolean changed = false;
        if (tabIndex == 0) {
            int max = Math.max(0, SplineEditorClientState.getModel().getPoints().size() - POINT_LIST_ROWS);
            pointListScroll = (int) Math.clamp(pointListScroll + (delta > 0 ? -1 : 1), 0, max);
            changed = true;
        } else if (tabIndex == 1 && SplineEditorClientState.getTemplateSourceMode() == SplineEditorClientState.TemplateSourceMode.JSON_FILE) {
            List<String> ids = SplineEditorClientState.getAvailableTemplateIds();
            int max = Math.max(0, ids.size() - POINT_LIST_ROWS);
            templateListScroll = (int) Math.clamp(templateListScroll + (delta > 0 ? -1 : 1), 0, max);
            changed = true;
        }
        if (changed) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (!SplineEditorClientState.isEditing()) return;
        if (activeField == -1) return;
        if (Minecraft.getInstance().screen != null) return;
        if (event.getAction() != GLFW.GLFW_PRESS && event.getAction() != GLFW.GLFW_REPEAT) return;

        int key = event.getKey();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            activeField = -1;
            fieldBuffer = "";
            return;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            commitField();
            return;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (!fieldBuffer.isEmpty()) {
                fieldBuffer = fieldBuffer.substring(0, fieldBuffer.length() - 1);
            }
            return;
        }
        char c = keyToChar(key, event.getModifiers());
        if (c != 0 && fieldBuffer.length() < 24) {
            fieldBuffer += c;
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!SplineEditorClientState.isEditing()) return;
        if (dragSlider == -1) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.level == null) return;
        long window = mc.getWindow().getWindow();
        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            dragSlider = -1;
            return;
        }
        applySliderDrag(dragSlider, guiMX());
    }

    // ═══════════════════════════════════════════════════════════════
    //  Primitive drawing helpers
    // ═══════════════════════════════════════════════════════════════

    private static void drawButton(GuiGraphics g, Font font, int x, int y, int w, int h,
                                   String label, boolean active, double mx, double my) {
        boolean hovered = inRect(mx, my, x, y, w, h);
        WandscapeTheme.drawRtsBox(g, x, y, w, h, active, hovered);
        int col = active ? WandscapeTheme.COLOR_TEXT_ACTIVE : WandscapeTheme.COLOR_TEXT_NORMAL;
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - font.lineHeight) / 2 + 1, col);
    }

    private static void drawToggle(GuiGraphics g, Font font, int x, int y, int w, int h,
                                   String label, boolean checked, double mx, double my) {
        boolean hovered = inRect(mx, my, x, y, w, h);
        int box = 12;
        int by = y + (h - box) / 2;
        WandscapeTheme.drawRtsBox(g, x, by, box, box, checked, hovered);
        if (checked) {
            g.fill(RenderType.guiOverlay(), x + 3, by + 3, x + box - 3, by + box - 3, 0, MedievalColors.BORDER_GOLD);
        }
        g.drawString(font, label, x + box + 7, y + (h - font.lineHeight) / 2, MedievalColors.TEXT_WARM_WHITE);
    }

    private static void drawSection(GuiGraphics g, Font font, int x, int y, String title) {
        g.drawString(font, title, x, y, MedievalColors.ACCENT_GOLD);
        g.fill(RenderType.guiOverlay(), x, y + font.lineHeight + 2, panelX + panelW - PAD, y + font.lineHeight + 3, 0, MedievalColors.BORDER_GOLD_DARK);
    }

    private static void drawSlider(GuiGraphics g, Font font, int x, int y, int w,
                                   String label, double min, double max, double value, String fmt) {
        g.drawString(font, label, x, y, MedievalColors.TEXT_WARM_WHITE);
        int trackY = y + 12;
        int trackH = 6;
        g.fill(RenderType.guiOverlay(), x, trackY, x + w, trackY + trackH, 0, MedievalColors.SLIDER_TRACK);
        double ratio = Math.clamp((value - min) / (max - min), 0.0, 1.0);
        int fillW = (int) (w * ratio);
        if (fillW > 0) {
            g.fill(RenderType.guiOverlay(), x, trackY, x + fillW, trackY + trackH, 0, MedievalColors.SLIDER_FILL);
        }
        String val = String.format(fmt, value);
        g.drawString(font, val, x + w - font.width(val), y, MedievalColors.TEXT_MUTED);
    }

    /** Mini numeric field: tiny label above a themed box. */
    private static void drawMiniField(GuiGraphics g, Font font, int x, int y, int w, String label,
                                      String text, boolean focused, double mx, double my) {
        g.drawString(font, label, x, y, MedievalColors.TEXT_MUTED);
        int fy = y + 10;
        boolean hovered = inRect(mx, my, x, fy, w, FIELD_H);
        WandscapeTheme.drawRtsBox(g, x, fy, w, FIELD_H, focused, hovered);
        g.drawString(font, text, x + 4, fy + 4, focused ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_MUTED);
    }

    private static void drawField(GuiGraphics g, Font font, int x, int y, int w,
                                  String label, String text, boolean focused, double mx, double my) {
        g.drawString(font, label, x, y, MedievalColors.TEXT_MUTED);
        int fy = y + 10;
        boolean hovered = inRect(mx, my, x, fy, w, FIELD_H);
        WandscapeTheme.drawRtsBox(g, x, fy, w, FIELD_H, focused, hovered);
        g.drawString(font, text, x + 4, fy + 4, focused ? MedievalColors.TEXT_WARM_WHITE : MedievalColors.TEXT_MUTED);
    }

    private static void drawListRows(GuiGraphics g, Font font, int x, int y, int w, int visible,
                                     int scroll, List<String> rows, int selectedIdx, double mx, double my) {
        int start = Math.min(scroll, Math.max(0, rows.size() - visible));
        int shown = Math.min(visible, rows.size() - start);
        for (int i = 0; i < shown; i++) {
            int idx = start + i;
            int rowY = y + i * ROW_H;
            boolean selected = idx == selectedIdx;
            boolean hovered = inRect(mx, my, x, rowY, w, ROW_H);
            if (selected) {
                g.fill(RenderType.guiOverlay(), x, rowY, x + w, rowY + ROW_H, 0, MedievalColors.PURPLE_BG);
            } else if (hovered) {
                g.fill(RenderType.guiOverlay(), x, rowY, x + w, rowY + ROW_H, 0, MedievalColors.PARCHMENT_LIGHT);
            }
            int col = selected ? MedievalColors.ACCENT_GOLD : MedievalColors.TEXT_WARM_WHITE;
            g.drawString(font, rows.get(idx), x + 3, rowY + 3, col);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Tab 0 — 曲线编辑
    // ═══════════════════════════════════════════════════════════════

    private static void drawTabCurve(GuiGraphics g, Font font, int contentY, double mx, double my) {
        int cx = panelX + PAD;
        int cw = panelW - PAD * 2;
        int cy = contentY;

        SplineModelHost model = new SplineModelHost();

        drawSection(g, font, cx, cy, "编辑模式切换");
        cy += 20;
        boolean isAdd = SplineEditorClientState.getEditMode() == SplineEditorClientState.EditMode.ADD;
        int halfW = (cw - 8) / 2;
        drawButton(g, font, cx, cy, halfW, BTN_H, "点击添加点", isAdd, mx, my);
        drawButton(g, font, cx + halfW + 8, cy, halfW, BTN_H, "选择与拖拽", !isAdd, mx, my);
        cy += BTN_H + 10;

        drawSection(g, font, cx, cy, "曲线几何与平移");
        cy += 20;
        drawToggle(g, font, cx, cy, cw, 18, "闭合环形道路 (连接首尾)", model.closed(), mx, my);
        cy += 22;
        int fw = (cw - 12) / 3;
        drawMiniField(g, font, cx, cy, fw, "X", fieldText(FIELD_SHIFT_X), activeField == FIELD_SHIFT_X, mx, my);
        drawMiniField(g, font, cx + fw + 6, cy, fw, "Y", fieldText(FIELD_SHIFT_Y), activeField == FIELD_SHIFT_Y, mx, my);
        drawMiniField(g, font, cx + (fw + 6) * 2, cy, fw, "Z", fieldText(FIELD_SHIFT_Z), activeField == FIELD_SHIFT_Z, mx, my);
        cy += 10 + FIELD_H;
        drawButton(g, font, cx, cy, cw, BTN_H, "整体平移", false, mx, my);
        cy += BTN_H + 10;

        drawSection(g, font, cx, cy, "控制点列表 (" + model.points().size() + ")");
        cy += 20;
        List<String> rows = new java.util.ArrayList<>();
        for (int i = 0; i < model.points().size(); i++) {
            SplinePoint pt = model.points().get(i);
            String sym = pt.isLocked() ? "对称" : "自由";
            rows.add(String.format("#%d  (%.1f, %.1f, %.1f) [%s]", i,
                    pt.getAnchor().x(), pt.getAnchor().y(), pt.getAnchor().z(), sym));
        }
        drawListRows(g, font, cx, cy, cw, POINT_LIST_ROWS, pointListScroll, rows,
                SplineEditorClientState.getSelectedPointIndex(), mx, my);
        cy += POINT_LIST_ROWS * ROW_H + 6;

        int sel = SplineEditorClientState.getSelectedPointIndex();
        if (sel >= 0 && sel < model.points().size()) {
            drawSection(g, font, cx, cy, "节点属性检查器 #" + sel);
            cy += 20;

            SplineEditorClientState.SelectionType st = SplineEditorClientState.getSelectedType();
            int thirdW = (cw - 12) / 3;
            drawButton(g, font, cx, cy, thirdW, BTN_H, "主锚点", st == SplineEditorClientState.SelectionType.ANCHOR, mx, my);
            drawButton(g, font, cx + thirdW + 6, cy, thirdW, BTN_H, "前手柄", st == SplineEditorClientState.SelectionType.CONTROL_PREV, mx, my);
            drawButton(g, font, cx + (thirdW + 6) * 2, cy, thirdW, BTN_H, "后手柄", st == SplineEditorClientState.SelectionType.CONTROL_NEXT, mx, my);
            cy += BTN_H + 8;

            int fw2 = (cw - 12) / 3;
            drawMiniField(g, font, cx, cy, fw2, "X", fieldText(FIELD_COORD_X), activeField == FIELD_COORD_X, mx, my);
            drawMiniField(g, font, cx + fw2 + 6, cy, fw2, "Y", fieldText(FIELD_COORD_Y), activeField == FIELD_COORD_Y, mx, my);
            drawMiniField(g, font, cx + (fw2 + 6) * 2, cy, fw2, "Z", fieldText(FIELD_COORD_Z), activeField == FIELD_COORD_Z, mx, my);
            cy += 10 + FIELD_H + 4;

            drawToggle(g, font, cx, cy, cw, 18, "对称切线手柄锁定", model.point(sel).isLocked(), mx, my);
            cy += 22;
            drawButton(g, font, cx, cy, (cw - 8) / 2, BTN_H, "视角聚焦", false, mx, my);
            drawButton(g, font, cx + (cw - 8) / 2 + 8, cy, (cw - 8) / 2, BTN_H, "删除节点", true, mx, my);
        }
    }

    private static boolean clickTabCurve(double mx, double my, int contentY) {
        int cx = panelX + PAD;
        int cw = panelW - PAD * 2;
        int cy = contentY;

        cy += 20;
        int halfW = (cw - 8) / 2;
        if (inRect(mx, my, cx, cy, halfW, BTN_H)) {
            SplineEditorClientState.setEditMode(SplineEditorClientState.EditMode.ADD);
            return true;
        }
        if (inRect(mx, my, cx + halfW + 8, cy, halfW, BTN_H)) {
            SplineEditorClientState.setEditMode(SplineEditorClientState.EditMode.EDIT);
            return true;
        }
        cy += BTN_H + 10;

        cy += 20;
        if (inRect(mx, my, cx, cy, cw, 18)) {
            SplineModelHost model = new SplineModelHost();
            model.setClosed(!model.closed());
            return true;
        }
        cy += 22;
        int fw = (cw - 12) / 3;
        if (fieldClick(mx, my, cx, cy, fw, FIELD_SHIFT_X)) return true;
        if (fieldClick(mx, my, cx + fw + 6, cy, fw, FIELD_SHIFT_Y)) return true;
        if (fieldClick(mx, my, cx + (fw + 6) * 2, cy, fw, FIELD_SHIFT_Z)) return true;
        cy += 10 + FIELD_H;
        if (inRect(mx, my, cx, cy, cw, BTN_H)) {
            SplineModelHost model = new SplineModelHost();
            SplineVec3 delta = new SplineVec3(shiftX, shiftY, shiftZ);
            model.translateAll(delta);
            shiftX = shiftY = shiftZ = 0;
            return true;
        }
        cy += BTN_H + 10;

        cy += 20;
        SplineModelHost model = new SplineModelHost();
        if (inRect(mx, my, cx, cy, cw, POINT_LIST_ROWS * ROW_H)) {
            int row = ((int) my - cy) / ROW_H + pointListScroll;
            if (row >= 0 && row < model.points().size()) {
                SplineEditorClientState.setSelectedPoint(row, SplineEditorClientState.SelectionType.ANCHOR);
                SplineEditorClientState.setEditMode(SplineEditorClientState.EditMode.EDIT);
            }
            return true;
        }
        cy += POINT_LIST_ROWS * ROW_H + 6;

        int sel = SplineEditorClientState.getSelectedPointIndex();
        if (sel >= 0 && sel < model.points().size()) {
            cy += 20;
            int thirdW = (cw - 12) / 3;
            if (inRect(mx, my, cx, cy, thirdW, BTN_H)) {
                SplineEditorClientState.setSelectedPoint(sel, SplineEditorClientState.SelectionType.ANCHOR);
                return true;
            }
            if (inRect(mx, my, cx + thirdW + 6, cy, thirdW, BTN_H)) {
                SplineEditorClientState.setSelectedPoint(sel, SplineEditorClientState.SelectionType.CONTROL_PREV);
                return true;
            }
            if (inRect(mx, my, cx + (thirdW + 6) * 2, cy, thirdW, BTN_H)) {
                SplineEditorClientState.setSelectedPoint(sel, SplineEditorClientState.SelectionType.CONTROL_NEXT);
                return true;
            }
            cy += BTN_H + 8;
            int fw2 = (cw - 12) / 3;
            if (fieldClick(mx, my, cx, cy, fw2, FIELD_COORD_X)) return true;
            if (fieldClick(mx, my, cx + fw2 + 6, cy, fw2, FIELD_COORD_Y)) return true;
            if (fieldClick(mx, my, cx + (fw2 + 6) * 2, cy, fw2, FIELD_COORD_Z)) return true;
            cy += 10 + FIELD_H + 4;
            if (inRect(mx, my, cx, cy, cw, 18)) {
                SplinePoint pt = model.point(sel);
                pt.setLocked(!pt.isLocked());
                return true;
            }
            cy += 22;
            if (inRect(mx, my, cx, cy, (cw - 8) / 2, BTN_H)) {
                SplineVec3 pos = model.point(sel).getAnchor();
                SplineEditorClientState.setCamPosition(pos.x(), pos.y() + 3.0, pos.z() + 5.0);
                return true;
            }
            if (inRect(mx, my, cx + (cw - 8) / 2 + 8, cy, (cw - 8) / 2, BTN_H)) {
                model.removePoint(sel);
                SplineEditorClientState.setSelectedPoint(-1, SplineEditorClientState.SelectionType.NONE);
                return true;
            }
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Tab 1 — 阵列生成
    // ═══════════════════════════════════════════════════════════════

    private static void drawTabArray(GuiGraphics g, Font font, int contentY, double mx, double my) {
        int cx = panelX + PAD;
        int cw = panelW - PAD * 2;
        int cy = contentY;

        drawSection(g, font, cx, cy, "模板来源");
        cy += 20;
        boolean usePreset = SplineEditorClientState.getTemplateSourceMode() == SplineEditorClientState.TemplateSourceMode.VPANEL_PRESET;
        drawButton(g, font, cx, cy, (cw - 8) / 2, BTN_H, "V 面板方块预设", usePreset, mx, my);
        drawButton(g, font, cx + (cw - 8) / 2 + 8, cy, (cw - 8) / 2, BTN_H, "JSON 预设文件", !usePreset, mx, my);
        cy += BTN_H + 10;

        if (usePreset) {
            var preset = RoadPlacementState.getSelectedPreset();
            String presetName = preset != null ? preset.displayName() : "无";
            g.drawString(font, "当前 V 面板预设: ", cx, cy, MedievalColors.TEXT_MUTED);
            g.drawString(font, presetName, cx + font.width("当前 V 面板预设: "), cy, MedievalColors.TEXT_WARM_WHITE);
            cy += 16;

            drawSlider(g, font, cx, cy, cw, "道路宽度", 1, 15, SplineEditorClientState.getDynamicWidth(), "%.0f");
            cy += 22;
            drawSlider(g, font, cx, cy, cw, "基层厚度", 1, 3, SplineEditorClientState.getDynamicDepth(), "%.0f");
            cy += 22;
            drawToggle(g, font, cx, cy, cw, 18, "加装路肩石边", SplineEditorClientState.isDynamicHasBorder(), mx, my);
            cy += 24;
        } else {
            List<String> ids = SplineEditorClientState.getAvailableTemplateIds();
            int activeIdx = ids.indexOf(SplineEditorClientState.getActiveTemplateId());
            drawListRows(g, font, cx, cy, cw, POINT_LIST_ROWS, templateListScroll, ids, activeIdx, mx, my);
            cy += POINT_LIST_ROWS * ROW_H + 6;
            var tmpl = SplineEditorClientState.getActiveTemplate();
            if (tmpl != null) {
                g.drawString(font, "当前模板: " + tmpl.getId() + " (" + tmpl.getBlocks().size() + " 方块)",
                        cx, cy, MedievalColors.TEXT_MUTED);
                cy += 16;
            }
        }

        drawSection(g, font, cx, cy, "实时 3D 预览与调整");
        cy += 20;
        drawToggle(g, font, cx, cy, cw, 18, "开启阵列 3D 实时预览", SplineEditorClientState.isArrayPreview(), mx, my);
        cy += 22;

        if (SplineEditorClientState.isArrayPreview()) {
            drawField(g, font, cx, cy, cw, "采样步距 (格)", fieldText(FIELD_STEP), activeField == FIELD_STEP, mx, my);
            cy += 10 + FIELD_H + 6;
            drawSlider(g, font, cx, cy, cw, "滚转角 Roll", -180, 180, SplineEditorClientState.getArrayOffsetRoll(), "%.1f°");
            cy += 22;
            drawSlider(g, font, cx, cy, cw, "俯仰角 Pitch", -180, 180, SplineEditorClientState.getArrayOffsetPitch(), "%.1f°");
            cy += 22;
            drawSlider(g, font, cx, cy, cw, "偏航角 Yaw", -180, 180, SplineEditorClientState.getArrayOffsetYaw(), "%.1f°");
            cy += 22;
            drawButton(g, font, cx, cy, cw, BTN_H, "0° 重置", false, mx, my);
            cy += BTN_H + 10;
        }

        drawButton(g, font, cx, cy, cw, 30, "下发道路建造任务", true, mx, my);
    }

    private static boolean clickTabArray(double mx, double my, int contentY) {
        int cx = panelX + PAD;
        int cw = panelW - PAD * 2;
        int cy = contentY;

        cy += 20;
        int halfW = (cw - 8) / 2;
        if (inRect(mx, my, cx, cy, halfW, BTN_H)) {
            SplineEditorClientState.setTemplateSourceMode(SplineEditorClientState.TemplateSourceMode.VPANEL_PRESET);
            return true;
        }
        if (inRect(mx, my, cx + halfW + 8, cy, halfW, BTN_H)) {
            SplineEditorClientState.setTemplateSourceMode(SplineEditorClientState.TemplateSourceMode.JSON_FILE);
            return true;
        }
        cy += BTN_H + 10;

        boolean usePreset = SplineEditorClientState.getTemplateSourceMode() == SplineEditorClientState.TemplateSourceMode.VPANEL_PRESET;
        if (usePreset) {
            cy += 16;
            if (sliderClick(mx, my, cx, cy, cw, SLIDER_WIDTH)) return true;
            cy += 22;
            if (sliderClick(mx, my, cx, cy, cw, SLIDER_DEPTH)) return true;
            cy += 22;
            if (inRect(mx, my, cx, cy, cw, 18)) {
                SplineEditorClientState.setDynamicHasBorder(!SplineEditorClientState.isDynamicHasBorder());
                return true;
            }
            cy += 24;
        } else {
            if (inRect(mx, my, cx, cy, cw, POINT_LIST_ROWS * ROW_H)) {
                List<String> ids = SplineEditorClientState.getAvailableTemplateIds();
                int row = ((int) my - cy) / ROW_H + templateListScroll;
                if (row >= 0 && row < ids.size()) {
                    SplineEditorClientState.setActiveTemplateId(ids.get(row));
                }
                return true;
            }
            cy += POINT_LIST_ROWS * ROW_H + 6;
            cy += 16;
        }

        cy += 20;
        if (inRect(mx, my, cx, cy, cw, 18)) {
            SplineEditorClientState.setArrayPreview(!SplineEditorClientState.isArrayPreview());
            return true;
        }
        cy += 22;

        if (SplineEditorClientState.isArrayPreview()) {
            if (fieldClick(mx, my, cx, cy, cw, FIELD_STEP)) return true;
            cy += 10 + FIELD_H + 6;
            if (sliderClick(mx, my, cx, cy, cw, SLIDER_ROLL)) return true;
            cy += 22;
            if (sliderClick(mx, my, cx, cy, cw, SLIDER_PITCH)) return true;
            cy += 22;
            if (sliderClick(mx, my, cx, cy, cw, SLIDER_YAW)) return true;
            cy += 22;
            if (inRect(mx, my, cx, cy, cw, BTN_H)) {
                SplineEditorClientState.setArrayOffsetRoll(0);
                SplineEditorClientState.setArrayOffsetPitch(0);
                SplineEditorClientState.setArrayOffsetYaw(0);
                return true;
            }
            cy += BTN_H + 10;
        }

        if (inRect(mx, my, cx, cy, cw, 30)) {
            SplineEditorController.doBuildArray();
            return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Tab 2 — 模板与工具
    // ═══════════════════════════════════════════════════════════════

    private static void drawTabTemplates(GuiGraphics g, Font font, int contentY, double mx, double my) {
        int cx = panelX + PAD;
        int cw = panelW - PAD * 2;
        int cy = contentY;

        drawSection(g, font, cx, cy, "模板文件管理");
        cy += 20;
        drawField(g, font, cx, cy, cw, "模板名称", fieldText(FIELD_NAME), activeField == FIELD_NAME, mx, my);
        cy += 10 + FIELD_H + 6;
        int halfW = (cw - 8) / 2;
        drawButton(g, font, cx, cy, halfW, BTN_H, "保存 JSON 模板", false, mx, my);
        drawButton(g, font, cx + halfW + 8, cy, halfW, BTN_H, "读取 JSON 模板", false, mx, my);
        cy += BTN_H + 12;

        drawSection(g, font, cx, cy, "视图与快捷工具");
        cy += 20;
        boolean topDown = SplineEditorClientState.isTopDown();
        drawButton(g, font, cx, cy, cw, BTN_H, topDown ? "退出 2D 俯瞰视角 (G)" : "切换 2D 俯瞰视角 (G)", topDown, mx, my);
        cy += BTN_H + 8;
        drawButton(g, font, cx, cy, cw, BTN_H, "打开操作指南 (H)", false, mx, my);
        cy += BTN_H + 8;
        drawButton(g, font, cx, cy, (cw - 8) / 2, BTN_H, "清空画布", false, mx, my);
        drawButton(g, font, cx + (cw - 8) / 2 + 8, cy, (cw - 8) / 2, BTN_H, "关闭编辑器", true, mx, my);
    }

    private static boolean clickTabTemplates(double mx, double my, int contentY) {
        int cx = panelX + PAD;
        int cw = panelW - PAD * 2;
        int cy = contentY;

        cy += 20;
        if (fieldClick(mx, my, cx, cy, cw, FIELD_NAME)) return true;
        cy += 10 + FIELD_H + 6;
        int halfW = (cw - 8) / 2;
        if (inRect(mx, my, cx, cy, halfW, BTN_H)) {
            if (templateName.trim().isEmpty()) return true;
            SplineEditorClientState.saveTemplate(templateName.trim());
            return true;
        }
        if (inRect(mx, my, cx + halfW + 8, cy, halfW, BTN_H)) {
            Minecraft mc = Minecraft.getInstance();
            if (!templateName.trim().isEmpty() && mc.player != null) {
                SplineVec3 pos = new SplineVec3(mc.player.position().x, mc.player.position().y, mc.player.position().z);
                SplineEditorClientState.loadTemplate(templateName.trim(), pos);
            }
            return true;
        }
        cy += BTN_H + 12;

        cy += 20;
        if (inRect(mx, my, cx, cy, cw, BTN_H)) {
            if (SplineEditorClientState.isTopDown()) {
                SplineEditorClientState.exitTopDown();
            } else {
                SplineEditorClientState.enterTopDown();
            }
            return true;
        }
        cy += BTN_H + 8;
        if (inRect(mx, my, cx, cy, cw, BTN_H)) {
            Minecraft mc = Minecraft.getInstance();
            String content = com.wsteam.wandscape.shared.ui.markdown.navigation.DocumentLoader.loadMarkdown("road_spline_guide");
            mc.setScreen(new com.wsteam.wandscape.shared.ui.guide.GuideTestScreen(null, content, "road_spline_guide"));
            return true;
        }
        cy += BTN_H + 8;
        if (inRect(mx, my, cx, cy, (cw - 8) / 2, BTN_H)) {
            SplineEditorClientState.getModel().clear();
            SplineEditorClientState.setSelectedPoint(-1, SplineEditorClientState.SelectionType.NONE);
            return true;
        }
        if (inRect(mx, my, cx + (cw - 8) / 2 + 8, cy, (cw - 8) / 2, BTN_H)) {
            SplineEditorClientState.exitEditMode();
            return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Slider / field interaction
    // ═══════════════════════════════════════════════════════════════

    private static boolean sliderClick(double mx, double my, int x, int y, int w, int id) {
        int trackY = y + 12;
        int trackH = 6;
        if (!inRect(mx, my, x, trackY, w, trackH)) return false;
        dragSlider = id;
        dragSliderX = x;
        dragSliderW = w;
        applySliderDrag(id, mx);
        return true;
    }

    private static void applySliderDrag(int id, double mouseX) {
        double ratio = Math.clamp((mouseX - dragSliderX) / dragSliderW, 0.0, 1.0);
        switch (id) {
            case SLIDER_WIDTH -> SplineEditorClientState.setDynamicWidth((int) Math.round(1 + ratio * 14));
            case SLIDER_DEPTH -> SplineEditorClientState.setDynamicDepth((int) Math.round(1 + ratio * 2));
            case SLIDER_ROLL -> SplineEditorClientState.setArrayOffsetRoll(-180 + ratio * 360);
            case SLIDER_PITCH -> SplineEditorClientState.setArrayOffsetPitch(-180 + ratio * 360);
            case SLIDER_YAW -> SplineEditorClientState.setArrayOffsetYaw(-180 + ratio * 360);
            default -> {}
        }
    }

    private static boolean fieldClick(double mx, double my, int x, int y, int w, int id) {
        int fy = y + 10;
        if (!inRect(mx, my, x, fy, w, FIELD_H)) return false;
        if (activeField != -1) commitField();
        activeField = id;
        fieldBuffer = fieldCurrentText(id);
        return true;
    }

    private static String fieldText(int id) {
        if (activeField == id) return fieldBuffer;
        return fieldCurrentText(id);
    }

    private static String fieldCurrentText(int id) {
        return switch (id) {
            case FIELD_SHIFT_X -> fmt1(shiftX);
            case FIELD_SHIFT_Y -> fmt1(shiftY);
            case FIELD_SHIFT_Z -> fmt1(shiftZ);
            case FIELD_COORD_X, FIELD_COORD_Y, FIELD_COORD_Z -> fmt2(currentSelectedCoord(id));
            case FIELD_STEP -> fmt2(SplineEditorClientState.getArrayStepDistance());
            case FIELD_NAME -> templateName;
            default -> "";
        };
    }

    private static double currentSelectedCoord(int id) {
        SplinePoint pt = selectedPoint();
        if (pt == null) return 0;
        SplineVec3 v = switch (SplineEditorClientState.getSelectedType()) {
            case ANCHOR -> pt.getAnchor();
            case CONTROL_PREV -> pt.getControlPrev();
            default -> pt.getControlNext();
        };
        return switch (id) {
            case FIELD_COORD_X -> v.x();
            case FIELD_COORD_Y -> v.y();
            default -> v.z();
        };
    }

    private static void commitField() {
        if (activeField == -1) return;
        int id = activeField;
        activeField = -1;
        String b = fieldBuffer.trim();

        switch (id) {
            case FIELD_SHIFT_X -> shiftX = parseDouble(b, shiftX);
            case FIELD_SHIFT_Y -> shiftY = parseDouble(b, shiftY);
            case FIELD_SHIFT_Z -> shiftZ = parseDouble(b, shiftZ);
            case FIELD_COORD_X -> setSelectedCoord(0, parseDouble(b, currentSelectedCoord(id)));
            case FIELD_COORD_Y -> setSelectedCoord(1, parseDouble(b, currentSelectedCoord(id)));
            case FIELD_COORD_Z -> setSelectedCoord(2, parseDouble(b, currentSelectedCoord(id)));
            case FIELD_STEP -> SplineEditorClientState.setArrayStepDistance(Math.max(0.1, parseDouble(b, SplineEditorClientState.getArrayStepDistance())));
            case FIELD_NAME -> templateName = b;
            default -> {}
        }
    }

    private static void setSelectedCoord(int axis, double value) {
        SplinePoint pt = selectedPoint();
        if (pt == null) return;
        SplineVec3 cur = switch (SplineEditorClientState.getSelectedType()) {
            case ANCHOR -> pt.getAnchor();
            case CONTROL_PREV -> pt.getControlPrev();
            default -> pt.getControlNext();
        };
        SplineVec3 next = switch (axis) {
            case 0 -> new SplineVec3(value, cur.y(), cur.z());
            case 1 -> new SplineVec3(cur.x(), value, cur.z());
            default -> new SplineVec3(cur.x(), cur.y(), value);
        };
        switch (SplineEditorClientState.getSelectedType()) {
            case ANCHOR -> pt.setAnchor(next);
            case CONTROL_PREV -> pt.setControlPrev(next);
            default -> pt.setControlNext(next);
        }
    }

    private static SplinePoint selectedPoint() {
        int idx = SplineEditorClientState.getSelectedPointIndex();
        List<SplinePoint> pts = SplineEditorClientState.getModel().getPoints();
        return (idx >= 0 && idx < pts.size()) ? pts.get(idx) : null;
    }

    private static double parseDouble(String s, double fallback) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String fmt1(double v) {
        return String.format("%.1f", v);
    }

    private static String fmt2(double v) {
        return String.format("%.2f", v);
    }

    private static char keyToChar(int key, int modifiers) {
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) {
            return shift ? (char) key : (char) (key + 32);
        }
        if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) {
            if (shift) {
                return switch (key) {
                    case GLFW.GLFW_KEY_0 -> ')';
                    case GLFW.GLFW_KEY_1 -> '!';
                    case GLFW.GLFW_KEY_2 -> '@';
                    case GLFW.GLFW_KEY_3 -> '#';
                    case GLFW.GLFW_KEY_4 -> '$';
                    case GLFW.GLFW_KEY_5 -> '%';
                    case GLFW.GLFW_KEY_6 -> '^';
                    case GLFW.GLFW_KEY_7 -> '&';
                    case GLFW.GLFW_KEY_8 -> '*';
                    default -> '(';
                };
            }
            return (char) key;
        }
        return switch (key) {
            case GLFW.GLFW_KEY_SPACE -> ' ';
            case GLFW.GLFW_KEY_MINUS -> shift ? '_' : '-';
            case GLFW.GLFW_KEY_PERIOD -> shift ? '>' : '.';
            default -> 0;
        };
    }

    private static void clearTransientState() {
        activeField = -1;
        fieldBuffer = "";
        dragSlider = -1;
    }

    /** Small value-object wrapper so draw/click layout stays in sync with the model. */
    private record SplineModelHost() {
        List<SplinePoint> points() {
            return SplineEditorClientState.getModel().getPoints();
        }
        boolean closed() {
            return SplineEditorClientState.getModel().isClosed();
        }
        void setClosed(boolean c) {
            SplineEditorClientState.getModel().setClosed(c);
        }
        SplinePoint point(int i) {
            return points().get(i);
        }
        void removePoint(int i) {
            SplineEditorClientState.getModel().removePoint(i);
        }
        void translateAll(SplineVec3 d) {
            SplineEditorClientState.getModel().translateAll(d);
        }
    }
}
