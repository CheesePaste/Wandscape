package com.wsteam.wandscape.road.client.modernui;

import java.util.List;
import java.util.Locale;

import com.wsteam.wandscape.road.client.RoadPlacementController;
import com.wsteam.wandscape.road.client.RoadPlacementState;
import com.wsteam.wandscape.road.client.SplineEditorClientState;
import com.wsteam.wandscape.road.client.SplineEditorController;
import com.wsteam.wandscape.road.core.RoadTemplate;
import com.wsteam.wandscape.road.core.SplineModel;
import com.wsteam.wandscape.road.core.SplinePoint;
import com.wsteam.wandscape.road.core.SplineVec3;
import com.wsteam.wandscape.road.data.RoadPreset;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.ui.I18n;

import icyllis.modernui.core.Context;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.graphics.drawable.ShapeDrawable;
import icyllis.modernui.mc.ScreenCallback;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.MotionEvent;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.CheckBox;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.SeekBar;
import icyllis.modernui.widget.SwitchButton;
import icyllis.modernui.widget.TextView;
import icyllis.modernui.widget.Toast;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;

/**
 * Pure Java ModernUI Road & Spline Studio.
 * <p>
 * Implements ScreenCallback with transparent background and no blur/pause,
 * allowing simultaneous 3D world viewports, freecam navigation, and UI interactions.
 */
public class RoadStudioFragment extends Fragment implements ScreenCallback {
    private static final String TAG = "RoadStudioFragment";

    // Colors matching Catppuccin Mocha / Wandscape Theme
    private static final int COLOR_BG_DOCK      = 0xF0181926; // Dark glass background
    private static final int COLOR_BORDER_DOCK  = 0xFF89B4FA; // Soft blue border
    private static final int COLOR_CARD_BG      = 0xCC24273A; // Elevated card background
    private static final int COLOR_CARD_BORDER  = 0xFF363A4F; // Subtle border
    private static final int COLOR_ACCENT_BLUE  = 0xFF89B4FA; // Blue accent
    private static final int COLOR_ACCENT_GOLD  = 0xFFF9E2AF; // Gold accent
    private static final int COLOR_ACCENT_GREEN = 0xFFA6E3A1; // Green accent
    private static final int COLOR_ACCENT_RED   = 0xFFF38BA8; // Red / Delete accent
    private static final int COLOR_TEXT_MUTED   = 0xFFA6ADC8; // Subtitle text
    private static final int COLOR_TEXT_DIM     = 0xFF6E738D; // Hint text
    private static final int COLOR_TEXT_WHITE   = 0xFFCDD6F4; // Primary text

    private FrameLayout rootLayout;
    private LinearLayout dockPanel;
    private TextView subtitleText;
    private LinearLayout contentContainer;

    // Spline mode tab state: 0 = Curve, 1 = Array, 2 = Templates
    private int splineActiveTab = 0;

    // Refresh synchronization flag
    private boolean suppressTextUpdates = false;

    @Override
    public boolean hasDefaultBackground() {
        return false; // NO dark scrim / background dimming!
    }

    @Override
    public boolean shouldBlurBackground() {
        return false; // NO screen blur!
    }

    @Override
    public boolean isPauseScreen() {
        return false; // Do NOT pause the game!
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, DataSet savedInstanceState) {
        rootLayout = new FrameLayout(getContext());
        rootLayout.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        buildDockPanel();
        refreshAllUI();

        return rootLayout;
    }

    private void buildDockPanel() {
        if (rootLayout == null) return;
        rootLayout.removeAllViews();

        int panelWidth = dp(RoadStudioModernUI.getPanelWidthDp());

        // Right-dock container
        dockPanel = new LinearLayout(getContext());
        dockPanel.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams dockParams = new FrameLayout.LayoutParams(
                panelWidth,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.END | Gravity.TOP
        );
        dockPanel.setLayoutParams(dockParams);

        // Dark glass background with left-side rounded corners
        ShapeDrawable dockBg = new ShapeDrawable();
        dockBg.setCornerRadii(dp(14), 0, 0, dp(14));
        dockBg.setColor(COLOR_BG_DOCK);
        dockBg.setStroke(dp(1.5f), COLOR_BORDER_DOCK);
        dockPanel.setBackground(dockBg);
        dockPanel.setPadding(dp(14), dp(12), dp(14), dp(10));

        // 1. Header Banner
        buildHeaderSection();

        // 2. Tool Mode Switcher Bar
        buildModeSwitcherBar();

        // 3. Scrollable Content Area
        ScrollView scrollView = new ScrollView(getContext());
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f // Weight 1.0 to fill middle space
        );
        scrollParams.topMargin = dp(8);
        scrollParams.bottomMargin = dp(8);
        scrollView.setLayoutParams(scrollParams);
        scrollView.setVerticalScrollBarEnabled(true);

        contentContainer = new LinearLayout(getContext());
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        scrollView.addView(contentContainer);
        dockPanel.addView(scrollView);

        // 4. Footer Bar
        buildFooterSection();

