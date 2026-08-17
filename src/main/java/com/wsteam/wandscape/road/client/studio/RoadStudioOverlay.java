package com.wsteam.wandscape.road.client.studio;

import java.util.List;

import com.wsteam.wandscape.road.client.RoadPlacementState;
import com.wsteam.wandscape.road.client.SplineEditorClientState;
import com.wsteam.wandscape.road.client.SplineEditorController;
import com.wsteam.wandscape.road.core.SplineModel;
import com.wsteam.wandscape.road.core.SplinePoint;
import com.wsteam.wandscape.road.core.SplineVec3;
import com.wsteam.wandscape.road.data.RoadPreset;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.ui.I18n;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Self-drawn overlay panel for the Road Studio — MC-native UI (no ImGui dependency).
 * Renders a right-side panel using MC-native {@link GuiGraphics} with the
 * immediate-mode widget system in {@link StudioWidgets}.
 */
public final class RoadStudioOverlay {
    private static final String TAG = "RoadStudioOverlay";

    private static volatile boolean visible = false;
    private static boolean registered = false;

    // Panel geometry (GUI-scaled coordinates)
    private static float panelWidthRatio = 0.32f;
    private static final float MIN_PANEL_RATIO = 0.22f;
    private static final float MAX_PANEL_RATIO = 0.55f;
    private static final int PAD = 10;

    // Scroll
    private static int scrollOffset = 0;
    private static int lastContentHeight = 0;
    private static double pendingScroll = 0;

    // Input state
    private static int frameMouseX, frameMouseY;
    private static boolean wasMouseDown = false;

    // Splitter drag
    private static boolean splitterDragging = false;
    private static int splitterDragStartX = 0;
    private static float splitterDragStartRatio = 0;

    // Spline tab index (for SPLINE mode)
    private static int splineTabIndex = 0;

    // Global shift accumulators
    private static double globalShiftX = 0, globalShiftY = 0, globalShiftZ = 0;

    private RoadStudioOverlay() {}

