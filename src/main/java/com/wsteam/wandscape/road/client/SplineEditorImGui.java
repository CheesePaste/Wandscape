package com.wsteam.wandscape.road.client;

import java.util.List;

import com.wsteam.wandscape.imgui.ImGuiManager;
import com.wsteam.wandscape.imgui.WandscapeImGuiTheme;
import com.wsteam.wandscape.road.data.RoadPreset;
import com.wsteam.wandscape.road.core.RoadTemplate;
import com.wsteam.wandscape.road.core.SplineModel;
import com.wsteam.wandscape.road.core.SplinePoint;
import com.wsteam.wandscape.road.core.SplineVec3;
import com.wsteam.wandscape.shared.log.Log;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImDouble;
import imgui.type.ImInt;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Ultimate Chinese-localized ImGui Studio interface for Spline Road Editor.
 * Features seamless integration with V-Panel Road Presets and dynamic width/depth generators.
 */
public final class SplineEditorImGui {
    private static final String TAG = "SplineEditorImGui";

    // FontAwesome Icons
    private static final String ICON_ROAD  = "\uF018";
    private static final String ICON_ADD   = "\uF067";
    private static final String ICON_EDIT  = "\uF140";
    private static final String ICON_POINT = "\uF111";
    private static final String ICON_CUBE  = "\uF1B2";
    private static final String ICON_SAVE  = "\uF0C7";
    private static final String ICON_LOAD  = "\uF07C";
    private static final String ICON_EYE   = "\uF06E";
    private static final String ICON_TRASH = "\uF1F8";
    private static final String ICON_LOCK  = "\uF023";
    private static final String ICON_CAM   = "\uF030";
    private static final String ICON_HELP  = "\uF059";
    private static final String ICON_LINK  = "\uF0C1";

    private static final ImString templateNameInput = new ImString(64);
    private static final ImDouble globalShiftX = new ImDouble(0.0);
    private static final ImDouble globalShiftY = new ImDouble(0.0);
    private static final ImDouble globalShiftZ = new ImDouble(0.0);

    // Array Generation UI binding
    private static final ImBoolean uiArrayPreview = new ImBoolean(false);
    private static final ImDouble uiStepDistance = new ImDouble(2.0);
    private static final float[] uiOffsetRoll = new float[]{0.0f};
    private static final float[] uiOffsetPitch = new float[]{0.0f};
    private static final float[] uiOffsetYaw = new float[]{0.0f};

    // Dynamic Template Generator UI binding
    private static final int[] uiDynamicWidth = new int[]{5};
    private static final int[] uiDynamicDepth = new int[]{1};
    private static final ImBoolean uiDynamicBorder = new ImBoolean(false);

    private SplineEditorImGui() {}

    public static void render() {
        Minecraft mc = Minecraft.getInstance();
        var io = ImGui.getIO();

        float width = 370.0f;
        ImGui.setNextWindowPos(io.getDisplaySizeX() - width, 0, ImGuiCond.Always);
        ImGui.setNextWindowSize(width, io.getDisplaySizeY(), ImGuiCond.Always);

        int flags = ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoTitleBar;

        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.WindowPadding, 12.0f, 14.0f);