        rootLayout.addView(dockPanel);
    }

    // ── Header Section ──
    private void buildHeaderSection() {
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        header.setPadding(dp(4), dp(4), dp(4), dp(8));

        LinearLayout titleCol = new LinearLayout(getContext());
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView titleText = new TextView(getContext());
        titleText.setText("✦ WANDSCAPE 道路制作工坊");
        titleText.setTextSize(17);
        titleText.setTextColor(COLOR_ACCENT_GOLD);
        titleCol.addView(titleText);

        subtitleText = new TextView(getContext());
        subtitleText.setTextSize(12);
        subtitleText.setTextColor(COLOR_ACCENT_BLUE);
        updateSubtitleText();
        titleCol.addView(subtitleText);

        header.addView(titleCol);

        // Close Button (✕)
        Button closeBtn = createStyledButton("✕", COLOR_CARD_BG, COLOR_ACCENT_RED, dp(32), dp(28));
        closeBtn.setOnClickListener(v -> {
            SplineEditorClientState.exitEditMode();
            RoadStudioModernUI.close();
        });
        header.addView(closeBtn);

        dockPanel.addView(header);
    }

    private void updateSubtitleText() {
        if (subtitleText == null) return;
        String toolName = switch (RoadPlacementState.getActiveTool()) {
            case REPLACE -> "直线替换";
            case FILL -> "立方体填充";
            case DESTROY_FILL -> "铲平垫平";
            case SPLINE -> "样条曲线";
        };
        String viewName = SplineEditorClientState.isTopDown() ? "2D 俯瞰" : "3D 自由";
        subtitleText.setText(String.format(Locale.ROOT, "模式: %s  |  视角: %s", toolName, viewName));
    }

    // ── Mode Switcher Bar ──
    private void buildModeSwitcherBar() {
        LinearLayout modeBar = new LinearLayout(getContext());
        modeBar.setOrientation(LinearLayout.HORIZONTAL);
        modeBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        modeBar.setPadding(0, dp(4), 0, dp(6));

        RoadPlacementState.ToolMode currentTool = RoadPlacementState.getActiveTool();

        addModeButton(modeBar, "替换", RoadPlacementState.ToolMode.REPLACE, currentTool);
        addModeButton(modeBar, "填充", RoadPlacementState.ToolMode.FILL, currentTool);
        addModeButton(modeBar, "铲平", RoadPlacementState.ToolMode.DESTROY_FILL, currentTool);
        addModeButton(modeBar, "样条", RoadPlacementState.ToolMode.SPLINE, currentTool);

        dockPanel.addView(modeBar);
    }

    private void addModeButton(LinearLayout bar, String label, RoadPlacementState.ToolMode mode, RoadPlacementState.ToolMode active) {
        boolean isSelected = (mode == active);
        int bgColor = isSelected ? 0xFF4A3B69 : COLOR_CARD_BG;
        int borderColor = isSelected ? COLOR_ACCENT_GOLD : COLOR_CARD_BORDER;

        Button btn = createStyledButton(label, bgColor, isSelected ? COLOR_ACCENT_GOLD : COLOR_TEXT_WHITE, 0, dp(30));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(30), 1.0f);
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        btn.setLayoutParams(params);

        btn.setOnClickListener(v -> {
            RoadPlacementState.setActiveTool(mode);
            buildDockPanel();
            refreshAllUI();
        });

        bar.addView(btn);
    }

    // ── Footer Section ──
    private void buildFooterSection() {
        LinearLayout footer = new LinearLayout(getContext());
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        footer.setPadding(dp(4), dp(6), dp(4), dp(2));

        // Separator line
        View divider = new View(getContext());
        divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        divider.setBackground(new ColorDrawable(COLOR_CARD_BORDER));
        footer.addView(divider);

        TextView hintText = new TextView(getContext());
        hintText.setText("[右键按住] 旋转视角 | [G] 俯瞰 | [H] 指南 | [ESC] 退出");
        hintText.setTextSize(11);
        hintText.setTextColor(COLOR_TEXT_DIM);
        hintText.setPadding(0, dp(6), 0, 0);
        footer.addView(hintText);

        dockPanel.addView(footer);
    }

    // ── Dynamic Content Refresh ──
    private void refreshAllUI() {
        if (contentContainer == null) return;
        contentContainer.removeAllViews();
        updateSubtitleText();

        RoadPlacementState.ToolMode mode = RoadPlacementState.getActiveTool();
        switch (mode) {
            case REPLACE -> buildReplaceModeView();
            case FILL -> buildFillModeView();
            case DESTROY_FILL -> buildDestroyFillModeView();
            case SPLINE -> buildSplineModeView();
        }
    }

    public void requestStateRefresh() {
        if (dockPanel != null) {
            dockPanel.post(this::refreshAllUI);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Mode 1: 直线替换 (REPLACE)
    // ══════════════════════════════════════════════════════════════════════
    private void buildReplaceModeView() {
        Minecraft mc = Minecraft.getInstance();

        // 1. Preset Selector Card
        LinearLayout presetCard = createCardView("铺设方块预设");
        buildPresetSelectorIntoCard(presetCard);
        contentContainer.addView(presetCard);

        // 2. Start & End Coordinates Card
        LinearLayout pointsCard = createCardView("铺设路线起终点");
        buildPointControlIntoCard(pointsCard, "起点坐标 (Start)", RoadPlacementState.getStartPos(), true);
        buildPointControlIntoCard(pointsCard, "终点坐标 (End)", RoadPlacementState.getEndPos(), false);
        contentContainer.addView(pointsCard);

        // 3. Evaluation Card
        BlockPos start = RoadPlacementState.getStartPos();
        BlockPos end = RoadPlacementState.getEndPos();
        LinearLayout evalCard = createCardView("铺设数据评估");
        if (start != null && end != null) {
            int dx = Math.abs(end.getX() - start.getX()) + 1;
            int dz = Math.abs(end.getZ() - start.getZ()) + 1;
            double dist = Math.sqrt((double) (end.getX() - start.getX()) * (end.getX() - start.getX())
                    + (double) (end.getZ() - start.getZ()) * (end.getZ() - start.getZ()));
            evalCard.addView(createValueLabel("覆盖跨度: " + dx + " × " + dz + " 方块范围"));
            evalCard.addView(createValueLabel(String.format(Locale.ROOT, "直线距离: %.1f 方块", dist)));
        } else {
            evalCard.addView(createMutedLabel("提示: 请在世界中点击或捕捉脚下以设定起点与终点。"));
        }
        contentContainer.addView(evalCard);

        // 4. Submit Button
        Button submitBtn = createActionButton("下发直线铺设任务", 0xFF2D5A27, COLOR_ACCENT_GREEN);
        submitBtn.setOnClickListener(v -> {
            if (RoadPlacementState.isReady()) {
                String presetId = RoadPlacementState.getSelectedPreset().id();
                PacketDistributor.sendToServer(new com.wsteam.wandscape.road.network.RoadPlacePacket(presetId, start, end));
                Log.info(TAG, "[RoadReplace] Published road place: preset={} start={} end={}", presetId, start, end);
                Toast.makeText(getContext(), "已下发直线铺设任务！NPC 将自动执行", Toast.LENGTH_SHORT).show();
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal("[Road] §aRoad task submitted! NPC will pave the path."), true);
                }
                RoadPlacementState.clearAll();
                refreshAllUI();
            } else {
                Toast.makeText(getContext(), "请先设定起点和终点！", Toast.LENGTH_SHORT).show();
            }
        });
        contentContainer.addView(submitBtn);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Mode 2: 立方体填充 (FILL)
    // ══════════════════════════════════════════════════════════════════════
    private void buildFillModeView() {
        Minecraft mc = Minecraft.getInstance();

        // 1. Preset Selector Card
        LinearLayout presetCard = createCardView("填充方块预设");
        buildPresetSelectorIntoCard(presetCard);
        contentContainer.addView(presetCard);

        // 2. Corner Coordinates Card
        LinearLayout pointsCard = createCardView("3D 立方体对角点");
        buildPointControlIntoCard(pointsCard, "角点 1 (Start)", RoadPlacementState.getStartPos(), true);
        buildPointControlIntoCard(pointsCard, "角点 2 (End)", RoadPlacementState.getEndPos(), false);
        contentContainer.addView(pointsCard);

        // 3. Evaluation Card
        BlockPos start = RoadPlacementState.getStartPos();
        BlockPos end = RoadPlacementState.getEndPos();
        LinearLayout evalCard = createCardView("立方体体积评估");
        if (start != null && end != null) {
            int dx = Math.abs(end.getX() - start.getX()) + 1;
            int dy = Math.abs(end.getY() - start.getY()) + 1;
            int dz = Math.abs(end.getZ() - start.getZ()) + 1;
            long volume = (long) dx * dy * dz;
            evalCard.addView(createValueLabel(String.format(Locale.ROOT, "尺寸: %d (宽) × %d (高) × %d (深)", dx, dy, dz)));
            evalCard.addView(createValueLabel(String.format(Locale.ROOT, "总体积: %d 个方块", volume)));
        } else {
            evalCard.addView(createMutedLabel("提示: 请选择两个对角点以确定立方体空间。"));
        }
        contentContainer.addView(evalCard);

        // 4. Submit Button
        Button submitBtn = createActionButton("下发立方体填充任务", 0xFF2D5A27, COLOR_ACCENT_GREEN);
        submitBtn.setOnClickListener(v -> {
            if (RoadPlacementState.isReady()) {
                String presetId = RoadPlacementState.getSelectedPreset().id();
                PacketDistributor.sendToServer(new com.wsteam.wandscape.road.network.FillBoxPacket(presetId, start, end));
                Log.info(TAG, "[FillBox] Published fill box: preset={} start={} end={}", presetId, start, end);
                Toast.makeText(getContext(), "已下发立方体填充任务！NPC 将自动执行", Toast.LENGTH_SHORT).show();
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal("[Fill] §aFill task submitted! NPC will fill the cube."), true);
                }
                RoadPlacementState.clearAll();
                refreshAllUI();
            } else {
                Toast.makeText(getContext(), "请先设定两个对角点！", Toast.LENGTH_SHORT).show();
            }
        });
        contentContainer.addView(submitBtn);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Mode 3: 铲平垫平 (DESTROY_FILL)
    // ══════════════════════════════════════════════════════════════════════
    private void buildDestroyFillModeView() {
        Minecraft mc = Minecraft.getInstance();

        // 1. Reference Block Card
        LinearLayout refCard = createCardView("参照基准方块");
        String refBlock = RoadPlacementState.getRefBlockId();
        if (refBlock.isEmpty()) {
            refCard.addView(createMutedLabel("未捕获参照方块 (在世界中右键点击方块捕获)"));
        } else {
            TextView refLabel = new TextView(getContext());
            refLabel.setText("参照方块: " + refBlock);
            refLabel.setTextColor(COLOR_ACCENT_GREEN);
            refLabel.setTextSize(13);
            refCard.addView(refLabel);
        }
        Button captureFeetBtn = createStyledButton("捕捉脚下方块为参照", COLOR_CARD_BG, COLOR_ACCENT_BLUE, ViewGroup.LayoutParams.MATCH_PARENT, dp(26));
        captureFeetBtn.setOnClickListener(v -> {
            BlockPos feet = getCapturedFeetPosition(mc);
            RoadPlacementState.setStartPos(feet);
            if (mc.level != null) {
                var st = mc.level.getBlockState(feet);
                String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                RoadPlacementState.setRefBlockId(id);
            }
            refreshAllUI();
        });
        refCard.addView(captureFeetBtn);
        contentContainer.addView(refCard);

        // 2. Area Boundary Card
        LinearLayout boundaryCard = createCardView("平整区域边界");
        buildPointControlIntoCard(boundaryCard, "边界起点 (Start)", RoadPlacementState.getStartPos(), true);
        buildPointControlIntoCard(boundaryCard, "边界终点 (End)", RoadPlacementState.getEndPos(), false);
        contentContainer.addView(boundaryCard);

        // 3. Evaluation Card
        BlockPos start = RoadPlacementState.getStartPos();
        BlockPos end = RoadPlacementState.getEndPos();
        LinearLayout evalCard = createCardView("平整面积评估");
        if (start != null && end != null) {
            int dx = Math.abs(end.getX() - start.getX()) + 1;
            int dz = Math.abs(end.getZ() - start.getZ()) + 1;
            evalCard.addView(createValueLabel(String.format(Locale.ROOT, "底面尺寸: %d × %d 方块", dx, dz)));
            evalCard.addView(createValueLabel(String.format(Locale.ROOT, "平整面积: %d 平方方块", (long) dx * dz)));
        } else {
            evalCard.addView(createMutedLabel("提示: 请选择起点与终点以确定平整区域。"));
        }
        contentContainer.addView(evalCard);

        // 4. Submit Button
        Button submitBtn = createActionButton("下发地形平整任务", 0xFF6B2B2B, COLOR_ACCENT_RED);
        submitBtn.setOnClickListener(v -> {
            if (RoadPlacementState.isReady()) {
                PacketDistributor.sendToServer(new com.wsteam.wandscape.road.network.DestroyFillPacket(start, end));
                Log.info(TAG, "[DestroyFill] Published destroy fill: start={} end={}", start, end);
                Toast.makeText(getContext(), "已下发地形平整任务！NPC 将自动执行", Toast.LENGTH_SHORT).show();
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal("[Destroy/Fill] §aTerrain flatten task submitted! NPC will flatten the area."), true);
                }
                RoadPlacementState.clearAll();
                refreshAllUI();
            } else {
                Toast.makeText(getContext(), "请先设定平整区域边界！", Toast.LENGTH_SHORT).show();
            }
        });
        contentContainer.addView(submitBtn);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Mode 4: 样条曲线 (SPLINE)
    // ══════════════════════════════════════════════════════════════════════
    private void buildSplineModeView() {
        // Sub-tabs for Spline Mode: [ 曲线编辑 ] [ 阵列生成 ] [ 模板与工具 ]
        LinearLayout subTabBar = new LinearLayout(getContext());
        subTabBar.setOrientation(LinearLayout.HORIZONTAL);
        subTabBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        subTabBar.setPadding(0, 0, 0, dp(6));

        addSplineSubTabButton(subTabBar, "曲线编辑", 0);
        addSplineSubTabButton(subTabBar, "阵列生成", 1);
        addSplineSubTabButton(subTabBar, "模板与工具", 2);

        contentContainer.addView(subTabBar);

        SplineModel model = SplineEditorClientState.getModel();
        switch (splineActiveTab) {
            case 0 -> buildSplineCurveNodesTab(model);
            case 1 -> buildSplineArrayStudioTab();
            case 2 -> buildSplineTemplatesTab(model);
        }
    }

    private void addSplineSubTabButton(LinearLayout bar, String label, int tabIndex) {
        boolean isSelected = (splineActiveTab == tabIndex);
        int bgColor = isSelected ? 0xFF313244 : COLOR_CARD_BG;
        int textColor = isSelected ? COLOR_ACCENT_BLUE : COLOR_TEXT_MUTED;

        Button btn = createStyledButton(label, bgColor, textColor, 0, dp(28));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(28), 1.0f);
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        btn.setLayoutParams(params);

        btn.setOnClickListener(v -> {
            splineActiveTab = tabIndex;
            refreshAllUI();
        });

        bar.addView(btn);
    }

    // ── Spline Sub-Tab 1: 曲线编辑 ──
    private void buildSplineCurveNodesTab(SplineModel model) {
        // 1. Edit Mode Switch: [ + 点击添加点 ] / [ ✎ 选择与拖拽 ]
        LinearLayout modeRow = new LinearLayout(getContext());
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        boolean isAdd = SplineEditorClientState.getEditMode() == SplineEditorClientState.EditMode.ADD;
        Button addBtn = createStyledButton("+ 点击添加点", isAdd ? 0xFF4A3B69 : COLOR_CARD_BG, isAdd ? COLOR_ACCENT_GOLD : COLOR_TEXT_WHITE, 0, dp(32));
        addBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(32), 1.0f));
        addBtn.setOnClickListener(v -> {
            SplineEditorClientState.setEditMode(SplineEditorClientState.EditMode.ADD);
            refreshAllUI();
        });

        Button editBtn = createStyledButton("✎ 选择与拖拽", !isAdd ? 0xFF4A3B69 : COLOR_CARD_BG, !isAdd ? COLOR_ACCENT_GOLD : COLOR_TEXT_WHITE, 0, dp(32));
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(0, dp(32), 1.0f);
        editParams.leftMargin = dp(6);
        editBtn.setLayoutParams(editParams);
        editBtn.setOnClickListener(v -> {
            SplineEditorClientState.setEditMode(SplineEditorClientState.EditMode.EDIT);
            refreshAllUI();
        });

        modeRow.addView(addBtn);
        modeRow.addView(editBtn);
        contentContainer.addView(modeRow);

        // 2. Curve Geometry & Closed Loop
        LinearLayout geomCard = createCardView("曲线几何与整体平移");
        CheckBox closedBox = new CheckBox(getContext());
        closedBox.setText("闭合环形道路 (连接首尾)");
        closedBox.setChecked(model.isClosed());
        closedBox.setTextColor(COLOR_TEXT_WHITE);
        closedBox.setOnCheckedChangeListener((btn, checked) -> {
            model.setClosed(checked);
        });
        geomCard.addView(closedBox);

        // Global Translation Inputs (X, Y, Z) + Translate Button
        geomCard.addView(createMutedLabel("整体平移偏移量 (X, Y, Z):"));
        LinearLayout shiftRow = new LinearLayout(getContext());
        shiftRow.setOrientation(LinearLayout.HORIZONTAL);
        shiftRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText shiftX = createNumberInput("0.0", 0, dp(30), 1.0f);
        EditText shiftY = createNumberInput("0.0", 0, dp(30), 1.0f);
        EditText shiftZ = createNumberInput("0.0", 0, dp(30), 1.0f);
        shiftY.setLayoutParams(new LinearLayout.LayoutParams(0, dp(30), 1.0f));
        ((LinearLayout.LayoutParams) shiftY.getLayoutParams()).leftMargin = dp(4);
        shiftZ.setLayoutParams(new LinearLayout.LayoutParams(0, dp(30), 1.0f));
        ((LinearLayout.LayoutParams) shiftZ.getLayoutParams()).leftMargin = dp(4);

        Button shiftBtn = createStyledButton("平移", COLOR_CARD_BG, COLOR_ACCENT_BLUE, dp(64), dp(30));
        ((LinearLayout.LayoutParams) shiftBtn.getLayoutParams()).leftMargin = dp(6);
        shiftBtn.setOnClickListener(v -> {
            double dx = parseDoubleSafe(shiftX.getText().toString(), 0.0);
            double dy = parseDoubleSafe(shiftY.getText().toString(), 0.0);
            double dz = parseDoubleSafe(shiftZ.getText().toString(), 0.0);
            SplineVec3 delta = new SplineVec3(dx, dy, dz);
            model.translateAll(delta);
            shiftX.setText("0.0");
            shiftY.setText("0.0");
            shiftZ.setText("0.0");
            Toast.makeText(getContext(), "已整体平移曲线！", Toast.LENGTH_SHORT).show();
            refreshAllUI();
        });

        shiftRow.addView(shiftX);
        shiftRow.addView(shiftY);
        shiftRow.addView(shiftZ);
        shiftRow.addView(shiftBtn);
        geomCard.addView(shiftRow);
        contentContainer.addView(geomCard);

        // 3. Points List Card
        List<SplinePoint> points = model.getPoints();
        LinearLayout listCard = createCardView("控制点列表 (" + points.size() + ")");
        if (points.isEmpty()) {
            listCard.addView(createMutedLabel("当前无控制点。在 3D 世界中左键点击方块即可添加！"));
        } else {
            for (int i = 0; i < points.size(); i++) {
                int pointIdx = i;
                SplinePoint pt = points.get(i);
                SplineVec3 anchor = pt.getAnchor();
                boolean isSelected = (SplineEditorClientState.getSelectedPointIndex() == pointIdx);

                LinearLayout itemCard = new LinearLayout(getContext());
                itemCard.setOrientation(LinearLayout.HORIZONTAL);
                itemCard.setGravity(Gravity.CENTER_VERTICAL);
                itemCard.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(28)
                ));
                ((LinearLayout.LayoutParams) itemCard.getLayoutParams()).bottomMargin = dp(2);

                ShapeDrawable itemBg = new ShapeDrawable();
                itemBg.setCornerRadius(dp(4));
                itemBg.setColor(isSelected ? 0xFF4A3B69 : 0x881E1E2E);
                if (isSelected) {
                    itemBg.setStroke(dp(1), COLOR_ACCENT_GOLD);
                }
                itemCard.setBackground(itemBg);
                itemCard.setPadding(dp(8), 0, dp(8), 0);

                String symTag = pt.isLocked() ? "[对称]" : "[自由]";
                String label = String.format(Locale.ROOT, "#%d  (%.1f, %.1f, %.1f) %s", pointIdx, anchor.x(), anchor.y(), anchor.z(), symTag);

                TextView itemLabel = new TextView(getContext());
                itemLabel.setText(label);
                itemLabel.setTextSize(12);
                itemLabel.setTextColor(isSelected ? COLOR_ACCENT_GOLD : COLOR_TEXT_WHITE);
                itemCard.addView(itemLabel);

                itemCard.setOnClickListener(v -> {
                    SplineEditorClientState.setSelectedPoint(pointIdx, SplineEditorClientState.SelectionType.ANCHOR);
                    SplineEditorClientState.setEditMode(SplineEditorClientState.EditMode.EDIT);
                    refreshAllUI();
                });

                listCard.addView(itemCard);
            }
        }
        contentContainer.addView(listCard);

        // 4. Node Inspector Card (when point selected)
        int selectedIdx = SplineEditorClientState.getSelectedPointIndex();
        if (selectedIdx >= 0 && selectedIdx < points.size()) {
            SplinePoint pt = points.get(selectedIdx);
            SplineEditorClientState.SelectionType selType = SplineEditorClientState.getSelectedType();

            LinearLayout inspectorCard = createCardView("节点属性检查器 #" + selectedIdx);

            // Target selector: [ 主锚点 ] [ 前手柄 ] [ 后手柄 ]
            inspectorCard.addView(createMutedLabel("调整句柄目标:"));
            LinearLayout targetRow = new LinearLayout(getContext());
            targetRow.setOrientation(LinearLayout.HORIZONTAL);
            targetRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            addTargetButton(targetRow, "主锚点", SplineEditorClientState.SelectionType.ANCHOR, selType, selectedIdx);
            addTargetButton(targetRow, "前手柄", SplineEditorClientState.SelectionType.CONTROL_PREV, selType, selectedIdx);
            addTargetButton(targetRow, "后手柄", SplineEditorClientState.SelectionType.CONTROL_NEXT, selType, selectedIdx);
            inspectorCard.addView(targetRow);

            SplineVec3 targetPos = switch (selType) {
                case ANCHOR -> pt.getAnchor();
                case CONTROL_PREV -> pt.getControlPrev();
                case CONTROL_NEXT -> pt.getControlNext();
                default -> null;
            };

            if (targetPos != null) {
                inspectorCard.addView(createMutedLabel("三维精确坐标:"));
                LinearLayout coordRow = new LinearLayout(getContext());
                coordRow.setOrientation(LinearLayout.HORIZONTAL);
                coordRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                EditText cx = createNumberInput(String.format(Locale.ROOT, "%.2f", targetPos.x()), 0, dp(30), 1.0f);
                EditText cy = createNumberInput(String.format(Locale.ROOT, "%.2f", targetPos.y()), 0, dp(30), 1.0f);
                EditText cz = createNumberInput(String.format(Locale.ROOT, "%.2f", targetPos.z()), 0, dp(30), 1.0f);
                cy.setLayoutParams(new LinearLayout.LayoutParams(0, dp(30), 1.0f));
                ((LinearLayout.LayoutParams) cy.getLayoutParams()).leftMargin = dp(4);
                cz.setLayoutParams(new LinearLayout.LayoutParams(0, dp(30), 1.0f));
                ((LinearLayout.LayoutParams) cz.getLayoutParams()).leftMargin = dp(4);

                TextWatcher watcher = new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                    @Override
                    public void afterTextChanged(Editable s) {
                        if (suppressTextUpdates) return;
                        double nx = parseDoubleSafe(cx.getText().toString(), targetPos.x());
                        double ny = parseDoubleSafe(cy.getText().toString(), targetPos.y());
                        double nz = parseDoubleSafe(cz.getText().toString(), targetPos.z());
                        SplineVec3 updated = new SplineVec3(nx, ny, nz);
                        switch (selType) {
                            case ANCHOR -> pt.setAnchor(updated);
                            case CONTROL_PREV -> pt.setControlPrev(updated);
                            case CONTROL_NEXT -> pt.setControlNext(updated);
                        }
                    }
                };
                cx.addTextChangedListener(watcher);
                cy.addTextChangedListener(watcher);
                cz.addTextChangedListener(watcher);

                coordRow.addView(cx);
                coordRow.addView(cy);
                coordRow.addView(cz);
                inspectorCard.addView(coordRow);
            }

            // Tangent Lock Checkbox & Camera Focus Button
            LinearLayout actionRow = new LinearLayout(getContext());
            actionRow.setOrientation(LinearLayout.HORIZONTAL);
            actionRow.setGravity(Gravity.CENTER_VERTICAL);
            actionRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            actionRow.setPadding(0, dp(4), 0, 0);

            CheckBox lockBox = new CheckBox(getContext());
            lockBox.setText("对称切线手柄锁定");
            lockBox.setChecked(pt.isLocked());
            lockBox.setTextColor(COLOR_TEXT_WHITE);
            lockBox.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
            lockBox.setOnCheckedChangeListener((btn, checked) -> {
                pt.setLocked(checked);
            });
            actionRow.addView(lockBox);

            Button focusBtn = createStyledButton("视角聚焦", COLOR_CARD_BG, COLOR_ACCENT_BLUE, dp(80), dp(28));
            focusBtn.setOnClickListener(v -> {
                SplineVec3 pos = pt.getAnchor();
                SplineEditorClientState.setCamPosition(pos.x(), pos.y() + 3.0, pos.z() + 5.0);
            });
            actionRow.addView(focusBtn);
            inspectorCard.addView(actionRow);

            // Delete Node Button
            Button deleteBtn = createStyledButton("删除节点 #" + selectedIdx, 0xFF6B2B2B, COLOR_ACCENT_RED, ViewGroup.LayoutParams.MATCH_PARENT, dp(30));
            ((LinearLayout.LayoutParams) deleteBtn.getLayoutParams()).topMargin = dp(6);
            deleteBtn.setOnClickListener(v -> {
                model.removePoint(selectedIdx);
                int after = model.getPoints().size();
                if (after > 0) {
                    int nextIdx = Math.min(selectedIdx, after - 1);
                    SplineEditorClientState.setSelectedPoint(nextIdx, SplineEditorClientState.SelectionType.ANCHOR);
                } else {
                    SplineEditorClientState.setSelectedPoint(-1, SplineEditorClientState.SelectionType.NONE);
                }
                refreshAllUI();
            });
            inspectorCard.addView(deleteBtn);

            contentContainer.addView(inspectorCard);
        }
    }

    private void addTargetButton(LinearLayout row, String label, SplineEditorClientState.SelectionType type, SplineEditorClientState.SelectionType current, int pointIdx) {
        boolean isSelected = (type == current);
        Button btn = createStyledButton(label, isSelected ? 0xFF4A3B69 : COLOR_CARD_BG, isSelected ? COLOR_ACCENT_GOLD : COLOR_TEXT_MUTED, 0, dp(26));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(26), 1.0f);
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        btn.setLayoutParams(params);
        btn.setOnClickListener(v -> {
            SplineEditorClientState.setSelectedPoint(pointIdx, type);
            refreshAllUI();
        });
        row.addView(btn);
    }

    // ── Spline Sub-Tab 2: 阵列生成 ──
    private void buildSplineArrayStudioTab() {
        // 1. Template Source Switcher Card
        LinearLayout sourceCard = createCardView("模板来源与联动");
        var sourceMode = SplineEditorClientState.getTemplateSourceMode();

        LinearLayout sourceRow = new LinearLayout(getContext());
        sourceRow.setOrientation(LinearLayout.HORIZONTAL);
        sourceRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        boolean isVPanel = (sourceMode == SplineEditorClientState.TemplateSourceMode.VPANEL_PRESET);
        Button vPanelBtn = createStyledButton("V 面板方块预设 (推荐)", isVPanel ? 0xFF4A3B69 : COLOR_CARD_BG, isVPanel ? COLOR_ACCENT_GOLD : COLOR_TEXT_MUTED, 0, dp(30));
        vPanelBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(30), 1.0f));
        vPanelBtn.setOnClickListener(v -> {
            SplineEditorClientState.setTemplateSourceMode(SplineEditorClientState.TemplateSourceMode.VPANEL_PRESET);
            refreshAllUI();
        });

        Button jsonBtn = createStyledButton("JSON 蓝图文件", !isVPanel ? 0xFF4A3B69 : COLOR_CARD_BG, !isVPanel ? COLOR_ACCENT_GOLD : COLOR_TEXT_MUTED, 0, dp(30));
        LinearLayout.LayoutParams jsonParams = new LinearLayout.LayoutParams(0, dp(30), 1.0f);
        jsonParams.leftMargin = dp(4);
        jsonBtn.setLayoutParams(jsonParams);
        jsonBtn.setOnClickListener(v -> {
            SplineEditorClientState.setTemplateSourceMode(SplineEditorClientState.TemplateSourceMode.JSON_FILE);
            refreshAllUI();
        });

        sourceRow.addView(vPanelBtn);
        sourceRow.addView(jsonBtn);
        sourceCard.addView(sourceRow);
        contentContainer.addView(sourceCard);

        // 2. Generator Specs Card
        if (isVPanel) {
            LinearLayout dynCard = createCardView("动态道路规格生成器");
            buildPresetSelectorIntoCard(dynCard);

            // Width Slider (1 ~ 15)
            dynCard.addView(createSliderRow("道路宽度 (Width):", SplineEditorClientState.getDynamicWidth(), 1, 15, "格方块", val -> {
                SplineEditorClientState.setDynamicWidth(val);
                SplineEditorClientState.rebuildDynamicTemplate();
            }));

            // Depth Slider (1 ~ 3)
            dynCard.addView(createSliderRow("基层厚度 (Depth):", SplineEditorClientState.getDynamicDepth(), 1, 3, "层深", val -> {
                SplineEditorClientState.setDynamicDepth(val);
                SplineEditorClientState.rebuildDynamicTemplate();
            }));

            // Side Border Checkbox
            CheckBox borderBox = new CheckBox(getContext());
            borderBox.setText("加装路肩石边 (Side Border)");
            borderBox.setChecked(SplineEditorClientState.isDynamicHasBorder());
            borderBox.setTextColor(COLOR_TEXT_WHITE);
            borderBox.setOnCheckedChangeListener((btn, checked) -> {
                SplineEditorClientState.setDynamicHasBorder(checked);
                SplineEditorClientState.rebuildDynamicTemplate();
            });
            dynCard.addView(borderBox);

            contentContainer.addView(dynCard);
        } else {
            LinearLayout jsonCard = createCardView("JSON 蓝图模板选择");
            List<String> templateIds = SplineEditorClientState.getAvailableTemplateIds();
            if (templateIds.isEmpty()) {
                jsonCard.addView(createMutedLabel("暂无读取到的 JSON 模板文件。"));
            } else {
                for (String tplId : templateIds) {
                    boolean isCur = tplId.equals(SplineEditorClientState.getActiveTemplateId());
                    Button tplBtn = createStyledButton(tplId, isCur ? 0xFF4A3B69 : COLOR_CARD_BG, isCur ? COLOR_ACCENT_GOLD : COLOR_TEXT_WHITE, ViewGroup.LayoutParams.MATCH_PARENT, dp(26));
                    ((LinearLayout.LayoutParams) tplBtn.getLayoutParams()).bottomMargin = dp(2);
                    tplBtn.setOnClickListener(v -> {
                        SplineEditorClientState.setActiveTemplateId(tplId);
                        refreshAllUI();
                    });
                    jsonCard.addView(tplBtn);
                }
            }
            contentContainer.addView(jsonCard);
        }

        // Active template info
        RoadTemplate activeTmpl = SplineEditorClientState.getActiveTemplate();
        if (activeTmpl != null) {
            TextView tmplInfo = new TextView(getContext());
            tmplInfo.setText(String.format(Locale.ROOT, "当前模板: %s (单段含 %d 个方块)", activeTmpl.getId(), activeTmpl.getBlocks().size()));
            tmplInfo.setTextColor(COLOR_ACCENT_BLUE);
            tmplInfo.setTextSize(12);
            tmplInfo.setPadding(dp(4), dp(2), dp(4), dp(4));
            contentContainer.addView(tmplInfo);
        }

        // 3. Realtime 3D Preview & Pose Card
        LinearLayout previewCard = createCardView("实时 3D 预览与姿态微调");
        CheckBox previewBox = new CheckBox(getContext());
        previewBox.setText("开启阵列 3D 实时预览");
        previewBox.setChecked(SplineEditorClientState.isArrayPreview());
        previewBox.setTextColor(COLOR_TEXT_WHITE);
        previewBox.setOnCheckedChangeListener((btn, checked) -> {
            SplineEditorClientState.setArrayPreview(checked);
            refreshAllUI();
        });
        previewCard.addView(previewBox);

        if (SplineEditorClientState.isArrayPreview()) {
            previewCard.addView(createSliderRow("采样步距 (Step):", (int) (SplineEditorClientState.getArrayStepDistance() * 10), 1, 100, "格", val -> {
                SplineEditorClientState.setArrayStepDistance(val / 10.0);
            }));

            // Rotation Sliders: Roll, Pitch, Yaw
            previewCard.addView(createSliderRow("滚动角 (Roll):", (int) SplineEditorClientState.getArrayOffsetRoll(), -180, 180, "°", val -> {
                SplineEditorClientState.setArrayOffsetRoll(val);
            }));
            previewCard.addView(createSliderRow("俯仰角 (Pitch):", (int) SplineEditorClientState.getArrayOffsetPitch(), -180, 180, "°", val -> {
                SplineEditorClientState.setArrayOffsetPitch(val);
            }));
            previewCard.addView(createSliderRow("偏航角 (Yaw):", (int) SplineEditorClientState.getArrayOffsetYaw(), -180, 180, "°", val -> {
                SplineEditorClientState.setArrayOffsetYaw(val);
            }));

            Button resetRotBtn = createStyledButton("0° 重置姿态", COLOR_CARD_BG, COLOR_ACCENT_BLUE, ViewGroup.LayoutParams.MATCH_PARENT, dp(26));
            resetRotBtn.setOnClickListener(v -> {
                SplineEditorClientState.setArrayOffsetRoll(0);
                SplineEditorClientState.setArrayOffsetPitch(0);
                SplineEditorClientState.setArrayOffsetYaw(0);
                refreshAllUI();
            });
            previewCard.addView(resetRotBtn);

            // Build Array Action
            Button buildBtn = createActionButton("下发道路建造任务", 0xFF2D5A27, COLOR_ACCENT_GREEN);
            buildBtn.setOnClickListener(v -> {
                SplineEditorController.doBuildArray();
                Toast.makeText(getContext(), "已下发道路建造任务！NPC 将自动执行", Toast.LENGTH_SHORT).show();
            });
            previewCard.addView(buildBtn);
        }
        contentContainer.addView(previewCard);
    }

    // ── Spline Sub-Tab 3: 模板与工具 ──
    private void buildSplineTemplatesTab(SplineModel model) {
        Minecraft mc = Minecraft.getInstance();

        // 1. Template File Management Card
        LinearLayout tplCard = createCardView("模板文件管理");
        EditText tplNameInput = createTextInput("输入模板名称 (例如: main_road)", 0, dp(32), 1.0f);
        tplCard.addView(tplNameInput);

        LinearLayout btnRow = new LinearLayout(getContext());
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        btnRow.setPadding(0, dp(6), 0, 0);

        Button saveBtn = createStyledButton("保存 JSON 模板", COLOR_CARD_BG, COLOR_ACCENT_BLUE, 0, dp(30));
        saveBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(30), 1.0f));
        saveBtn.setOnClickListener(v -> {
            String name = tplNameInput.getText().toString().trim();
            if (!name.isEmpty()) {
                SplineEditorClientState.saveTemplate(name);
                Toast.makeText(getContext(), "已保存模板: " + name, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getContext(), "请输入模板名称！", Toast.LENGTH_SHORT).show();
            }
        });

        Button loadBtn = createStyledButton("读取 JSON 模板", COLOR_CARD_BG, COLOR_ACCENT_GOLD, 0, dp(30));
        LinearLayout.LayoutParams loadParams = new LinearLayout.LayoutParams(0, dp(30), 1.0f);
        loadParams.leftMargin = dp(6);
        loadBtn.setLayoutParams(loadParams);
        loadBtn.setOnClickListener(v -> {
            String name = tplNameInput.getText().toString().trim();
            if (!name.isEmpty()) {
                Vec3 pos = SplineEditorClientState.isEditing()
                        ? new Vec3(SplineEditorClientState.getCamX(), SplineEditorClientState.getCamY(), SplineEditorClientState.getCamZ())
                        : (mc.player != null ? mc.player.position() : Vec3.ZERO);
                SplineVec3 origin = new SplineVec3(pos.x, pos.y, pos.z);
                SplineEditorClientState.loadTemplate(name, origin);
                Toast.makeText(getContext(), "已载入模板: " + name, Toast.LENGTH_SHORT).show();
                refreshAllUI();
            } else {
                Toast.makeText(getContext(), "请输入模板名称！", Toast.LENGTH_SHORT).show();
            }
        });

        btnRow.addView(saveBtn);
        btnRow.addView(loadBtn);
        tplCard.addView(btnRow);
        contentContainer.addView(tplCard);

        // 2. View & Utilities Card
        LinearLayout utilCard = createCardView("视图与快捷工具");

        boolean topDown = SplineEditorClientState.isTopDown();
        String topDownLabel = topDown ? "退出 2D 俯瞰视角 (G)" : "切换 2D 俯瞰视角 (G)";
        Button topDownBtn = createStyledButton(topDownLabel, COLOR_CARD_BG, COLOR_ACCENT_BLUE, ViewGroup.LayoutParams.MATCH_PARENT, dp(32));
        topDownBtn.setOnClickListener(v -> {
            if (topDown) {
                SplineEditorClientState.exitTopDown();
            } else {
                SplineEditorClientState.enterTopDown();
            }
            refreshAllUI();
        });
        utilCard.addView(topDownBtn);

        Button guideBtn = createStyledButton("打开操作指南 (H)", COLOR_CARD_BG, COLOR_ACCENT_GOLD, ViewGroup.LayoutParams.MATCH_PARENT, dp(32));
        ((LinearLayout.LayoutParams) guideBtn.getLayoutParams()).topMargin = dp(4);
        guideBtn.setOnClickListener(v -> {
            String content = com.wsteam.wandscape.shared.ui.markdown.navigation.DocumentLoader.loadMarkdown("road_spline_guide");
            mc.setScreen(new com.wsteam.wandscape.shared.ui.guide.GuideTestScreen(null, content, "road_spline_guide"));
        });
        utilCard.addView(guideBtn);

        LinearLayout clearCloseRow = new LinearLayout(getContext());
        clearCloseRow.setOrientation(LinearLayout.HORIZONTAL);
        clearCloseRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        clearCloseRow.setPadding(0, dp(4), 0, 0);

        Button clearBtn = createStyledButton("清空画布", COLOR_CARD_BG, COLOR_TEXT_MUTED, 0, dp(30));
        clearBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(30), 1.0f));
        clearBtn.setOnClickListener(v -> {
            model.clear();
            SplineEditorClientState.setSelectedPoint(-1, SplineEditorClientState.SelectionType.NONE);
            Toast.makeText(getContext(), "已清空曲线画布", Toast.LENGTH_SHORT).show();
            refreshAllUI();
        });

        Button closeBtn = createStyledButton("关闭 Studio (ESC)", 0xFF6B2B2B, COLOR_ACCENT_RED, 0, dp(30));
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(0, dp(30), 1.0f);
        closeParams.leftMargin = dp(6);
        closeBtn.setLayoutParams(closeParams);
        closeBtn.setOnClickListener(v -> {
            SplineEditorClientState.exitEditMode();
            RoadStudioModernUI.close();
        });

        clearCloseRow.addView(clearBtn);
        clearCloseRow.addView(closeBtn);
        utilCard.addView(clearCloseRow);

        contentContainer.addView(utilCard);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helper UI Builders
    // ══════════════════════════════════════════════════════════════════════

    private LinearLayout createCardView(String title) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(8);
        card.setLayoutParams(params);

        ShapeDrawable cardBg = new ShapeDrawable();
        cardBg.setCornerRadius(dp(8));
        cardBg.setColor(COLOR_CARD_BG);
        cardBg.setStroke(dp(1), COLOR_CARD_BORDER);
        card.setBackground(cardBg);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));

        TextView titleText = new TextView(getContext());
        titleText.setText(title);
        titleText.setTextSize(13);
        titleText.setTextColor(COLOR_ACCENT_GOLD);
        titleText.setPadding(0, 0, 0, dp(6));
        card.addView(titleText);

        return card;
    }

    private void buildPresetSelectorIntoCard(LinearLayout card) {
        List<RoadPreset> presets = RoadPlacementState.getPresets();
        int currentIdx = RoadPlacementState.getSelectedPresetIndex();

        LinearLayout presetRow = new LinearLayout(getContext());
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < presets.size(); i++) {
            int idx = i;
            RoadPreset p = presets.get(i);
            boolean isCur = (idx == currentIdx);

            Button btn = createStyledButton(p.displayName(), isCur ? 0xFF4A3B69 : COLOR_CARD_BG, isCur ? COLOR_ACCENT_GOLD : COLOR_TEXT_WHITE, 0, dp(26));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(26), 1.0f);
            params.leftMargin = dp(1);
            params.rightMargin = dp(1);
            btn.setLayoutParams(params);
            btn.setOnClickListener(v -> {
                RoadPlacementState.setSelectedPresetIndex(idx);
                SplineEditorClientState.rebuildDynamicTemplate();
                refreshAllUI();
            });
            presetRow.addView(btn);
        }
        card.addView(presetRow);
    }

    private void buildPointControlIntoCard(LinearLayout card, String label, BlockPos pos, boolean isStart) {
        Minecraft mc = Minecraft.getInstance();
        card.addView(createMutedLabel(label + ":"));

        if (pos != null) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            EditText px = createNumberInput(String.valueOf(pos.getX()), 0, dp(28), 1.0f);
            EditText py = createNumberInput(String.valueOf(pos.getY()), 0, dp(28), 1.0f);
            EditText pz = createNumberInput(String.valueOf(pos.getZ()), 0, dp(28), 1.0f);
            py.setLayoutParams(new LinearLayout.LayoutParams(0, dp(28), 1.0f));
            ((LinearLayout.LayoutParams) py.getLayoutParams()).leftMargin = dp(4);
            pz.setLayoutParams(new LinearLayout.LayoutParams(0, dp(28), 1.0f));
            ((LinearLayout.LayoutParams) pz.getLayoutParams()).leftMargin = dp(4);

            TextWatcher watcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    if (suppressTextUpdates) return;
                    int x = parseIntSafe(px.getText().toString(), pos.getX());
                    int y = parseIntSafe(py.getText().toString(), pos.getY());
                    int z = parseIntSafe(pz.getText().toString(), pos.getZ());
                    BlockPos updated = new BlockPos(x, y, z);
                    if (isStart) RoadPlacementState.setStartPos(updated);
                    else RoadPlacementState.setEndPos(updated);
                }
            };
            px.addTextChangedListener(watcher);
            py.addTextChangedListener(watcher);
            pz.addTextChangedListener(watcher);

            Button clearBtn = createStyledButton("清除", COLOR_CARD_BG, COLOR_TEXT_MUTED, dp(50), dp(28));
            ((LinearLayout.LayoutParams) clearBtn.getLayoutParams()).leftMargin = dp(6);
            clearBtn.setOnClickListener(v -> {
                if (isStart) RoadPlacementState.clearStartPos();
                else RoadPlacementState.clearEndPos();
                refreshAllUI();
            });

            row.addView(px);
            row.addView(py);
            row.addView(pz);
            row.addView(clearBtn);
            card.addView(row);
        } else {
            Button captureBtn = createStyledButton("捕捉脚下位点", COLOR_CARD_BG, COLOR_ACCENT_BLUE, ViewGroup.LayoutParams.MATCH_PARENT, dp(26));
            captureBtn.setOnClickListener(v -> {
                BlockPos feet = getCapturedFeetPosition(mc);
                if (isStart) RoadPlacementState.setStartPos(feet);
                else RoadPlacementState.setEndPos(feet);
                refreshAllUI();
            });
            card.addView(captureBtn);
        }
    }

    private LinearLayout createSliderRow(String label, int currentVal, int min, int max, String unit, java.util.function.IntConsumer onChange) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setPadding(0, dp(2), 0, dp(4));

        LinearLayout topRow = new LinearLayout(getContext());
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView lbl = new TextView(getContext());
        lbl.setText(label);
        lbl.setTextSize(12);
        lbl.setTextColor(COLOR_TEXT_WHITE);
        lbl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView valLbl = new TextView(getContext());
        valLbl.setText(currentVal + " " + unit);
        valLbl.setTextSize(12);
        valLbl.setTextColor(COLOR_ACCENT_BLUE);

        topRow.addView(lbl);
        topRow.addView(valLbl);
        row.addView(topRow);

        SeekBar seekBar = new SeekBar(getContext());
        seekBar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));
        seekBar.setMax(max - min);
        seekBar.setProgress(currentVal - min);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int actual = min + progress;
                valLbl.setText(actual + " " + unit);
                if (fromUser && onChange != null) {
                    onChange.accept(actual);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        row.addView(seekBar);
        return row;
    }

    private Button createStyledButton(String text, int bgColor, int textColor, int width, int height) {
        Button btn = new Button(getContext());
        btn.setText(text);
        btn.setTextColor(textColor);
        btn.setTextSize(12);
        btn.setLayoutParams(new LinearLayout.LayoutParams(
                width > 0 ? width : ViewGroup.LayoutParams.WRAP_CONTENT,
                height > 0 ? height : ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        ShapeDrawable bg = new ShapeDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(bgColor);
        bg.setStroke(dp(1), COLOR_CARD_BORDER);
        btn.setBackground(bg);
        btn.setPadding(dp(6), 0, dp(6), 0);

        return btn;
    }

    private Button createActionButton(String text, int bgColor, int textColor) {
        Button btn = new Button(getContext());
        btn.setText(text);
        btn.setTextColor(textColor);
        btn.setTextSize(14);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(36)
        );
        params.topMargin = dp(4);
        btn.setLayoutParams(params);

        ShapeDrawable bg = new ShapeDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(bgColor);
        bg.setStroke(dp(1.5f), textColor);
        btn.setBackground(bg);

        return btn;
    }

    private EditText createNumberInput(String text, int width, int height, float weight) {
        EditText edit = new EditText(getContext());
        edit.setText(text);
        edit.setTextSize(12);
        edit.setTextColor(COLOR_TEXT_WHITE);
        edit.setSingleLine(true);
        edit.setGravity(Gravity.CENTER);
        edit.setLayoutParams(new LinearLayout.LayoutParams(
                width > 0 ? width : 0,
                height > 0 ? height : ViewGroup.LayoutParams.WRAP_CONTENT,
                weight
        ));

        ShapeDrawable bg = new ShapeDrawable();
        bg.setCornerRadius(dp(4));
        bg.setColor(0xFF181926);
        bg.setStroke(dp(1), COLOR_CARD_BORDER);
        edit.setBackground(bg);
        edit.setPadding(dp(4), dp(2), dp(4), dp(2));

        edit.setOnFocusChangeListener((v, hasFocus) -> {
            RoadStudioModernUI.setKeyboardFocused(hasFocus);
        });

        return edit;
    }

    private EditText createTextInput(String hint, int width, int height, float weight) {
        EditText edit = new EditText(getContext());
        edit.setHint(hint);
        edit.setTextSize(12);
        edit.setTextColor(COLOR_TEXT_WHITE);
        edit.setHintTextColor(COLOR_TEXT_DIM);
        edit.setSingleLine(true);
        edit.setLayoutParams(new LinearLayout.LayoutParams(
                width > 0 ? width : 0,
                height > 0 ? height : ViewGroup.LayoutParams.WRAP_CONTENT,
                weight
        ));

        ShapeDrawable bg = new ShapeDrawable();
        bg.setCornerRadius(dp(6));
        bg.setColor(0xFF181926);
        bg.setStroke(dp(1), COLOR_CARD_BORDER);
        edit.setBackground(bg);
        edit.setPadding(dp(8), dp(4), dp(8), dp(4));

        edit.setOnFocusChangeListener((v, hasFocus) -> {
            RoadStudioModernUI.setKeyboardFocused(hasFocus);
        });

        return edit;
    }

    private TextView createMutedLabel(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTextColor(COLOR_TEXT_MUTED);
        tv.setPadding(0, dp(2), 0, dp(2));
        return tv;
    }

    private TextView createValueLabel(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(COLOR_TEXT_WHITE);
        tv.setPadding(0, dp(2), 0, dp(2));
        return tv;
    }

    private BlockPos getCapturedFeetPosition(Minecraft mc) {
        if (SplineEditorClientState.isEditing()) {
            return BlockPos.containing(
                    SplineEditorClientState.getCamX(),
                    SplineEditorClientState.getCamY() - 1.0,
                    SplineEditorClientState.getCamZ()
            );
        }
        if (mc.player != null) {
            return mc.player.blockPosition().below();
        }
        return BlockPos.ZERO;
    }

    private double parseDoubleSafe(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private int dp(float value) {
        Context context = getContext();
        if (context == null) return (int) (value + 0.5f);
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