    // ════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ════════════════════════════════════════════════════════════════

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(RenderGuiEvent.Post.class, RoadStudioOverlay::onRenderGui);
        NeoForge.EVENT_BUS.addListener(InputEvent.MouseScrollingEvent.class, RoadStudioOverlay::onMouseScroll);
        Log.info(TAG, "RoadStudioOverlay registered");
    }

    public static boolean isVisible() {
        return visible;
    }

    public static void setVisible(boolean v) {
        if (v) open();
        else close();
    }

    /** Open the native Road Studio overlay and release mouse cursor. */
    public static void open() {
        visible = true;
        scrollOffset = 0;
        splineTabIndex = 0;
        SplineEditorController.resetInputState();
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.mouseHandler != null) {
            mc.mouseHandler.releaseMouse();
        }
        Log.info(TAG, "[RoadStudio] Native overlay opened");
    }

    /** Close the native Road Studio overlay and grab mouse back. */
    public static void close() {
        if (!visible) return;
        visible = false;
        SplineEditorClientState.exitEditMode();
        RoadPlacementState.exitProjection();
        SplineEditorController.resetInputState();
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.mouseHandler != null) {
            mc.mouseHandler.grabMouse();
        }
        Log.info(TAG, "[RoadStudio] Native overlay closed");
    }

    /** True if the mouse cursor is currently over the overlay panel. */
    public static boolean isMouseOverPanel() {
        if (!visible) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return false;
        updateMousePos();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int panelW = (int) (screenW * panelWidthRatio);
        panelW = Math.max(180, Math.min(screenW - 40, panelW));
        int panelX = screenW - panelW;
        return frameMouseX >= panelX && frameMouseX <= screenW;
    }

    public static boolean wantsKeyboard() {
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    //  INPUT EVENTS
    // ════════════════════════════════════════════════════════════════

    private static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!visible) return;
        if (Minecraft.getInstance().screen != null) return;
        updateMousePos();

        if (isMouseOverPanel()) {
            pendingScroll += event.getScrollDeltaY();
            event.setCanceled(true);
        }
    }

    private static void updateMousePos() {
        Minecraft mc = Minecraft.getInstance();
        double[] mx = new double[1], my = new double[1];
        GLFW.glfwGetCursorPos(mc.getWindow().getWindow(), mx, my);
        double guiScale = mc.getWindow().getGuiScale();
        frameMouseX = (int) (mx[0] / guiScale);
        frameMouseY = (int) (my[0] / guiScale);
    }

    // ════════════════════════════════════════════════════════════════
    //  RENDER
    // ════════════════════════════════════════════════════════════════

    private static void onRenderGui(RenderGuiEvent.Post event) {
        if (!visible) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.screen != null) return;

        updateMousePos();
        long window = mc.getWindow().getWindow();
        boolean leftDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean clicked = leftDown && !wasMouseDown;
        boolean released = !leftDown && wasMouseDown;
        wasMouseDown = leftDown;

        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        int screenW = g.guiWidth();
        int screenH = g.guiHeight();

        int panelW = (int) (screenW * panelWidthRatio);
        panelW = Math.max(180, Math.min(screenW - 40, panelW));
        int panelX = screenW - panelW;
        int panelH = screenH;

        // Handle scroll
        if (pendingScroll != 0 && isMouseOverPanel()) {
            scrollOffset -= (int) (pendingScroll * 24);
            int maxScroll = Math.max(0, lastContentHeight - (panelH - 60));
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));
        }
        pendingScroll = 0;

        // Handle splitter drag
        handleSplitter(screenW, panelX, leftDown);

        // Begin widget frame
        StudioWidgets.beginFrame(g, font, frameMouseX, frameMouseY,
                leftDown, clicked, released, panelX, screenW);

        // ── Panel background ──
        g.fill(panelX, 0, screenW, screenH, StudioColors.PANEL_BG);

        // Left gold border / splitter line
        boolean splitterHov = Math.abs(frameMouseX - panelX) < 5;
        int lineColor = (splitterHov || splitterDragging)
                ? StudioColors.SPLITTER_ACTIVE : StudioColors.BORDER_GOLD_BRIGHT;
        g.fill(panelX, 0, panelX + 1, screenH, lineColor);

        // ── Content area (with scissor clip) ──
        int contentX = panelX + PAD;
        int contentW = panelW - PAD * 2 - 8; // 8 for scrollbar
        int contentY = PAD - scrollOffset;

        g.enableScissor(panelX + 1, 0, screenW, screenH - 18);

        StudioWidgets.beginLayout(contentX, contentY, contentW);

        SplineModel model = SplineEditorClientState.getModel();

        drawHeaderBanner(g, font, contentX, contentW);
        drawToolModeSelector();

        RoadPlacementState.ToolMode currentTool = RoadPlacementState.getActiveTool();
        switch (currentTool) {
            case REPLACE -> drawReplaceModeTab(mc);
            case FILL -> drawFillModeTab(mc);
            case DESTROY_FILL -> drawDestroyFillModeTab(mc);
            case SPLINE -> drawSplineModeTab(model, mc);
        }

        StudioWidgets.spacingLarge();
        lastContentHeight = StudioWidgets.endLayout();

        g.disableScissor();

        // Footer at fixed bottom position (outside scroll area)
        drawBottomFooter(g, font, panelX, panelW, screenH);

        // Scrollbar
        if (lastContentHeight > panelH - 40) {
            StudioWidgets.verticalScrollbar(
                    screenW - 8, 0, screenH - 18, lastContentHeight + 40, scrollOffset);
        }

        // Combo dropdown (rendered on top of everything)
        int comboResult = StudioWidgets.renderComboDropdown();
        if (comboResult >= 0) {
            RoadPlacementState.setSelectedPresetIndex(comboResult);
        }
    }

    private static void handleSplitter(int screenW, int panelX, boolean leftDown) {
        if (!splitterDragging && leftDown && Math.abs(frameMouseX - panelX) < 5) {
            splitterDragging = true;
            splitterDragStartX = frameMouseX;
            splitterDragStartRatio = panelWidthRatio;
        }

        if (splitterDragging) {
            if (leftDown) {
                int dx = frameMouseX - splitterDragStartX;
                float newRatio = splitterDragStartRatio - (float) dx / screenW;
                panelWidthRatio = Math.max(MIN_PANEL_RATIO, Math.min(MAX_PANEL_RATIO, newRatio));
            } else {
                splitterDragging = false;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  HEADER BANNER
    // ════════════════════════════════════════════════════════════════

    private static void drawHeaderBanner(GuiGraphics g, Font font, int x, int w) {
        int bannerH = 34;
        int by = StudioWidgets.getY();
        StudioWidgets.gradientBox(x - 2, by, w + 4, bannerH,
                StudioColors.HEADER_BG_TOP, StudioColors.HEADER_BG_BOTTOM);

        g.drawString(font, I18n.name("gui.wandscape.roadstudio.banner",
                "WANDSCAPE 道路制作工坊").getString(),
                x + 6, by + 4, StudioColors.TEXT_GOLD);

        String toolName = switch (RoadPlacementState.getActiveTool()) {
            case REPLACE -> I18n.name("gui.wandscape.roadstudio.tool_replace", "直线替换").getString();
            case FILL -> I18n.name("gui.wandscape.roadstudio.tool_fill", "立方体填充").getString();
            case DESTROY_FILL -> I18n.name("gui.wandscape.roadstudio.tool_destroy", "铲平垫平").getString();
            case SPLINE -> I18n.name("gui.wandscape.roadstudio.tool_spline", "样条曲线").getString();
        };
        String viewStr = SplineEditorClientState.isTopDown()
                ? I18n.name("gui.wandscape.roadstudio.view_topdown", "2D 俯瞰").getString()
                : I18n.name("gui.wandscape.roadstudio.view_free", "3D 自由").getString();
        g.drawString(font, I18n.name("gui.wandscape.roadstudio.mode_format",
                "模式: %s  |  视角: %s", toolName, viewStr).getString(),
                x + 6, by + 18, StudioColors.TEXT_CYAN);

        StudioWidgets.setY(by + bannerH + 6);
    }

    // ════════════════════════════════════════════════════════════════
    //  TOOL MODE SELECTOR (4 buttons)
    // ════════════════════════════════════════════════════════════════

    private static void drawToolModeSelector() {
        StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.mode_title",
                "模式选择").getString());

        RoadPlacementState.ToolMode current = RoadPlacementState.getActiveTool();
        int y = StudioWidgets.getY();
        int x = StudioWidgets.getLayoutX();
        int totalW = StudioWidgets.getLayoutW();
        int gap = 3;
        int btnW = (totalW - gap * 3) / 4;
        int btnH = 22;

        String[] labels = {
                I18n.name("gui.wandscape.roadstudio.mode_replace", "替换").getString(),
                I18n.name("gui.wandscape.roadstudio.mode_fill", "填充").getString(),
                I18n.name("gui.wandscape.roadstudio.mode_destroy", "铲平").getString(),
                I18n.name("gui.wandscape.roadstudio.mode_spline", "样条").getString(),
        };
        RoadPlacementState.ToolMode[] modes = RoadPlacementState.ToolMode.values();

        for (int i = 0; i < 4; i++) {
            int bx = x + i * (btnW + gap);
            boolean sel = (modes[i] == current);
            if (StudioWidgets.modeButton(labels[i], sel, bx, y, btnW, btnH)) {
                RoadPlacementState.setActiveTool(modes[i]);
            }
        }
        StudioWidgets.setY(y + btnH + 6);
    }

    // ════════════════════════════════════════════════════════════════
    //  PRESET COMBO
    // ════════════════════════════════════════════════════════════════

    private static void drawPresetCombo() {
        List<RoadPreset> presets = RoadPlacementState.getPresets();
        int currentIdx = RoadPlacementState.getSelectedPresetIndex();
        String[] names = presets.stream()
                .map(p -> I18n.name("gui.wandscape.road.preset." + p.id(), p.displayName()).getString())
                .toArray(String[]::new);

        int newIdx = StudioWidgets.combo("##preset", names, currentIdx, 22);
        if (newIdx != currentIdx) {
            RoadPlacementState.setSelectedPresetIndex(newIdx);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  MODE: REPLACE
    // ════════════════════════════════════════════════════════════════

    private static void drawReplaceModeTab(Minecraft mc) {
        StudioWidgets.spacing();
        StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.replace_preset_header",
                "铺设方块预设").getString());
        drawPresetCombo();

        StudioWidgets.spacing();
        StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.replace_points_header",
                "铺设路线起终点").getString());

        drawPositionControls(mc, I18n.name("gui.wandscape.roadstudio.start_label",
                "起点坐标 (Start)").getString(), true);
        StudioWidgets.spacing();
        drawPositionControls(mc, I18n.name("gui.wandscape.roadstudio.end_label",
                "终点坐标 (End)").getString(), false);

        // Evaluation
        StudioWidgets.spacing();
        BlockPos start = RoadPlacementState.getStartPos();
        BlockPos end = RoadPlacementState.getEndPos();
        if (start != null && end != null) {
            int dx = Math.abs(end.getX() - start.getX()) + 1;
            int dz = Math.abs(end.getZ() - start.getZ()) + 1;
            double dist = Math.sqrt((double) (end.getX() - start.getX()) * (end.getX() - start.getX())
                    + (double) (end.getZ() - start.getZ()) * (end.getZ() - start.getZ()));
            StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.replace_eval_header",
                    "铺设数据评估").getString());
            StudioWidgets.text(I18n.name("gui.wandscape.roadstudio.replace_span",
                    "覆盖跨度: %d × %d 方块范围", dx, dz).getString());
            StudioWidgets.text(I18n.name("gui.wandscape.roadstudio.replace_dist",
                    "直线距离: %.1f 方块", dist).getString());
        } else {
            StudioWidgets.textMuted(I18n.name("gui.wandscape.roadstudio.replace_hint",
                    "提示: 在世界中左键拖拽或点击下方按钮设置起终点").getString());
        }

        StudioWidgets.spacingLarge();
        if (StudioWidgets.buttonFull(
                I18n.name("gui.wandscape.roadstudio.replace_submit",
                        "下发直线铺设任务").getString(),
                26, StudioColors.BUTTON_GREEN, StudioColors.BUTTON_GREEN_HOVER)) {
            submitRoadReplace(mc);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  MODE: FILL
    // ════════════════════════════════════════════════════════════════

    private static void drawFillModeTab(Minecraft mc) {
        StudioWidgets.spacing();
        StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.fill_preset_header",
                "填充方块预设").getString());
        drawPresetCombo();

        StudioWidgets.spacing();
        StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.fill_corners_header",
                "3D 立方体对角点").getString());
        drawPositionControls(mc, I18n.name("gui.wandscape.roadstudio.fill_corner1",
                "角点 1 (Start)").getString(), true);
        StudioWidgets.spacing();
        drawPositionControls(mc, I18n.name("gui.wandscape.roadstudio.fill_corner2",
                "角点 2 (End)").getString(), false);

        StudioWidgets.spacing();
        BlockPos start = RoadPlacementState.getStartPos();
        BlockPos end = RoadPlacementState.getEndPos();
        if (start != null && end != null) {
            int dx = Math.abs(end.getX() - start.getX()) + 1;
            int dy = Math.abs(end.getY() - start.getY()) + 1;
            int dz = Math.abs(end.getZ() - start.getZ()) + 1;
            long volume = (long) dx * dy * dz;
            StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.fill_eval_header",
                    "立方体体积评估").getString());
            StudioWidgets.text(I18n.name("gui.wandscape.roadstudio.fill_size",
                    "尺寸: %d (宽) × %d (高) × %d (深)", dx, dy, dz).getString());
            StudioWidgets.text(I18n.name("gui.wandscape.roadstudio.fill_volume",
                    "总体积: %d 个方块", volume).getString());
        } else {
            StudioWidgets.textMuted(I18n.name("gui.wandscape.roadstudio.fill_hint",
                    "提示: 请设置两个对角点以确定填充区域").getString());
        }

        StudioWidgets.spacingLarge();
        if (StudioWidgets.buttonFull(
                I18n.name("gui.wandscape.roadstudio.fill_submit",
                        "下发立方体填充任务").getString(),
                26, StudioColors.BUTTON_GREEN, StudioColors.BUTTON_GREEN_HOVER)) {
            submitFillBox(mc);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  MODE: DESTROY/FILL
    // ════════════════════════════════════════════════════════════════

    private static void drawDestroyFillModeTab(Minecraft mc) {
        StudioWidgets.spacing();
        StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.destroy_ref_header",
                "参照基准方块").getString());

        String refBlock = RoadPlacementState.getRefBlockId();
        if (refBlock.isEmpty()) {
            StudioWidgets.textDisabled(I18n.name("gui.wandscape.roadstudio.destroy_no_ref",
                    "未捕获参照方块 (右键点击方块捕获)").getString());
        } else {
            StudioWidgets.textColored(
                    I18n.name("gui.wandscape.roadstudio.destroy_ref_fmt",
                            "参照方块: %s", refBlock).getString(),
                    StudioColors.TEXT_GREEN);
        }

        if (StudioWidgets.buttonFull(
                I18n.name("gui.wandscape.roadstudio.destroy_capture_feet",
                        "捕捉脚下方块为参照").getString(),
                20, StudioColors.BUTTON_NORMAL, StudioColors.BUTTON_HOVER)) {
            BlockPos feet = getCapturedFeetPosition(mc);
            RoadPlacementState.setStartPos(feet);
            if (mc.level != null) {
                var st = mc.level.getBlockState(feet);
                String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                RoadPlacementState.setRefBlockId(id);
            }
        }

        StudioWidgets.spacing();
        StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.destroy_area_header",
                "平整区域边界").getString());
        drawPositionControls(mc, I18n.name("gui.wandscape.roadstudio.destroy_start",
                "边界起点 (Start)").getString(), true);
        StudioWidgets.spacing();
        drawPositionControls(mc, I18n.name("gui.wandscape.roadstudio.destroy_end",
                "边界终点 (End)").getString(), false);

        StudioWidgets.spacing();
        BlockPos start = RoadPlacementState.getStartPos();
        BlockPos end = RoadPlacementState.getEndPos();
        if (start != null && end != null) {
            int dx = Math.abs(end.getX() - start.getX()) + 1;
            int dz = Math.abs(end.getZ() - start.getZ()) + 1;
            StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.destroy_eval_header",
                    "平整面积评估").getString());
            StudioWidgets.text(I18n.name("gui.wandscape.roadstudio.destroy_size",
                    "底面尺寸: %d × %d 方块", dx, dz).getString());
            StudioWidgets.text(I18n.name("gui.wandscape.roadstudio.destroy_area",
                    "平整面积: %d 平方方块", dx * dz).getString());
        } else {
            StudioWidgets.textMuted(I18n.name("gui.wandscape.roadstudio.destroy_hint",
                    "提示: 请选择起点与终点以确定平整区域").getString());
        }

        StudioWidgets.spacingLarge();
        if (StudioWidgets.buttonFull(
                I18n.name("gui.wandscape.roadstudio.destroy_submit",
                        "下发地形平整任务").getString(),
                26, StudioColors.BUTTON_GREEN, StudioColors.BUTTON_GREEN_HOVER)) {
            submitDestroyFill(mc);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  MODE: SPLINE (3 sub-tabs)
    // ════════════════════════════════════════════════════════════════

    private static void drawSplineModeTab(SplineModel model, Minecraft mc) {
        String[] tabs = {
                I18n.name("gui.wandscape.roadstudio.tab_curve", "曲线编辑").getString(),
                I18n.name("gui.wandscape.roadstudio.tab_array", "阵列生成").getString(),
                I18n.name("gui.wandscape.roadstudio.tab_templates", "模板工具").getString(),
        };

        int clickedTab = StudioWidgets.tabBar(tabs, splineTabIndex);
        if (clickedTab >= 0) {
            splineTabIndex = clickedTab;
        }

        switch (splineTabIndex) {
            case 0 -> drawCurveTab(model, mc);
            case 1 -> drawArrayTab(mc);
            case 2 -> drawTemplatesTab(model, mc);
        }
    }

    // ── Sub-tab 1: Curve Editing ──

    private static void drawCurveTab(SplineModel model, Minecraft mc) {
        StudioWidgets.spacing();
        StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.curve_edit_mode",
                "编辑模式切换").getString());

        boolean isAdd = SplineEditorClientState.getEditMode() == SplineEditorClientState.EditMode.ADD;
        int y = StudioWidgets.getY();
        int x = StudioWidgets.getLayoutX();
        int halfW = (StudioWidgets.getLayoutW() - 4) / 2;

        if (StudioWidgets.modeButton(I18n.name("gui.wandscape.roadstudio.curve_add_point",
                "点击添加点").getString(), isAdd, x, y, halfW, 24)) {
            SplineEditorClientState.setEditMode(SplineEditorClientState.EditMode.ADD);
        }
        if (StudioWidgets.modeButton(I18n.name("gui.wandscape.roadstudio.curve_select_drag",
                "选择与拖拽").getString(), !isAdd, x + halfW + 4, y, halfW, 24)) {
            SplineEditorClientState.setEditMode(SplineEditorClientState.EditMode.EDIT);
        }
        StudioWidgets.setY(y + 24 + 6);

        // Curve geometry
        StudioWidgets.spacing();
        StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.curve_geom_header",
                "曲线几何与平移").getString());

        boolean closed = model.isClosed();
        if (StudioWidgets.checkbox(I18n.name("gui.wandscape.roadstudio.curve_closed",
                "闭合环形道路 (连接首尾)").getString(), closed)) {
            model.setClosed(!closed);
        }

        StudioWidgets.spacing();
        StudioWidgets.textMuted(I18n.name("gui.wandscape.roadstudio.curve_shift_label",
                "整体平移偏移量:").getString());

        // Global shift sliders
        globalShiftX = StudioWidgets.sliderFloat("##gsX", "X 偏移", (float) globalShiftX, -32f, 32f, "%.1f");
        globalShiftY = StudioWidgets.sliderFloat("##gsY", "Y 偏移", (float) globalShiftY, -32f, 32f, "%.1f");
        globalShiftZ = StudioWidgets.sliderFloat("##gsZ", "Z 偏移", (float) globalShiftZ, -32f, 32f, "%.1f");

        if (StudioWidgets.buttonFull(
                I18n.name("gui.wandscape.roadstudio.curve_shift_btn", "执行整体平移").getString(),
                22, StudioColors.BUTTON_NORMAL, StudioColors.BUTTON_HOVER)) {
            SplineVec3 delta = new SplineVec3(globalShiftX, globalShiftY, globalShiftZ);
            model.translateAll(delta);
            globalShiftX = 0;
            globalShiftY = 0;
            globalShiftZ = 0;
            Log.info(TAG, "Translated all points by {}", delta);
        }

        // Control points list
        StudioWidgets.spacing();
        StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.curve_list_header",
                "控制点列表 (%d)", model.getPoints().size()).getString());

        if (model.getPoints().isEmpty()) {
            StudioWidgets.textDisabled(I18n.name("gui.wandscape.roadstudio.curve_list_empty1",
                    "  当前无控制点。").getString());
            StudioWidgets.textDisabled(I18n.name("gui.wandscape.roadstudio.curve_list_empty2",
                    "  请在世界中左键点击方块表面添加。").getString());
        } else {
            String[] pointLabels = new String[model.getPoints().size()];
            for (int i = 0; i < model.getPoints().size(); i++) {
                SplinePoint pt = model.getPoints().get(i);
                SplineVec3 anchor = pt.getAnchor();
                String symTag = pt.isLocked() ? "[对称]" : "[自由]";
                pointLabels[i] = String.format("#%d (%.1f, %.1f, %.1f) %s",
                        i, anchor.x(), anchor.y(), anchor.z(), symTag);
            }

            int selected = SplineEditorClientState.getSelectedPointIndex();
            int clicked = StudioWidgets.selectableList(pointLabels, selected, 95);
            if (clicked >= 0) {
                SplineEditorClientState.setSelectedPoint(clicked, SplineEditorClientState.SelectionType.ANCHOR);
                SplineEditorClientState.setEditMode(SplineEditorClientState.EditMode.EDIT);
            }
        }

        // Inspector for selected point
        drawPointInspector(model);
    }

    private static void drawPointInspector(SplineModel model) {
        int selectedIdx = SplineEditorClientState.getSelectedPointIndex();
        SplineEditorClientState.SelectionType selectedType = SplineEditorClientState.getSelectedType();
        if (selectedIdx < 0 || selectedIdx >= model.getPoints().size()) return;

        SplinePoint pt = model.getPoints().get(selectedIdx);

        StudioWidgets.spacing();
        StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.curve_inspector_header",
                "节点属性检查器 #%d", selectedIdx).getString());

        int y = StudioWidgets.getY();
        int x = StudioWidgets.getLayoutX();
        int segW = StudioWidgets.getLayoutW() / 3;

        if (StudioWidgets.radioButtonAt(I18n.name("gui.wandscape.roadstudio.curve_anchor",
                "主锚点").getString(),
                selectedType == SplineEditorClientState.SelectionType.ANCHOR, x, y)) {
            SplineEditorClientState.setSelectedPoint(selectedIdx, SplineEditorClientState.SelectionType.ANCHOR);
        }
        if (StudioWidgets.radioButtonAt(I18n.name("gui.wandscape.roadstudio.curve_handle_prev",
                "前手柄").getString(),
                selectedType == SplineEditorClientState.SelectionType.CONTROL_PREV, x + segW, y)) {
            SplineEditorClientState.setSelectedPoint(selectedIdx, SplineEditorClientState.SelectionType.CONTROL_PREV);
        }
        if (StudioWidgets.radioButtonAt(I18n.name("gui.wandscape.roadstudio.curve_handle_next",
                "后手柄").getString(),
                selectedType == SplineEditorClientState.SelectionType.CONTROL_NEXT, x + segW * 2, y)) {
            SplineEditorClientState.setSelectedPoint(selectedIdx, SplineEditorClientState.SelectionType.CONTROL_NEXT);
        }
        StudioWidgets.setY(y + 18);

        SplineVec3 targetPos = switch (selectedType) {
            case ANCHOR -> pt.getAnchor();
            case CONTROL_PREV -> pt.getControlPrev();
            case CONTROL_NEXT -> pt.getControlNext();
            default -> null;
        };
        if (targetPos != null) {
            StudioWidgets.text(String.format("  坐标: (%.2f, %.2f, %.2f)",
                    targetPos.x(), targetPos.y(), targetPos.z()));
        }

        // Symmetry lock
        boolean locked = pt.isLocked();
        if (StudioWidgets.checkbox(I18n.name("gui.wandscape.roadstudio.curve_sym_lock",
                "对称切线手柄锁定").getString(), locked)) {
            pt.setLocked(!locked);
        }

        // Focus & Delete buttons
        int yRow = StudioWidgets.getY();
        int halfW = (StudioWidgets.getLayoutW() - 4) / 2;
        if (StudioWidgets.buttonAt(I18n.name("gui.wandscape.roadstudio.curve_focus",
                "视角聚焦").getString(),
                x, yRow, halfW, 22,
                StudioColors.BUTTON_NORMAL, StudioColors.BUTTON_HOVER, StudioColors.BUTTON_ACTIVE)) {
            SplineVec3 pos = pt.getAnchor();
            SplineEditorClientState.setCamPosition(pos.x(), pos.y() + 3.0, pos.z() + 5.0);
        }

        if (StudioWidgets.buttonAt(I18n.name("gui.wandscape.roadstudio.curve_delete_btn",
                "删除节点 #%d", selectedIdx).getString(),
                x + halfW + 4, yRow, halfW, 22,
                StudioColors.BUTTON_RED, StudioColors.BUTTON_RED_HOVER, StudioColors.BUTTON_RED_HOVER)) {
            model.removePoint(selectedIdx);
            int after = model.getPoints().size();
            if (after > 0) {
                SplineEditorClientState.setSelectedPoint(Math.min(selectedIdx, after - 1),
                        SplineEditorClientState.SelectionType.ANCHOR);
            } else {
                SplineEditorClientState.setSelectedPoint(-1, SplineEditorClientState.SelectionType.NONE);
            }
        }
        StudioWidgets.setY(yRow + 22 + 6);
    }

    // ── Sub-tab 2: Array Generation ──

    private static void drawArrayTab(Minecraft mc) {
        StudioWidgets.spacing();
        StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.array_source_header",
                "模板来源与规格").getString());

        var sourceMode = SplineEditorClientState.getTemplateSourceMode();
        int y = StudioWidgets.getY();
        int x = StudioWidgets.getLayoutX();
        int halfW = StudioWidgets.getLayoutW() / 2;

        if (StudioWidgets.radioButtonAt(I18n.name("gui.wandscape.roadstudio.array_source_vpanel",
                "方块预设生成").getString(),
                sourceMode == SplineEditorClientState.TemplateSourceMode.VPANEL_PRESET, x, y)) {
            SplineEditorClientState.setTemplateSourceMode(SplineEditorClientState.TemplateSourceMode.VPANEL_PRESET);
        }
        if (StudioWidgets.radioButtonAt(I18n.name("gui.wandscape.roadstudio.array_source_json",
                "JSON 文件模板").getString(),
                sourceMode == SplineEditorClientState.TemplateSourceMode.JSON_FILE, x + halfW, y)) {
            SplineEditorClientState.setTemplateSourceMode(SplineEditorClientState.TemplateSourceMode.JSON_FILE);
        }
        StudioWidgets.setY(y + 18);

        if (sourceMode == SplineEditorClientState.TemplateSourceMode.VPANEL_PRESET) {
            StudioWidgets.spacing();
            drawPresetCombo();

            StudioWidgets.spacing();
            int width = StudioWidgets.sliderInt("##dynW",
                    I18n.name("gui.wandscape.roadstudio.array_width", "道路宽度").getString(),
                    SplineEditorClientState.getDynamicWidth(), 1, 15);
            if (width != SplineEditorClientState.getDynamicWidth()) {
                SplineEditorClientState.setDynamicWidth(width);
            }

            int depth = StudioWidgets.sliderInt("##dynD",
                    I18n.name("gui.wandscape.roadstudio.array_depth", "基层厚度").getString(),
                    SplineEditorClientState.getDynamicDepth(), 1, 3);
            if (depth != SplineEditorClientState.getDynamicDepth()) {
                SplineEditorClientState.setDynamicDepth(depth);
            }

            boolean border = SplineEditorClientState.isDynamicHasBorder();
            if (StudioWidgets.checkbox(I18n.name("gui.wandscape.roadstudio.array_border",
                    "边缘石砖边框").getString(), border)) {
                SplineEditorClientState.setDynamicHasBorder(!border);
            }
        }

        // Preview toggle
        StudioWidgets.spacing();
        boolean preview = SplineEditorClientState.isArrayPreview();
        if (StudioWidgets.checkbox(I18n.name("gui.wandscape.roadstudio.array_preview",
                "预览 3D 阵列生成结果").getString(), preview)) {
            SplineEditorClientState.setArrayPreview(!preview);
        }

        // Step distance
        StudioWidgets.spacing();
        float stepDist = StudioWidgets.sliderFloat("##step",
                I18n.name("gui.wandscape.roadstudio.array_step", "采样步距 (格)").getString(),
                (float) SplineEditorClientState.getArrayStepDistance(), 0.5f, 8.0f, "%.1f");
        SplineEditorClientState.setArrayStepDistance(stepDist);

        // Rotation sliders
        StudioWidgets.spacing();
        StudioWidgets.textMuted(I18n.name("gui.wandscape.roadstudio.array_rot_label",
                "3D 阵列姿态旋转微调:").getString());

        float roll = StudioWidgets.sliderFloat("##roll",
                I18n.name("gui.wandscape.roadstudio.array_roll", "滚动角 Roll").getString(),
                (float) SplineEditorClientState.getArrayOffsetRoll(), -180f, 180f, "%.1f°");
        SplineEditorClientState.setArrayOffsetRoll(roll);

        float pitch = StudioWidgets.sliderFloat("##pitch",
                I18n.name("gui.wandscape.roadstudio.array_pitch", "俯仰角 Pitch").getString(),
                (float) SplineEditorClientState.getArrayOffsetPitch(), -180f, 180f, "%.1f°");
        SplineEditorClientState.setArrayOffsetPitch(pitch);

        float yaw = StudioWidgets.sliderFloat("##yaw",
                I18n.name("gui.wandscape.roadstudio.array_yaw", "偏航角 Yaw").getString(),
                (float) SplineEditorClientState.getArrayOffsetYaw(), -180f, 180f, "%.1f°");
        SplineEditorClientState.setArrayOffsetYaw(yaw);

        // Reset rotation button
        if (StudioWidgets.buttonFull("重置旋转为 0°", 18,
                StudioColors.BUTTON_NORMAL, StudioColors.BUTTON_HOVER)) {
            SplineEditorClientState.setArrayOffsetRoll(0);
            SplineEditorClientState.setArrayOffsetPitch(0);
            SplineEditorClientState.setArrayOffsetYaw(0);
        }

        // Build button
        StudioWidgets.spacingLarge();
        if (StudioWidgets.buttonFull(
                I18n.name("gui.wandscape.roadstudio.array_build",
                        "下发道路建造任务").getString(),
                26, StudioColors.BUTTON_GREEN, StudioColors.BUTTON_GREEN_HOVER)) {
            SplineEditorController.doBuildArray();
        }
    }

    // ── Sub-tab 3: Templates & Tools ──

    private static void drawTemplatesTab(SplineModel model, Minecraft mc) {
        StudioWidgets.spacing();
        StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.tpl_header",
                "模板文件管理").getString());

        int y = StudioWidgets.getY();
        int x = StudioWidgets.getLayoutX();
        int halfW = (StudioWidgets.getLayoutW() - 4) / 2;

        if (StudioWidgets.buttonAt(I18n.name("gui.wandscape.roadstudio.tpl_save",
                "保存 JSON 模板").getString(),
                x, y, halfW, 22,
                StudioColors.BUTTON_BLUE, StudioColors.BUTTON_HOVER, StudioColors.BUTTON_ACTIVE)) {
            SplineEditorClientState.saveTemplate("native_export");
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(
                        "§aSaved to config/wandscape/splines/native_export.json"), true);
            }
        }
        if (StudioWidgets.buttonAt(I18n.name("gui.wandscape.roadstudio.tpl_load",
                "读取 JSON 模板").getString(),
                x + halfW + 4, y, halfW, 22,
                StudioColors.BUTTON_BLUE_ALT, StudioColors.BUTTON_HOVER, StudioColors.BUTTON_ACTIVE)) {
            Vec3 pos = SplineEditorClientState.isEditing()
                    ? new Vec3(SplineEditorClientState.getCamX(),
                    SplineEditorClientState.getCamY(),
                    SplineEditorClientState.getCamZ())
                    : (mc.player != null ? mc.player.position() : Vec3.ZERO);
            SplineEditorClientState.loadTemplate("native_export",
                    new SplineVec3(pos.x, pos.y, pos.z));
        }
        StudioWidgets.setY(y + 22 + 6);

        // View tools
        StudioWidgets.spacing();
        StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.tpl_view_header",
                "视图与快捷工具").getString());

        boolean topDown = SplineEditorClientState.isTopDown();
        String topDownLabel = topDown
                ? I18n.name("gui.wandscape.roadstudio.tpl_exit_topdown",
                "退出 2D 俯瞰视角 (G)").getString()
                : I18n.name("gui.wandscape.roadstudio.tpl_enter_topdown",
                "切换 2D 俯瞰视角 (G)").getString();

        if (StudioWidgets.buttonFull(topDownLabel, 22,
                StudioColors.BUTTON_NORMAL, StudioColors.BUTTON_HOVER)) {
            if (topDown) SplineEditorClientState.exitTopDown();
            else SplineEditorClientState.enterTopDown();
        }

        if (StudioWidgets.buttonFull(
                I18n.name("gui.wandscape.roadstudio.tpl_help",
                        "打开操作指南 (H)").getString(),
                22, StudioColors.BUTTON_NORMAL, StudioColors.BUTTON_HOVER)) {
            String content = com.wsteam.wandscape.shared.ui.markdown.navigation.DocumentLoader.loadMarkdown("road_spline_guide");
            mc.setScreen(new com.wsteam.wandscape.shared.ui.guide.GuideTestScreen(null, content, "road_spline_guide"));
        }

        StudioWidgets.spacing();
        y = StudioWidgets.getY();
        halfW = (StudioWidgets.getLayoutW() - 4) / 2;

        if (StudioWidgets.buttonAt(I18n.name("gui.wandscape.roadstudio.tpl_clear",
                "清空画布").getString(),
                StudioWidgets.getLayoutX(), y, halfW, 22,
                StudioColors.BUTTON_NORMAL, StudioColors.BUTTON_HOVER, StudioColors.BUTTON_ACTIVE)) {
            model.clear();
            SplineEditorClientState.setSelectedPoint(-1, SplineEditorClientState.SelectionType.NONE);
        }

        if (StudioWidgets.buttonAt(I18n.name("gui.wandscape.roadstudio.tpl_close",
                "关闭 Studio").getString(),
                StudioWidgets.getLayoutX() + halfW + 4, y, halfW, 22,
                StudioColors.BUTTON_RED, StudioColors.BUTTON_RED_HOVER, StudioColors.BUTTON_RED_HOVER)) {
            close();
        }
        StudioWidgets.setY(y + 22 + 6);
    }

    // ════════════════════════════════════════════════════════════════
    //  FOOTER
    // ════════════════════════════════════════════════════════════════

    private static void drawBottomFooter(GuiGraphics g, Font font, int panelX, int panelW, int screenH) {
        int footerY = screenH - 16;
        g.fill(panelX + 1, footerY, panelX + panelW, footerY + 1, StudioColors.SEPARATOR);
        g.drawString(font, I18n.name("gui.wandscape.roadstudio.footer",
                " [右键按住] 旋转视角 | [G] 俯瞰 | [H] 指南 | [ESC] 退出").getString(),
                panelX + PAD, footerY + 3, StudioColors.TEXT_MUTED);
    }

    // ════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════

    private static void drawPositionControls(Minecraft mc, String label, boolean isStart) {
        BlockPos pos = isStart ? RoadPlacementState.getStartPos() : RoadPlacementState.getEndPos();
        int result = StudioWidgets.positionRow(label, pos);
        if (result == 1) {
            if (isStart) RoadPlacementState.clearStartPos();
            else RoadPlacementState.clearEndPos();
        } else if (result == 2) {
            BlockPos feet = getCapturedFeetPosition(mc);
            if (isStart) RoadPlacementState.setStartPos(feet);
            else RoadPlacementState.setEndPos(feet);
        }
    }

    private static BlockPos getCapturedFeetPosition(Minecraft mc) {
        if (SplineEditorClientState.isEditing()) {
            return BlockPos.containing(
                    SplineEditorClientState.getCamX(),
                    SplineEditorClientState.getCamY() - 1.0,
                    SplineEditorClientState.getCamZ());
        }
        if (mc.player != null) {
            return mc.player.blockPosition().below();
        }
        return BlockPos.ZERO;
    }

    private static void submitRoadReplace(Minecraft mc) {
        if (!RoadPlacementState.isReady()) return;
        BlockPos start = RoadPlacementState.getStartPos();
        BlockPos end = RoadPlacementState.getEndPos();
        String presetId = RoadPlacementState.getSelectedPreset().id();
        PacketDistributor.sendToServer(
                new com.wsteam.wandscape.road.network.RoadPlacePacket(presetId, start, end));
        Log.info(TAG, "[RoadReplace] Published: preset={} start={} end={}", presetId, start, end);
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[Road] §aRoad task submitted!"), true);
        }
        RoadPlacementState.clearAll();
    }

    private static void submitFillBox(Minecraft mc) {
        if (!RoadPlacementState.isReady()) return;
        BlockPos start = RoadPlacementState.getStartPos();
        BlockPos end = RoadPlacementState.getEndPos();
        String presetId = RoadPlacementState.getSelectedPreset().id();
        PacketDistributor.sendToServer(
                new com.wsteam.wandscape.road.network.FillBoxPacket(presetId, start, end));
        Log.info(TAG, "[FillBox] Published: preset={} start={} end={}", presetId, start, end);
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[Fill] §aFill task submitted!"), true);
        }
        RoadPlacementState.clearAll();
    }

    private static void submitDestroyFill(Minecraft mc) {
        if (!RoadPlacementState.isReady()) return;
        BlockPos start = RoadPlacementState.getStartPos();
        BlockPos end = RoadPlacementState.getEndPos();
        PacketDistributor.sendToServer(
                new com.wsteam.wandscape.road.network.DestroyFillPacket(start, end));
        Log.info(TAG, "[DestroyFill] Published: start={} end={}", start, end);
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[Destroy/Fill] §aTerrain flatten task submitted!"), true);
        }
        RoadPlacementState.clearAll();
    }
}