        if (ImGui.begin("道路制作工坊", flags)) {
            SplineModel model = SplineEditorClientState.getModel();

            // ── Banner Header ──
            drawHeaderBanner(model);

            // ── Main TabBar ──
            if (ImGui.beginTabBar("SplineStudioTabBar", imgui.flag.ImGuiTabBarFlags.None)) {
                
                // ── TAB 1: 曲线编辑 ──
                if (ImGui.beginTabItem(ICON_ROAD + " 曲线编辑")) {
                    drawCurveNodesTab(model, mc);
                    ImGui.endTabItem();
                }

                // ── TAB 2: 阵列生成 ──
                if (ImGui.beginTabItem(ICON_CUBE + " 阵列生成")) {
                    drawArrayStudioTab();
                    ImGui.endTabItem();
                }

                // ── TAB 3: 模板与工具 ──
                if (ImGui.beginTabItem(ICON_SAVE + " 模板与工具")) {
                    drawTemplatesTab(model, mc);
                    ImGui.endTabItem();
                }

                ImGui.endTabBar();
            }

            // ── Bottom Action Footer ──
            drawBottomFooter();
        }
        ImGui.end();
        ImGui.popStyleVar();
    }

    // ── Header Banner ──
    private static void drawHeaderBanner(SplineModel model) {
        ImGui.pushStyleColor(ImGuiCol.ChildBg, 0.15f, 0.11f, 0.22f, 0.85f);
        ImGui.pushStyleColor(ImGuiCol.Border, 0.78f, 0.63f, 0.25f, 0.50f);
        ImGui.beginChild("HeaderBanner", 0, 56, true);
        {
            ImGui.textColored(0.95f, 0.78f, 0.30f, 1.00f, ICON_ROAD + " WANDSCAPE 道路制作工坊");
            ImGui.sameLine();
            WandscapeImGuiTheme.textMuted("v2.0");

            String modeStr = SplineEditorClientState.getEditMode() == SplineEditorClientState.EditMode.ADD ? "添加节点" : "编辑节点";
            String topDownStr = SplineEditorClientState.isTopDown() ? "2D 俯瞰" : "3D 自由";
            ImGui.textColored(0.40f, 0.75f, 0.95f, 1.00f, String.format("控制点: %d  |  模式: %s  |  视角: %s", model.getPoints().size(), modeStr, topDownStr));
        }
        ImGui.endChild();
        ImGui.popStyleColor(2);
        ImGui.spacing();
    }

    // ── Tab 1: 曲线编辑 ──
    private static void drawCurveNodesTab(SplineModel model, Minecraft mc) {
        ImGui.spacing();
        
        // 模式选择器
        WandscapeImGuiTheme.drawSectionHeader(ICON_EDIT, "编辑模式切换");
        
        boolean isAdd = SplineEditorClientState.getEditMode() == SplineEditorClientState.EditMode.ADD;
        float halfW = (ImGui.getContentRegionAvailX() - 8.0f) / 2.0f;

        if (isAdd) {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.35f, 0.28f, 0.48f, 1.00f);
            ImGui.pushStyleColor(ImGuiCol.Border, 0.95f, 0.78f, 0.30f, 0.90f);
        } else {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.16f, 0.14f, 0.20f, 0.85f);
            ImGui.pushStyleColor(ImGuiCol.Border, 0.40f, 0.35f, 0.25f, 0.40f);
        }
        if (ImGui.button(ICON_ADD + " 点击添加点", halfW, 32)) {
            SplineEditorClientState.setEditMode(SplineEditorClientState.EditMode.ADD);
        }
        WandscapeImGuiTheme.drawTooltip("【添加模式】在游戏世界中左键点击方块表面，可顺序放置新的道路控制点。");
        ImGui.popStyleColor(2);

        ImGui.sameLine();

        if (!isAdd) {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.35f, 0.28f, 0.48f, 1.00f);
            ImGui.pushStyleColor(ImGuiCol.Border, 0.95f, 0.78f, 0.30f, 0.90f);
        } else {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.16f, 0.14f, 0.20f, 0.85f);
            ImGui.pushStyleColor(ImGuiCol.Border, 0.40f, 0.35f, 0.25f, 0.40f);
        }
        if (ImGui.button(ICON_EDIT + " 选择与拖拽", halfW, 32)) {
            SplineEditorClientState.setEditMode(SplineEditorClientState.EditMode.EDIT);
        }
        WandscapeImGuiTheme.drawTooltip("【选择编辑模式】在世界中或下方列表中点击控制点，通过 3D 坐标轴手柄拖动调控曲线轨迹。");
        ImGui.popStyleColor(2);

        ImGui.spacing();
        ImGui.spacing();

        // 曲线几何与全局平移
        WandscapeImGuiTheme.drawSectionHeader(ICON_ROAD, "曲线几何与平移");
        
        ImBoolean closed = new ImBoolean(model.isClosed());
        if (ImGui.checkbox("闭合环形道路 (连接首尾)", closed)) {
            model.setClosed(closed.get());
        }
        WandscapeImGuiTheme.drawTooltip("勾选后自动连接道路首尾点，构建无缝闭合的环形道路。");

        ImGui.spacing();
        WandscapeImGuiTheme.textMuted("整体平移偏移量 (X, Y, Z):");
        
        ImGui.pushItemWidth(55);
        ImGui.inputDouble("##ShiftX", globalShiftX, 0.0, 0.0, "%.1f");
        WandscapeImGuiTheme.drawTooltip("X 轴平移增量 (方块)");
        ImGui.sameLine();
        ImGui.inputDouble("##ShiftY", globalShiftY, 0.0, 0.0, "%.1f");
        WandscapeImGuiTheme.drawTooltip("Y 轴高程增量 (方块)");
        ImGui.sameLine();
        ImGui.inputDouble("##ShiftZ", globalShiftZ, 0.0, 0.0, "%.1f");
        WandscapeImGuiTheme.drawTooltip("Z 轴平移增量 (方块)");
        ImGui.popItemWidth();

        ImGui.sameLine();
        if (ImGui.button("整体平移", 60, 24)) {
            SplineVec3 delta = new SplineVec3(globalShiftX.get(), globalShiftY.get(), globalShiftZ.get());
            model.translateAll(delta);
            globalShiftX.set(0.0);
            globalShiftY.set(0.0);
            globalShiftZ.set(0.0);
            Log.info(TAG, "Translated all points by {}", delta);
        }
        WandscapeImGuiTheme.drawTooltip("将整条曲线上的所有控制点按照输入的 (X, Y, Z) 偏移量一并平移。");

        ImGui.spacing();
        ImGui.spacing();

        // 控制点列表
        WandscapeImGuiTheme.drawSectionHeader(ICON_POINT, "控制点列表 (" + model.getPoints().size() + ")");
        
        if (ImGui.beginChild("PointsListChild", 0, 130, true)) {
            if (model.getPoints().isEmpty()) {
                ImGui.spacing();
                ImGui.textDisabled("  当前无控制点。");
                ImGui.textDisabled("  请在世界中左键点击方块添加。");
            } else {
                for (int i = 0; i < model.getPoints().size(); i++) {
                    SplinePoint pt = model.getPoints().get(i);
                    SplineVec3 anchor = pt.getAnchor();
                    String symTag = pt.isLocked() ? "[对称]" : "[自由]";
                    String label = String.format("#%d  (%.1f, %.1f, %.1f) %s", i, anchor.x(), anchor.y(), anchor.z(), symTag);
                    
                    boolean isSelected = SplineEditorClientState.getSelectedPointIndex() == i;
                    if (ImGui.selectable(label, isSelected)) {
                        SplineEditorClientState.setSelectedPoint(i, SplineEditorClientState.SelectionType.ANCHOR);
                        SplineEditorClientState.setEditMode(SplineEditorClientState.EditMode.EDIT);
                    }
                    if (isSelected) {
                        WandscapeImGuiTheme.drawTooltip("当前选中的控制点 #" + i + "。可在下方检查器中精准修改。");
                    }
                }
            }
        }
        ImGui.endChild();

        // 控制点属性检查器
        int selectedIdx = SplineEditorClientState.getSelectedPointIndex();
        SplineEditorClientState.SelectionType selectedType = SplineEditorClientState.getSelectedType();

        if (selectedIdx >= 0 && selectedIdx < model.getPoints().size()) {
            SplinePoint pt = model.getPoints().get(selectedIdx);
            
            ImGui.spacing();
            WandscapeImGuiTheme.drawSectionHeader(ICON_CAM, "节点属性检查器 #" + selectedIdx);

            WandscapeImGuiTheme.textMuted("调整句柄目标:");
            if (ImGui.radioButton("主锚点", selectedType == SplineEditorClientState.SelectionType.ANCHOR)) {
                SplineEditorClientState.setSelectedPoint(selectedIdx, SplineEditorClientState.SelectionType.ANCHOR);
            }
            WandscapeImGuiTheme.drawTooltip("移动控制点在世界中的主中心位置");
            ImGui.sameLine();
            if (ImGui.radioButton("前手柄", selectedType == SplineEditorClientState.SelectionType.CONTROL_PREV)) {
                SplineEditorClientState.setSelectedPoint(selectedIdx, SplineEditorClientState.SelectionType.CONTROL_PREV);
            }
            WandscapeImGuiTheme.drawTooltip("调整入向 Bezier 切线控制点");
            ImGui.sameLine();
            if (ImGui.radioButton("后手柄", selectedType == SplineEditorClientState.SelectionType.CONTROL_NEXT)) {
                SplineEditorClientState.setSelectedPoint(selectedIdx, SplineEditorClientState.SelectionType.CONTROL_NEXT);
            }
            WandscapeImGuiTheme.drawTooltip("调整出向 Bezier 切线控制点");

            SplineVec3 targetPos = switch (selectedType) {
                case ANCHOR -> pt.getAnchor();
                case CONTROL_PREV -> pt.getControlPrev();
                case CONTROL_NEXT -> pt.getControlNext();
                default -> null;
            };

            if (targetPos != null) {
                ImDouble px = new ImDouble(targetPos.x());
                ImDouble py = new ImDouble(targetPos.y());
                ImDouble pz = new ImDouble(targetPos.z());

                ImGui.text("三维精确坐标:");
                ImGui.pushItemWidth(75);
                boolean mx = ImGui.inputDouble("X##Coord", px, 0.1, 1.0, "%.2f");
                ImGui.sameLine();
                boolean my = ImGui.inputDouble("Y##Coord", py, 0.1, 1.0, "%.2f");
                ImGui.sameLine();
                boolean mz = ImGui.inputDouble("Z##Coord", pz, 0.1, 1.0, "%.2f");
                ImGui.popItemWidth();

                if (mx || my || mz) {
                    SplineVec3 updated = new SplineVec3(px.get(), py.get(), pz.get());
                    switch (selectedType) {
                        case ANCHOR -> pt.setAnchor(updated);
                        case CONTROL_PREV -> pt.setControlPrev(updated);
                        case CONTROL_NEXT -> pt.setControlNext(updated);
                    }
                }
            }

            ImBoolean locked = new ImBoolean(pt.isLocked());
            if (ImGui.checkbox("对称切线手柄锁定", locked)) {
                pt.setLocked(locked.get());
            }
            WandscapeImGuiTheme.drawTooltip("保持前后切线控制手柄对称镜像联动，确保平滑过渡。");

            ImGui.sameLine();
            if (ImGui.button(ICON_CAM + " 视角聚焦")) {
                SplineVec3 pos = pt.getAnchor();
                SplineEditorClientState.setCamPosition(pos.x(), pos.y() + 3.0, pos.z() + 5.0);
            }
            WandscapeImGuiTheme.drawTooltip("将自由视角摄像机瞬间移至选中的控制点附近。");

            ImGui.spacing();
            ImGui.pushStyleColor(ImGuiCol.Button, 0.55f, 0.15f, 0.15f, 0.85f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.75f, 0.20f, 0.20f, 0.95f);
            if (ImGui.button(ICON_TRASH + " 删除节点 #" + selectedIdx, -1, 26)) {
                model.removePoint(selectedIdx);
                SplineEditorClientState.setSelectedPoint(-1, SplineEditorClientState.SelectionType.NONE);
            }
            WandscapeImGuiTheme.drawTooltip("移除当前选中的控制节点 (快捷键 Delete / Backspace)。");
            ImGui.popStyleColor(2);
        }
    }

    // ── Tab 2: 阵列生成 ──
    private static void drawArrayStudioTab() {
        ImGui.spacing();
        
        // 1. 模板来源选择
        WandscapeImGuiTheme.drawSectionHeader(ICON_LINK, "模板来源与 V 面板联动");

        var sourceMode = SplineEditorClientState.getTemplateSourceMode();
        if (ImGui.radioButton("V 面板方块预设 (推荐)", sourceMode == SplineEditorClientState.TemplateSourceMode.VPANEL_PRESET)) {
            SplineEditorClientState.setTemplateSourceMode(SplineEditorClientState.TemplateSourceMode.VPANEL_PRESET);
        }
        WandscapeImGuiTheme.drawTooltip("与概览 (V) 面板选中的方块预设直接联动，并允许实时动态调节宽度与厚度。");

        ImGui.sameLine();

        if (ImGui.radioButton("JSON 预设文件", sourceMode == SplineEditorClientState.TemplateSourceMode.JSON_FILE)) {
            SplineEditorClientState.setTemplateSourceMode(SplineEditorClientState.TemplateSourceMode.JSON_FILE);
        }
        WandscapeImGuiTheme.drawTooltip("使用硬盘中的 JSON 结构模板做阵列排布。");

        ImGui.spacing();

        // 2. 根据来源模式显示配置面板
        if (SplineEditorClientState.getTemplateSourceMode() == SplineEditorClientState.TemplateSourceMode.VPANEL_PRESET) {
            // V 面板选择与动态生成器
            List<RoadPreset> presets = RoadPlacementState.getPresets();
            int currentIdx = RoadPlacementState.getSelectedPresetIndex();
            String[] presetNames = presets.stream().map(RoadPreset::displayName).toArray(String[]::new);

            ImInt selectedPresetInt = new ImInt(currentIdx);
            ImGui.text("V 面板选中的方块:");
            ImGui.pushItemWidth(-1);
            if (ImGui.combo("##VPanelPresetCombo", selectedPresetInt, presetNames)) {
                RoadPlacementState.setSelectedPresetIndex(selectedPresetInt.get());
                SplineEditorClientState.rebuildDynamicTemplate();
            }
            ImGui.popItemWidth();
            WandscapeImGuiTheme.drawTooltip("在 ImGui 里选择道路方块会实时同步更新 V 面板选中的预设！");

            ImGui.spacing();
            WandscapeImGuiTheme.textMuted("动态道路规格生成器:");

            uiDynamicWidth[0] = SplineEditorClientState.getDynamicWidth();
            uiDynamicDepth[0] = SplineEditorClientState.getDynamicDepth();
            uiDynamicBorder.set(SplineEditorClientState.isDynamicHasBorder());

            boolean dynamicChanged = false;
            ImGui.pushItemWidth(180);
            if (ImGui.sliderInt("道路宽度 (Width)", uiDynamicWidth, 1, 15, "%d 格方块")) {
                SplineEditorClientState.setDynamicWidth(uiDynamicWidth[0]);
                dynamicChanged = true;
            }
            WandscapeImGuiTheme.drawTooltip("调节生成道路横向铺设的方块宽度 (1 ~ 15 格)");

            if (ImGui.sliderInt("基层厚度 (Depth)", uiDynamicDepth, 1, 3, "%d 层深")) {
                SplineEditorClientState.setDynamicDepth(uiDynamicDepth[0]);
                dynamicChanged = true;
            }
            WandscapeImGuiTheme.drawTooltip("调节道路垂直向下铺设的基础层数 (1 ~ 3 层)");
            ImGui.popItemWidth();

            if (ImGui.checkbox("加装路肩石边 (Side Border)", uiDynamicBorder)) {
                SplineEditorClientState.setDynamicHasBorder(uiDynamicBorder.get());
                dynamicChanged = true;
            }
            WandscapeImGuiTheme.drawTooltip("勾选后，道路左右两侧最边缘自动铺设石砖护栏包裹。");

            if (dynamicChanged) {
                SplineEditorClientState.rebuildDynamicTemplate();
            }

        } else {
            // JSON 文件模式
            List<String> templateIds = SplineEditorClientState.getAvailableTemplateIds();
            if (!templateIds.isEmpty()) {
                String currentId = SplineEditorClientState.getActiveTemplateId();
                int idx = Math.max(0, templateIds.indexOf(currentId));
                
                ImInt activeTemplateIdx = new ImInt(idx);
                String[] templateArray = templateIds.toArray(new String[0]);
                ImGui.text("选择 JSON 模板:");
                ImGui.pushItemWidth(-1);
                if (ImGui.combo("##BlueprintCombo", activeTemplateIdx, templateArray)) {
                    SplineEditorClientState.setActiveTemplateId(templateArray[activeTemplateIdx.get()]);
                }
                ImGui.popItemWidth();
                WandscapeImGuiTheme.drawTooltip("选择读取到的 JSON 结构文件进行排布。");
            } else {
                ImGui.textDisabled("暂无可用的 JSON 蓝图模板。");
            }
        }

        RoadTemplate activeTmpl = SplineEditorClientState.getActiveTemplate();
        if (activeTmpl != null) {
            WandscapeImGuiTheme.textMuted("当前使用模板: " + activeTmpl.getId() + " (单段含 " + activeTmpl.getBlocks().size() + " 个方块)");
        }

        ImGui.spacing();
        ImGui.spacing();

        // 3. 实时 3D 预览与采样
        WandscapeImGuiTheme.drawSectionHeader(ICON_EYE, "实时 3D 预览与调整");

        uiArrayPreview.set(SplineEditorClientState.isArrayPreview());
        if (ImGui.checkbox("开启阵列 3D 实时预览", uiArrayPreview)) {
            SplineEditorClientState.setArrayPreview(uiArrayPreview.get());
        }
        WandscapeImGuiTheme.drawTooltip("在游戏世界中以 3D 包围盒的形式实时渲染沿曲线排列的方块位姿。");

        if (SplineEditorClientState.isArrayPreview()) {
            ImGui.spacing();
            uiStepDistance.set(SplineEditorClientState.getArrayStepDistance());
            ImGui.pushItemWidth(140);
            if (ImGui.inputDouble("采样步距 (格)", uiStepDistance, 0.5, 1.0, "%.2f")) {
                SplineEditorClientState.setArrayStepDistance(Math.max(0.1, uiStepDistance.get()));
            }
            ImGui.popItemWidth();
            WandscapeImGuiTheme.drawTooltip("沿 Bezier 曲线采样的间距（单位：方块）。越小生成越平滑密实。");

            ImGui.spacing();
            WandscapeImGuiTheme.textMuted("3D 阵列姿态旋转微调:");

            uiOffsetRoll[0] = (float) SplineEditorClientState.getArrayOffsetRoll();
            uiOffsetPitch[0] = (float) SplineEditorClientState.getArrayOffsetPitch();
            uiOffsetYaw[0] = (float) SplineEditorClientState.getArrayOffsetYaw();

            boolean rotChanged = false;
            ImGui.pushItemWidth(180);
            rotChanged |= ImGui.sliderFloat("滚转角 Roll", uiOffsetRoll, -180.0f, 180.0f, "%.1f 度");
            rotChanged |= ImGui.sliderFloat("俯仰角 Pitch", uiOffsetPitch, -180.0f, 180.0f, "%.1f 度");
            rotChanged |= ImGui.sliderFloat("偏航角 Yaw", uiOffsetYaw, -180.0f, 180.0f, "%.1f 度");
            ImGui.popItemWidth();

            ImGui.sameLine();
            if (ImGui.button("0° 重置")) {
                uiOffsetRoll[0] = 0;
                uiOffsetPitch[0] = 0;
                uiOffsetYaw[0] = 0;
                rotChanged = true;
            }
            WandscapeImGuiTheme.drawTooltip("快捷重置所有旋转角为 0°");

            if (rotChanged) {
                SplineEditorClientState.setArrayOffsetRoll(uiOffsetRoll[0]);
                SplineEditorClientState.setArrayOffsetPitch(uiOffsetPitch[0]);
                SplineEditorClientState.setArrayOffsetYaw(uiOffsetYaw[0]);
            }
            
            ImGui.spacing();
            ImGui.spacing();

            ImGui.pushStyleColor(ImGuiCol.Button, 0.22f, 0.45f, 0.25f, 0.90f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.30f, 0.60f, 0.32f, 1.00f);
            ImGui.pushStyleColor(ImGuiCol.ButtonActive, 0.38f, 0.72f, 0.40f, 1.00f);
            if (ImGui.button(ICON_CUBE + " 下发道路建造任务", -1, 36)) {
                SplineEditorController.doBuildArray();
            }
            WandscapeImGuiTheme.drawTooltip("将打包好的道路方块发送给服务端，由殖民地建筑法师 NPC 自动建造！");
            ImGui.popStyleColor(3);
        }
    }

    // ── Tab 3: 模板与工具 ──
    private static void drawTemplatesTab(SplineModel model, Minecraft mc) {
        ImGui.spacing();
        WandscapeImGuiTheme.drawSectionHeader(ICON_SAVE, "模板文件管理");

        ImGui.pushItemWidth(-1);
        ImGui.inputTextWithHint("##TemplateName", "输入模板名称 (例如: main_road)", templateNameInput);
        ImGui.popItemWidth();
        ImGui.spacing();

        float halfW = (ImGui.getContentRegionAvailX() - 8.0f) / 2.0f;
        
        ImGui.pushStyleColor(ImGuiCol.Button, 0.15f, 0.32f, 0.50f, 0.90f);
        if (ImGui.button(ICON_SAVE + " 保存 JSON 模板", halfW, 28)) {
            String name = templateNameInput.get().trim();
            if (!name.isEmpty()) {
                SplineEditorClientState.saveTemplate(name);
            }
        }
        WandscapeImGuiTheme.drawTooltip("将当前绘制好的曲线节点导出保存至 config/wandscape/splines/");
        ImGui.popStyleColor();

        ImGui.sameLine();

        ImGui.pushStyleColor(ImGuiCol.Button, 0.20f, 0.28f, 0.42f, 0.90f);
        if (ImGui.button(ICON_LOAD + " 读取 JSON 模板", halfW, 28)) {
            String name = templateNameInput.get().trim();
            if (!name.isEmpty() && mc.player != null) {
                Vec3 pos = mc.player.position();
                SplineVec3 placementOrigin = new SplineVec3(pos.x, pos.y, pos.z);
                SplineEditorClientState.loadTemplate(name, placementOrigin);
            }
        }
        WandscapeImGuiTheme.drawTooltip("从 config/wandscape/splines/ 中读取曲线模板并放置在当前玩家位置。");
        ImGui.popStyleColor();

        ImGui.spacing();
        ImGui.spacing();
        WandscapeImGuiTheme.drawSectionHeader(ICON_EYE, "视图与快捷工具");

        boolean topDown = SplineEditorClientState.isTopDown();
        String btnText = topDown ? ICON_EYE + " 退出 2D 俯瞰视角 (G)" : ICON_EYE + " 切换 2D 俯瞰视角 (G)";
        
        if (ImGui.button(btnText, -1, 30)) {
            if (topDown) {
                SplineEditorClientState.exitTopDown();
            } else {
                SplineEditorClientState.enterTopDown();
            }
        }
        WandscapeImGuiTheme.drawTooltip("在 2D 鸟瞰视角与 3D 自由视角间切换 (快捷键 G)");

        ImGui.spacing();

        if (ImGui.button(ICON_HELP + " 打开操作指南 (H)", -1, 30)) {
            String content = com.wsteam.wandscape.shared.ui.markdown.navigation.DocumentLoader.loadMarkdown("road_spline_guide");
            mc.setScreen(new com.wsteam.wandscape.shared.ui.guide.GuideTestScreen(null, content, "road_spline_guide"));
        }
        WandscapeImGuiTheme.drawTooltip("打开道路制作与 Spline 曲线操作说明文档 (快捷键 H)");

        ImGui.spacing();

        if (ImGui.button("清空画布", halfW, 28)) {
            model.clear();
            SplineEditorClientState.setSelectedPoint(-1, SplineEditorClientState.SelectionType.NONE);
        }
        WandscapeImGuiTheme.drawTooltip("清空当前画布上的所有控制节点。");

        ImGui.sameLine();

        ImGui.pushStyleColor(ImGuiCol.Button, 0.55f, 0.15f, 0.15f, 0.85f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.75f, 0.20f, 0.20f, 0.95f);
        if (ImGui.button("关闭 Studio", halfW, 28)) {
            SplineEditorClientState.exitEditMode();
            ImGuiManager.toggle();
        }
        WandscapeImGuiTheme.drawTooltip("退出道路制作工坊 (快捷键 ESC)。");
        ImGui.popStyleColor(2);
    }

    // ── Bottom Action Footer ──
    private static void drawBottomFooter() {
        ImGui.setCursorPosY(ImGui.getWindowHeight() - 26);
        ImGui.separator();
        WandscapeImGuiTheme.textMuted(" [右键按住] 旋转视角 | [G] 俯瞰 | [H] 指南 | [ESC] 退出");
    }
}
