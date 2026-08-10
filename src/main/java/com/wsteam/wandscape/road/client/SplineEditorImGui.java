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
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Clean UTF-8 Chinese Localized ImGui Studio Interface for Road Placement & Spline Editor.
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

            // ── Mode Switcher Bar ──
            drawToolModeSelector();

            RoadPlacementState.ToolMode currentTool = RoadPlacementState.getActiveTool();

            switch (currentTool) {
                case REPLACE -> drawReplaceModeTab(mc);
                case FILL -> drawFillModeTab(mc);
                case DESTROY_FILL -> drawDestroyFillModeTab(mc);
                case SPLINE -> {
                    // ── Main TabBar for Spline Mode ──
                    if (ImGui.beginTabBar("SplineStudioTabBar", imgui.flag.ImGuiTabBarFlags.None)) {
                        if (ImGui.beginTabItem(ICON_ROAD + " 曲线编辑")) {
                            drawCurveNodesTab(model, mc);
                            ImGui.endTabItem();
                        }
                        if (ImGui.beginTabItem(ICON_CUBE + " 阵列生成")) {
                            drawArrayStudioTab();
                            ImGui.endTabItem();
                        }
                        if (ImGui.beginTabItem(ICON_SAVE + " 模板与工具")) {
                            drawTemplatesTab(model, mc);
                            ImGui.endTabItem();
                        }
                        ImGui.endTabBar();
                    }
                }
            }

            // ── Bottom Action Footer ──
            drawBottomFooter();
        }
        ImGui.end();
        ImGui.popStyleVar();
    }

    // ── Tool Mode Switcher ──
    private static void drawToolModeSelector() {
        WandscapeImGuiTheme.drawSectionHeader(ICON_EDIT, "模式选择");
        RoadPlacementState.ToolMode currentTool = RoadPlacementState.getActiveTool();

        float availW = ImGui.getContentRegionAvailX();
        float btnW = (availW - 9.0f) / 4.0f;

        drawModeButton("替换", RoadPlacementState.ToolMode.REPLACE, currentTool, btnW);
        ImGui.sameLine();
        drawModeButton("填充", RoadPlacementState.ToolMode.FILL, currentTool, btnW);
        ImGui.sameLine();
        drawModeButton("铲平", RoadPlacementState.ToolMode.DESTROY_FILL, currentTool, btnW);
        ImGui.sameLine();
        drawModeButton("样条", RoadPlacementState.ToolMode.SPLINE, currentTool, btnW);

        ImGui.spacing();
    }

    private static void drawModeButton(String label, RoadPlacementState.ToolMode mode, RoadPlacementState.ToolMode activeTool, float width) {
        boolean selected = (mode == activeTool);
        if (selected) {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.35f, 0.28f, 0.48f, 1.00f);
            ImGui.pushStyleColor(ImGuiCol.Border, 0.95f, 0.78f, 0.30f, 0.90f);
        } else {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.16f, 0.14f, 0.20f, 0.85f);
            ImGui.pushStyleColor(ImGuiCol.Border, 0.40f, 0.35f, 0.25f, 0.40f);
        }

        if (ImGui.button(label, width, 26)) {
            RoadPlacementState.setActiveTool(mode);
        }
        ImGui.popStyleColor(2);
    }

    // ── Mode 1: 直线替换 (REPLACE) ──
    private static void drawReplaceModeTab(Minecraft mc) {
        ImGui.spacing();
        WandscapeImGuiTheme.drawSectionHeader(ICON_ROAD, "铺设方块预设");
        drawPresetSelectorCombo();

        ImGui.spacing();
        WandscapeImGuiTheme.drawSectionHeader(ICON_POINT, "铺设路线起终点");
        drawStartPosLabelControls(mc, "起点坐标 (Start)");
        ImGui.spacing();
        drawEndPosLabelControls(mc, "终点坐标 (End)");

        ImGui.spacing();
        BlockPos start = RoadPlacementState.getStartPos();
        BlockPos end = RoadPlacementState.getEndPos();
        if (start != null && end != null) {
            int dx = Math.abs(end.getX() - start.getX()) + 1;
            int dz = Math.abs(end.getZ() - start.getZ()) + 1;
            double dist = Math.sqrt((double)(end.getX() - start.getX()) * (end.getX() - start.getX()) + (double)(end.getZ() - start.getZ()) * (end.getZ() - start.getZ()));
            WandscapeImGuiTheme.drawSectionHeader(ICON_CUBE, "铺设数据评估");
            ImGui.text(String.format("覆盖跨度: %d × %d 方块范围", dx, dz));
            ImGui.text(String.format("直线距离: %.1f 方块", dist));
        } else {
            WandscapeImGuiTheme.textMuted("提示: 请选择起点与终点（可在世界中右键/左键点击）");
        }

        ImGui.spacing();
        ImGui.spacing();

        ImGui.pushStyleColor(ImGuiCol.Button, 0.22f, 0.45f, 0.25f, 0.90f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.30f, 0.60f, 0.32f, 1.00f);
        if (ImGui.button(ICON_CUBE + " 下发直线铺设任务", -1, 36)) {
            if (RoadPlacementState.isReady()) {
                String presetId = RoadPlacementState.getSelectedPreset().id();
                PacketDistributor.sendToServer(new com.wsteam.wandscape.road.network.RoadPlacePacket(presetId, start, end));
                Log.info(TAG, "[RoadReplace] Published road place: preset={} start={} end={}", presetId, start, end);
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal("[Road] §aRoad task submitted! NPC will pave the path."), true);
                }
                RoadPlacementState.clearAll();
            }
        }
        WandscapeImGuiTheme.drawTooltip("下发直线地表道路替换任务给法师 NPC");
        ImGui.popStyleColor(2);
    }

    // ── Mode 2: 立方体填充 (FILL) ──
    private static void drawFillModeTab(Minecraft mc) {
        ImGui.spacing();
        WandscapeImGuiTheme.drawSectionHeader(ICON_ROAD, "填充方块预设");
        drawPresetSelectorCombo();

        ImGui.spacing();
        WandscapeImGuiTheme.drawSectionHeader(ICON_POINT, "3D 立方体对角点");
        drawStartPosLabelControls(mc, "角点 1 (Start)");
        ImGui.spacing();
        drawEndPosLabelControls(mc, "角点 2 (End)");

        ImGui.spacing();
        BlockPos start = RoadPlacementState.getStartPos();
        BlockPos end = RoadPlacementState.getEndPos();
        if (start != null && end != null) {
            int dx = Math.abs(end.getX() - start.getX()) + 1;
            int dy = Math.abs(end.getY() - start.getY()) + 1;
            int dz = Math.abs(end.getZ() - start.getZ()) + 1;
            long volume = (long) dx * dy * dz;
            WandscapeImGuiTheme.drawSectionHeader(ICON_CUBE, "立方体体积评估");
            ImGui.text(String.format("尺寸: %d (宽) × %d (高) × %d (深)", dx, dy, dz));
            ImGui.text(String.format("总体积: %d 个方块", volume));
        } else {
            WandscapeImGuiTheme.textMuted("提示: 请选择两个对角点（可在世界中右键/左键点击）");
        }

        ImGui.spacing();
        ImGui.spacing();

        ImGui.pushStyleColor(ImGuiCol.Button, 0.22f, 0.45f, 0.25f, 0.90f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.30f, 0.60f, 0.32f, 1.00f);
        if (ImGui.button(ICON_CUBE + " 下发立方体填充任务", -1, 36)) {
            if (RoadPlacementState.isReady()) {
                String presetId = RoadPlacementState.getSelectedPreset().id();
                PacketDistributor.sendToServer(new com.wsteam.wandscape.road.network.FillBoxPacket(presetId, start, end));
                Log.info(TAG, "[FillBox] Published fill box: preset={} start={} end={}", presetId, start, end);
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal("[Fill] §aFill task submitted! NPC will fill the cube."), true);
                }
                RoadPlacementState.clearAll();
            }
        }
        WandscapeImGuiTheme.drawTooltip("下发 3D 立方体填充任务给法师 NPC");
        ImGui.popStyleColor(2);
    }

    private static BlockPos getCapturedFeetPosition(Minecraft mc) {
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

    // ── Mode 3: 铲平垫平 (DESTROY_FILL) ──
    private static void drawDestroyFillModeTab(Minecraft mc) {
        ImGui.spacing();
        WandscapeImGuiTheme.drawSectionHeader(ICON_CUBE, "参照基准方块");
        String refBlock = RoadPlacementState.getRefBlockId();
        if (refBlock.isEmpty()) {
            ImGui.textDisabled("未捕获参照方块 (在世界中右键点击方块捕获)");
        } else {
            ImGui.textColored(0.40f, 0.85f, 0.40f, 1.00f, "参照方块: " + refBlock);
        }

        if (ImGui.button("捕捉脚下方块为参照", -1, 24)) {
            BlockPos feet = getCapturedFeetPosition(mc);
            RoadPlacementState.setStartPos(feet);
            if (mc.level != null) {
                var st = mc.level.getBlockState(feet);
                String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                RoadPlacementState.setRefBlockId(id);
            }
        }

        ImGui.spacing();
        WandscapeImGuiTheme.drawSectionHeader(ICON_POINT, "平整区域边界");
        drawStartPosLabelControls(mc, "边界起点 (Start)");
        ImGui.spacing();
        drawEndPosLabelControls(mc, "边界终点 (End)");

        ImGui.spacing();
        BlockPos start = RoadPlacementState.getStartPos();
        BlockPos end = RoadPlacementState.getEndPos();
        if (start != null && end != null) {
            int dx = Math.abs(end.getX() - start.getX()) + 1;
            int dz = Math.abs(end.getZ() - start.getZ()) + 1;
            WandscapeImGuiTheme.drawSectionHeader(ICON_ROAD, "平整面积评估");
            ImGui.text(String.format("底面尺寸: %d × %d 方块", dx, dz));
            ImGui.text(String.format("平整面积: %d 平方方块", dx * dz));
        } else {
            WandscapeImGuiTheme.textMuted("提示: 请选择起点与终点以确定平整区域");
        }

        ImGui.spacing();
        ImGui.spacing();

        ImGui.pushStyleColor(ImGuiCol.Button, 0.22f, 0.45f, 0.25f, 0.90f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.30f, 0.60f, 0.32f, 1.00f);
        if (ImGui.button(ICON_TRASH + " 下发地形平整任务", -1, 36)) {
            if (RoadPlacementState.isReady()) {
                PacketDistributor.sendToServer(new com.wsteam.wandscape.road.network.DestroyFillPacket(start, end));
                Log.info(TAG, "[DestroyFill] Published destroy fill: start={} end={}", start, end);
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal("[Destroy/Fill] §aTerrain flatten task submitted! NPC will flatten the area."), true);
                }
                RoadPlacementState.clearAll();
            }
        }
        WandscapeImGuiTheme.drawTooltip("下发地形铲平/垫平任务给法师 NPC");
        ImGui.popStyleColor(2);
    }

    // ── Helper UI Controls for Presets & Points ──
    private static void drawPresetSelectorCombo() {
        List<RoadPreset> presets = RoadPlacementState.getPresets();
        int currentIdx = RoadPlacementState.getSelectedPresetIndex();
        String[] presetNames = presets.stream().map(RoadPreset::displayName).toArray(String[]::new);

        ImInt selectedPresetInt = new ImInt(currentIdx);
        ImGui.pushItemWidth(-1);
        if (ImGui.combo("##RoadPlacementPresetCombo", selectedPresetInt, presetNames)) {
            RoadPlacementState.setSelectedPresetIndex(selectedPresetInt.get());
        }
        ImGui.popItemWidth();
    }

    private static void drawStartPosLabelControls(Minecraft mc, String label) {
        BlockPos start = RoadPlacementState.getStartPos();
        WandscapeImGuiTheme.textMuted(label + ":");
        if (start != null) {
            ImInt px = new ImInt(start.getX());
            ImInt py = new ImInt(start.getY());
            ImInt pz = new ImInt(start.getZ());

            ImGui.pushItemWidth(65);
            boolean cx = ImGui.inputInt("X##StartX", px, 0, 0);
            ImGui.sameLine();
            boolean cy = ImGui.inputInt("Y##StartY", py, 0, 0);
            ImGui.sameLine();
            boolean cz = ImGui.inputInt("Z##StartZ", pz, 0, 0);
            ImGui.popItemWidth();

            if (cx || cy || cz) {
                RoadPlacementState.setStartPos(new BlockPos(px.get(), py.get(), pz.get()));
            }

            ImGui.sameLine();
            if (ImGui.button("清除##ClearStart", 42, 20)) {
                RoadPlacementState.clearStartPos();
            }
        } else {
            ImGui.textDisabled("  [未设置点位]");
            if (ImGui.button("捕捉脚下位点##SetFeetStart", -1, 24)) {
                RoadPlacementState.setStartPos(getCapturedFeetPosition(mc));
            }
        }
    }

    private static void drawEndPosLabelControls(Minecraft mc, String label) {
        BlockPos end = RoadPlacementState.getEndPos();
        WandscapeImGuiTheme.textMuted(label + ":");
        if (end != null) {
            ImInt px = new ImInt(end.getX());
            ImInt py = new ImInt(end.getY());
            ImInt pz = new ImInt(end.getZ());

            ImGui.pushItemWidth(65);
            boolean cx = ImGui.inputInt("X##EndX", px, 0, 0);
            ImGui.sameLine();
            boolean cy = ImGui.inputInt("Y##EndY", py, 0, 0);
            ImGui.sameLine();
            boolean cz = ImGui.inputInt("Z##EndZ", pz, 0, 0);
            ImGui.popItemWidth();

            if (cx || cy || cz) {
                RoadPlacementState.setEndPos(new BlockPos(px.get(), py.get(), pz.get()));
            }

            ImGui.sameLine();
            if (ImGui.button("清除##ClearEnd", 42, 20)) {
                RoadPlacementState.clearEndPos();
            }
        } else {
            ImGui.textDisabled("  [未设置点位]");
            if (ImGui.button("捕捉脚下位点##SetFeetEnd", -1, 24)) {
                RoadPlacementState.setEndPos(getCapturedFeetPosition(mc));
            }
        }
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

            String toolName = switch (RoadPlacementState.getActiveTool()) {
                case REPLACE -> "直线替换";
                case FILL -> "立方体填充";
                case DESTROY_FILL -> "铲平垫平";
                case SPLINE -> "样条曲线";
            };
            String topDownStr = SplineEditorClientState.isTopDown() ? "2D 俯瞰" : "3D 自由";
            ImGui.textColored(0.40f, 0.75f, 0.95f, 1.00f, String.format("模式: %s  |  视角: %s", toolName, topDownStr));
        }
        ImGui.endChild();
        ImGui.popStyleColor(2);
        ImGui.spacing();
    }


    // \u2500\u2500 Tab 1: \u66f2\u7ebf\u7f16\u8f91 \u2500\u2500
    private static void drawCurveNodesTab(SplineModel model, Minecraft mc) {
        ImGui.spacing();
        
        // \u6a21\u5f0f\u9009\u62e9\u5668
        WandscapeImGuiTheme.drawSectionHeader(ICON_EDIT, "\u7f16\u8f91\u6a21\u5f0f\u5207\u6362");
        
        boolean isAdd = SplineEditorClientState.getEditMode() == SplineEditorClientState.EditMode.ADD;
        float halfW = (ImGui.getContentRegionAvailX() - 8.0f) / 2.0f;

        if (isAdd) {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.35f, 0.28f, 0.48f, 1.00f);
            ImGui.pushStyleColor(ImGuiCol.Border, 0.95f, 0.78f, 0.30f, 0.90f);
        } else {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.16f, 0.14f, 0.20f, 0.85f);
            ImGui.pushStyleColor(ImGuiCol.Border, 0.40f, 0.35f, 0.25f, 0.40f);
        }
        if (ImGui.button(ICON_ADD + " \u70b9\u51fb\u6dfb\u52a0\u70b9", halfW, 32)) {
            SplineEditorClientState.setEditMode(SplineEditorClientState.EditMode.ADD);
        }
        WandscapeImGuiTheme.drawTooltip("\u3010\u6dfb\u52a0\u6a21\u5f0f\u3011\u5728\u6e38\u620f\u4e16\u754c\u4e2d\u5de6\u952e\u70b9\u51fb\u65b9\u5757\u8868\u9762\uff0c\u53ef\u987a\u5e8f\u653e\u7f6e\u65b0\u7684\u9053\u8def\u63a7\u5236\u70b9\u3002");
        ImGui.popStyleColor(2);

        ImGui.sameLine();

        if (!isAdd) {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.35f, 0.28f, 0.48f, 1.00f);
            ImGui.pushStyleColor(ImGuiCol.Border, 0.95f, 0.78f, 0.30f, 0.90f);
        } else {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.16f, 0.14f, 0.20f, 0.85f);
            ImGui.pushStyleColor(ImGuiCol.Border, 0.40f, 0.35f, 0.25f, 0.40f);
        }
        if (ImGui.button(ICON_EDIT + " \u9009\u62e9\u4e0e\u62d6\u62fd", halfW, 32)) {
            SplineEditorClientState.setEditMode(SplineEditorClientState.EditMode.EDIT);
        }
        WandscapeImGuiTheme.drawTooltip("\u3010\u9009\u62e9\u7f16\u8f91\u6a21\u5f0f\u3011\u5728\u4e16\u754c\u4e2d\u6216\u4e0b\u65b9\u5217\u8868\u4e2d\u70b9\u51fb\u63a7\u5236\u70b9\uff0c\u901a\u8fc7 3D \u5750\u6807\u8f74\u624b\u67c4\u62d6\u52a8\u8c03\u63a7\u66f2\u7ebf\u8f68\u8ff9\u3002");
        ImGui.popStyleColor(2);

        ImGui.spacing();
        ImGui.spacing();

        // \u66f2\u7ebf\u51e0\u4f55\u4e0e\u5e73\u79fb
        WandscapeImGuiTheme.drawSectionHeader(ICON_ROAD, "\u66f2\u7ebf\u51e0\u4f55\u4e0e\u5e73\u79fb");
        
        ImBoolean closed = new ImBoolean(model.isClosed());
        if (ImGui.checkbox("\u95ed\u5408\u73af\u5f62\u9053\u8def (\u8fde\u63a5\u9996\u5c3e)", closed)) {
            model.setClosed(closed.get());
        }
        WandscapeImGuiTheme.drawTooltip("\u52fe\u9009\u540e\u81ea\u52a8\u8fde\u63a5\u9053\u8def\u9996\u5c3e\u70b9\uff0c\u6784\u5efa\u65e0\u7f1d\u95ed\u5408\u7684\u73af\u5f62\u9053\u8def\u3002");

        ImGui.spacing();
        WandscapeImGuiTheme.textMuted("\u6574\u4f53\u5e73\u79fb\u504f\u79fb\u91cf (X, Y, Z):");
        
        ImGui.pushItemWidth(55);
        ImGui.inputDouble("##ShiftX", globalShiftX, 0.0, 0.0, "%.1f");
        WandscapeImGuiTheme.drawTooltip("X \u8f74\u5e73\u79fb\u589e\u91cf (\u65b9\u5757)");
        ImGui.sameLine();
        ImGui.inputDouble("##ShiftY", globalShiftY, 0.0, 0.0, "%.1f");
        WandscapeImGuiTheme.drawTooltip("Y \u8f74\u9ad8\u7a0b\u589e\u91cf (\u65b9\u5757)");
        ImGui.sameLine();
        ImGui.inputDouble("##ShiftZ", globalShiftZ, 0.0, 0.0, "%.1f");
        WandscapeImGuiTheme.drawTooltip("Z \u8f74\u5e73\u79fb\u589e\u91cf (\u65b9\u5757)");
        ImGui.popItemWidth();

        ImGui.sameLine();
        if (ImGui.button("\u6574\u4f53\u5e73\u79fb", 60, 24)) {
            SplineVec3 delta = new SplineVec3(globalShiftX.get(), globalShiftY.get(), globalShiftZ.get());
            model.translateAll(delta);
            globalShiftX.set(0.0);
            globalShiftY.set(0.0);
            globalShiftZ.set(0.0);
            Log.info(TAG, "Translated all points by {}", delta);
        }
        WandscapeImGuiTheme.drawTooltip("\u5c06\u6574\u6761\u66f2\u7ebf\u4e0a\u7684\u6240\u6709\u63a7\u5236\u70b9\u6309\u7167\u8f93\u5165\u7684 (X, Y, Z) \u504f\u79fb\u91cf\u4e00\u5e76\u5e73\u79fb\u3002");

        ImGui.spacing();
        ImGui.spacing();

        // \u63a7\u5236\u70b9\u5217\u8868
        WandscapeImGuiTheme.drawSectionHeader(ICON_POINT, "\u63a7\u5236\u70b9\u5217\u8868 (" + model.getPoints().size() + ")");
        
        if (ImGui.beginChild("PointsListChild", 0, 130, true)) {
            if (model.getPoints().isEmpty()) {
                ImGui.spacing();
                ImGui.textDisabled("  \u5f53\u524d\u65e0\u63a7\u5236\u70b9\u3002");
                ImGui.textDisabled("  \u8bf7\u5728\u4e16\u754c\u4e2d\u5de6\u952e\u70b9\u51fb\u65b9\u5757\u6dfb\u52a0\u3002");
            } else {
                for (int i = 0; i < model.getPoints().size(); i++) {
                    SplinePoint pt = model.getPoints().get(i);
                    SplineVec3 anchor = pt.getAnchor();
                    String symTag = pt.isLocked() ? "[\u5bf9\u79f0]" : "[\u81ea\u7531]";
                    String label = String.format("#%d  (%.1f, %.1f, %.1f) %s", i, anchor.x(), anchor.y(), anchor.z(), symTag);
                    
                    boolean isSelected = SplineEditorClientState.getSelectedPointIndex() == i;
                    if (ImGui.selectable(label, isSelected)) {
                        SplineEditorClientState.setSelectedPoint(i, SplineEditorClientState.SelectionType.ANCHOR);
                        SplineEditorClientState.setEditMode(SplineEditorClientState.EditMode.EDIT);
                    }
                    if (isSelected) {
                        WandscapeImGuiTheme.drawTooltip("\u5f53\u524d\u9009\u4e2d\u7684\u63a7\u5236\u70b9 #" + i + "\u3002\u53ef\u5728\u4e0b\u65b9\u68c0\u67e5\u5668\u4e2d\u7cbe\u51c6\u4fee\u6539\u3002");
                    }
                }
            }
        }
        ImGui.endChild();

        // \u63a7\u5236\u70b9\u5c5e\u6027\u68c0\u67e5\u5668
        int selectedIdx = SplineEditorClientState.getSelectedPointIndex();
        SplineEditorClientState.SelectionType selectedType = SplineEditorClientState.getSelectedType();

        if (selectedIdx >= 0 && selectedIdx < model.getPoints().size()) {
            SplinePoint pt = model.getPoints().get(selectedIdx);
            
            ImGui.spacing();
            WandscapeImGuiTheme.drawSectionHeader(ICON_CAM, "\u8282\u70b9\u5c5e\u6027\u68c0\u67e5\u5668 #" + selectedIdx);

            WandscapeImGuiTheme.textMuted("\u8c03\u6574\u53e5\u67c4\u76ee\u6807:");
            if (ImGui.radioButton("\u4e3b\u951a\u70b9", selectedType == SplineEditorClientState.SelectionType.ANCHOR)) {
                SplineEditorClientState.setSelectedPoint(selectedIdx, SplineEditorClientState.SelectionType.ANCHOR);
            }
            WandscapeImGuiTheme.drawTooltip("\u79fb\u52a8\u63a7\u5236\u70b9\u5728\u4e16\u754c\u4e2d\u7684\u4e3b\u4e2d\u5fc3\u4f4d\u7f6e");
            ImGui.sameLine();
            if (ImGui.radioButton("\u524d\u624b\u67c4", selectedType == SplineEditorClientState.SelectionType.CONTROL_PREV)) {
                SplineEditorClientState.setSelectedPoint(selectedIdx, SplineEditorClientState.SelectionType.CONTROL_PREV);
            }
            WandscapeImGuiTheme.drawTooltip("\u8c03\u6574\u5165\u5411 Bezier \u5207\u7ebf\u63a7\u5236\u70b9");
            ImGui.sameLine();
            if (ImGui.radioButton("\u540e\u624b\u67c4", selectedType == SplineEditorClientState.SelectionType.CONTROL_NEXT)) {
                SplineEditorClientState.setSelectedPoint(selectedIdx, SplineEditorClientState.SelectionType.CONTROL_NEXT);
            }
            WandscapeImGuiTheme.drawTooltip("\u8c03\u6574\u51fa\u5411 Bezier \u5207\u7ebf\u63a7\u5236\u70b9");

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

                ImGui.text("\u4e09\u7ef4\u7cbe\u786e\u5750\u6807:");
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
            if (ImGui.checkbox("\u5bf9\u79f0\u5207\u7ebf\u624b\u67c4\u9501\u5b9a", locked)) {
                pt.setLocked(locked.get());
            }
            WandscapeImGuiTheme.drawTooltip("\u4fdd\u6301\u524d\u540e\u5207\u7ebf\u63a7\u5236\u624b\u67c4\u5bf9\u79f0\u955c\u50cf\u8054\u52a8\uff0c\u786e\u4fdd\u5e73\u6ed1\u8fc7\u6e21\u3002");

            ImGui.sameLine();
            if (ImGui.button(ICON_CAM + " \u89c6\u89d2\u805a\u7126")) {
                SplineVec3 pos = pt.getAnchor();
                SplineEditorClientState.setCamPosition(pos.x(), pos.y() + 3.0, pos.z() + 5.0);
            }
            WandscapeImGuiTheme.drawTooltip("\u5c06\u81ea\u7531\u89c6\u89d2\u6444\u50cf\u673a\u77ac\u95f4\u79fb\u81f3\u9009\u4e2d\u7684\u63a7\u5236\u70b9\u9644\u8fd1\u3002");

            ImGui.spacing();
            ImGui.pushStyleColor(ImGuiCol.Button, 0.55f, 0.15f, 0.15f, 0.85f);
            ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.75f, 0.20f, 0.20f, 0.95f);
            if (ImGui.button(ICON_TRASH + " \u5220\u9664\u8282\u70b9 #" + selectedIdx, -1, 26)) {
                model.removePoint(selectedIdx);
                SplineEditorClientState.setSelectedPoint(-1, SplineEditorClientState.SelectionType.NONE);
            }
            WandscapeImGuiTheme.drawTooltip("\u79fb\u9664\u5f53\u524d\u9009\u4e2d\u7684\u63a7\u5236\u8282\u70b9 (\u5feb\u6377\u952e Delete / Backspace)\u3002");
            ImGui.popStyleColor(2);
        }
    }

    // \u2500\u2500 Tab 2: \u9635\u5217\u751f\u6210 \u2500\u2500
    private static void drawArrayStudioTab() {
        ImGui.spacing();
        
        // 1. \u6a21\u677f\u6765\u6e90\u9009\u62e9
        WandscapeImGuiTheme.drawSectionHeader(ICON_LINK, "\u6a21\u677f\u6765\u6e90\u4e0e V \u9762\u677f\u8054\u52a8");

        var sourceMode = SplineEditorClientState.getTemplateSourceMode();
        if (ImGui.radioButton("V \u9762\u677f\u65b9\u5757\u9884\u8bbe (\u63a8\u8350)", sourceMode == SplineEditorClientState.TemplateSourceMode.VPANEL_PRESET)) {
            SplineEditorClientState.setTemplateSourceMode(SplineEditorClientState.TemplateSourceMode.VPANEL_PRESET);
        }
        WandscapeImGuiTheme.drawTooltip("\u4e0e\u6982\u89c8 (V) \u9762\u677f\u9009\u4e2d\u7684\u65b9\u5757\u9884\u8bbe\u76f4\u63a5\u8054\u52a8\uff0c\u5e76\u5141\u8bb8\u5b9e\u65f6\u52a8\u6001\u8c03\u8282\u5bbd\u5ea6\u4e0e\u539a\u5ea6\u3002");

        ImGui.sameLine();

        if (ImGui.radioButton("JSON \u9884\u8bbe\u6587\u4ef6", sourceMode == SplineEditorClientState.TemplateSourceMode.JSON_FILE)) {
            SplineEditorClientState.setTemplateSourceMode(SplineEditorClientState.TemplateSourceMode.JSON_FILE);
        }
        WandscapeImGuiTheme.drawTooltip("\u4f7f\u7528\u786c\u76d8\u4e2d\u7684 JSON \u7ed3\u6784\u6a21\u677f\u505a\u9635\u5217\u6392\u5e03\u3002");

        ImGui.spacing();

        // 2. \u6839\u636e\u6765\u6e90\u6a21\u5f0f\u663e\u793a\u914d\u7f6e\u9762\u677f
        if (SplineEditorClientState.getTemplateSourceMode() == SplineEditorClientState.TemplateSourceMode.VPANEL_PRESET) {
            // V \u9762\u677f\u9009\u62e9\u4e0e\u52a8\u6001\u751f\u6210\u5668
            List<RoadPreset> presets = RoadPlacementState.getPresets();
            int currentIdx = RoadPlacementState.getSelectedPresetIndex();
            String[] presetNames = presets.stream().map(RoadPreset::displayName).toArray(String[]::new);

            ImInt selectedPresetInt = new ImInt(currentIdx);
            ImGui.text("V \u9762\u677f\u9009\u4e2d\u7684\u65b9\u5757:");
            ImGui.pushItemWidth(-1);
            if (ImGui.combo("##VPanelPresetCombo", selectedPresetInt, presetNames)) {
                RoadPlacementState.setSelectedPresetIndex(selectedPresetInt.get());
                SplineEditorClientState.rebuildDynamicTemplate();
            }
            ImGui.popItemWidth();
            WandscapeImGuiTheme.drawTooltip("\u5728 ImGui \u91cc\u9009\u62e9\u9053\u8def\u65b9\u5757\u4f1a\u5b9e\u65f6\u540c\u6b65\u66f4\u65b0 V \u9762\u677f\u9009\u4e2d\u7684\u9884\u8bbe\uff01");

            ImGui.spacing();
            WandscapeImGuiTheme.textMuted("\u52a8\u6001\u9053\u8def\u89c4\u683c\u751f\u6210\u5668:");

            uiDynamicWidth[0] = SplineEditorClientState.getDynamicWidth();
            uiDynamicDepth[0] = SplineEditorClientState.getDynamicDepth();
            uiDynamicBorder.set(SplineEditorClientState.isDynamicHasBorder());

            boolean dynamicChanged = false;
            ImGui.pushItemWidth(180);
            if (ImGui.sliderInt("\u9053\u8def\u5bbd\u5ea6 (Width)", uiDynamicWidth, 1, 15, "%d \u683c\u65b9\u5757")) {
                SplineEditorClientState.setDynamicWidth(uiDynamicWidth[0]);
                dynamicChanged = true;
            }
            WandscapeImGuiTheme.drawTooltip("\u8c03\u8282\u751f\u6210\u9053\u8def\u6a2a\u5411\u94fa\u8bbe\u7684\u65b9\u5757\u5bbd\u5ea6 (1 ~ 15 \u683c)");

            if (ImGui.sliderInt("\u57fa\u5c42\u539a\u5ea6 (Depth)", uiDynamicDepth, 1, 3, "%d \u5c42\u6df1")) {
                SplineEditorClientState.setDynamicDepth(uiDynamicDepth[0]);
                dynamicChanged = true;
            }
            WandscapeImGuiTheme.drawTooltip("\u8c03\u8282\u9053\u8def\u5782\u76f4\u5411\u4e0b\u94fa\u8bbe\u7684\u57fa\u7840\u5c42\u6570 (1 ~ 3 \u5c42)");
            ImGui.popItemWidth();

            if (ImGui.checkbox("\u52a0\u88c5\u8def\u80a9\u77f3\u8fb9 (Side Border)", uiDynamicBorder)) {
                SplineEditorClientState.setDynamicHasBorder(uiDynamicBorder.get());
                dynamicChanged = true;
            }
            WandscapeImGuiTheme.drawTooltip("\u52fe\u9009\u540e\uff0c\u9053\u8def\u5de6\u53f3\u4e24\u4fa7\u6700\u8fb9\u7f18\u81ea\u52a8\u94fa\u8bbe\u77f3\u7816\u62a4\u680f\u5305\u88f9\u3002");

            if (dynamicChanged) {
                SplineEditorClientState.rebuildDynamicTemplate();
            }

        } else {
            // JSON \u6587\u4ef6\u6a21\u5f0f
            List<String> templateIds = SplineEditorClientState.getAvailableTemplateIds();
            if (!templateIds.isEmpty()) {
                String currentId = SplineEditorClientState.getActiveTemplateId();
                int idx = Math.max(0, templateIds.indexOf(currentId));
                
                ImInt activeTemplateIdx = new ImInt(idx);
                String[] templateArray = templateIds.toArray(new String[0]);
                ImGui.text("\u9009\u62e9 JSON \u6a21\u677f:");
                ImGui.pushItemWidth(-1);
                if (ImGui.combo("##BlueprintCombo", activeTemplateIdx, templateArray)) {
                    SplineEditorClientState.setActiveTemplateId(templateArray[activeTemplateIdx.get()]);
                }
                ImGui.popItemWidth();
                WandscapeImGuiTheme.drawTooltip("\u9009\u62e9\u8bfb\u53d6\u5230\u7684 JSON \u7ed3\u6784\u6587\u4ef6\u8fdb\u884c\u6392\u5e03\u3002");
            } else {
                ImGui.textDisabled("\u6682\u65e0\u53ef\u7528\u7684 JSON \u84dd\u56fe\u6a21\u677f\u3002");
            }
        }

        RoadTemplate activeTmpl = SplineEditorClientState.getActiveTemplate();
        if (activeTmpl != null) {
            WandscapeImGuiTheme.textMuted("\u5f53\u524d\u4f7f\u7528\u6a21\u677f: " + activeTmpl.getId() + " (\u5355\u6bb5\u542b " + activeTmpl.getBlocks().size() + " \u4e2a\u65b9\u5757)");
        }

        ImGui.spacing();
        ImGui.spacing();

        // 3. \u5b9e\u65f6 3D \u9884\u89c8\u4e0e\u91c7\u6837
        WandscapeImGuiTheme.drawSectionHeader(ICON_EYE, "\u5b9e\u65f6 3D \u9884\u89c8\u4e0e\u8c03\u6574");

        uiArrayPreview.set(SplineEditorClientState.isArrayPreview());
        if (ImGui.checkbox("\u5f00\u542f\u9635\u5217 3D \u5b9e\u65f6\u9884\u89c8", uiArrayPreview)) {
            SplineEditorClientState.setArrayPreview(uiArrayPreview.get());
        }
        WandscapeImGuiTheme.drawTooltip("\u5728\u6e38\u620f\u4e16\u754c\u4e2d\u4ee5 3D \u5305\u56f4\u76d2\u7684\u5f62\u5f0f\u5b9e\u65f6\u6e32\u67d3\u6cbf\u66f2\u7ebf\u6392\u5217\u7684\u65b9\u5757\u4f4d\u59ff\u3002");

        if (SplineEditorClientState.isArrayPreview()) {
            ImGui.spacing();
            uiStepDistance.set(SplineEditorClientState.getArrayStepDistance());
            ImGui.pushItemWidth(140);
            if (ImGui.inputDouble("\u91c7\u6837\u6b65\u8ddd (\u683c)", uiStepDistance, 0.5, 1.0, "%.2f")) {
                SplineEditorClientState.setArrayStepDistance(Math.max(0.1, uiStepDistance.get()));
            }
            ImGui.popItemWidth();
            WandscapeImGuiTheme.drawTooltip("\u6cbf Bezier \u66f2\u7ebf\u91c7\u6837\u7684\u95f4\u8ddd\uff08\u5355\u4f4d\uff1a\u65b9\u5757\uff09\u3002\u8d8a\u5c0f\u751f\u6210\u8d8a\u5e73\u6ed1\u5bc6\u5b9e\u3002");

            ImGui.spacing();
            WandscapeImGuiTheme.textMuted("3D \u9635\u5217\u59ff\u6001\u65cb\u8f6c\u5fae\u8c03:");

            uiOffsetRoll[0] = (float) SplineEditorClientState.getArrayOffsetRoll();
            uiOffsetPitch[0] = (float) SplineEditorClientState.getArrayOffsetPitch();
            uiOffsetYaw[0] = (float) SplineEditorClientState.getArrayOffsetYaw();

            boolean rotChanged = false;
            ImGui.pushItemWidth(180);
            rotChanged |= ImGui.sliderFloat("\u6eda\u8f6c\u89d2 Roll", uiOffsetRoll, -180.0f, 180.0f, "%.1f \u5ea6");
            rotChanged |= ImGui.sliderFloat("\u4fef\u4ef0\u89d2 Pitch", uiOffsetPitch, -180.0f, 180.0f, "%.1f \u5ea6");
            rotChanged |= ImGui.sliderFloat("\u504f\u822a\u89d2 Yaw", uiOffsetYaw, -180.0f, 180.0f, "%.1f \u5ea6");
            ImGui.popItemWidth();

            ImGui.sameLine();
            if (ImGui.button("0\u00b0 \u91cd\u7f6e")) {
                uiOffsetRoll[0] = 0;
                uiOffsetPitch[0] = 0;
                uiOffsetYaw[0] = 0;
                rotChanged = true;
            }
            WandscapeImGuiTheme.drawTooltip("\u5feb\u6377\u91cd\u7f6e\u6240\u6709\u65cb\u8f6c\u89d2\u4e3a 0\u00b0");

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
            if (ImGui.button(ICON_CUBE + " \u4e0b\u53d1\u9053\u8def\u5efa\u9020\u4efb\u52a1", -1, 36)) {
                SplineEditorController.doBuildArray();
            }
            WandscapeImGuiTheme.drawTooltip("\u5c06\u6253\u5305\u597d\u7684\u9053\u8def\u65b9\u5757\u53d1\u9001\u7ed9\u670d\u52a1\u7aef\uff0c\u7531\u6b96\u6c11\u5730\u5efa\u7b51\u6cd5\u5e08 NPC \u81ea\u52a8\u5efa\u9020\uff01");
            ImGui.popStyleColor(3);
        }
    }

    // \u2500\u2500 Tab 3: \u6a21\u677f\u4e0e\u5de5\u5177 \u2500\u2500
    private static void drawTemplatesTab(SplineModel model, Minecraft mc) {
        ImGui.spacing();
        WandscapeImGuiTheme.drawSectionHeader(ICON_SAVE, "\u6a21\u677f\u6587\u4ef6\u7ba1\u7406");

        ImGui.pushItemWidth(-1);
        ImGui.inputTextWithHint("##TemplateName", "\u8f93\u5165\u6a21\u677f\u540d\u79f0 (\u4f8b\u5982: main_road)", templateNameInput);
        ImGui.popItemWidth();
        ImGui.spacing();

        float halfW = (ImGui.getContentRegionAvailX() - 8.0f) / 2.0f;
        
        ImGui.pushStyleColor(ImGuiCol.Button, 0.15f, 0.32f, 0.50f, 0.90f);
        if (ImGui.button(ICON_SAVE + " \u4fdd\u5b58 JSON \u6a21\u677f", halfW, 28)) {
            String name = templateNameInput.get().trim();
            if (!name.isEmpty()) {
                SplineEditorClientState.saveTemplate(name);
            }
        }
        WandscapeImGuiTheme.drawTooltip("\u5c06\u5f53\u524d\u7ed8\u5236\u597d\u7684\u66f2\u7ebf\u8282\u70b9\u5bfc\u51fa\u4fdd\u5b58\u81f3 config/wandscape/splines/");
        ImGui.popStyleColor();

        ImGui.sameLine();

        ImGui.pushStyleColor(ImGuiCol.Button, 0.20f, 0.28f, 0.42f, 0.90f);
        if (ImGui.button(ICON_LOAD + " \u8bfb\u53d6 JSON \u6a21\u677f", halfW, 28)) {
            String name = templateNameInput.get().trim();
            if (!name.isEmpty()) {
                Vec3 pos = SplineEditorClientState.isEditing()
                        ? new Vec3(SplineEditorClientState.getCamX(), SplineEditorClientState.getCamY(), SplineEditorClientState.getCamZ())
                        : (mc.player != null ? mc.player.position() : Vec3.ZERO);
                SplineVec3 placementOrigin = new SplineVec3(pos.x, pos.y, pos.z);
                SplineEditorClientState.loadTemplate(name, placementOrigin);
            }
        }
        WandscapeImGuiTheme.drawTooltip("\u4ece config/wandscape/splines/ \u4e2d\u8bfb\u53d6\u66f2\u7ebf\u6a21\u677f\u5e76\u653e\u7f6e\u5728\u5f53\u524d\u73a9\u5bb6\u4f4d\u7f6e\u3002");
        ImGui.popStyleColor();

        ImGui.spacing();
        ImGui.spacing();
        WandscapeImGuiTheme.drawSectionHeader(ICON_EYE, "\u89c6\u56fe\u4e0e\u5feb\u6377\u5de5\u5177");

        boolean topDown = SplineEditorClientState.isTopDown();
        String btnText = topDown ? ICON_EYE + " \u9000\u51fa 2D \u4fef\u77b0\u89c6\u89d2 (G)" : ICON_EYE + " \u5207\u6362 2D \u4fef\u77b0\u89c6\u89d2 (G)";
        
        if (ImGui.button(btnText, -1, 30)) {
            if (topDown) {
                SplineEditorClientState.exitTopDown();
            } else {
                SplineEditorClientState.enterTopDown();
            }
        }
        WandscapeImGuiTheme.drawTooltip("\u5728 2D \u9e1f\u77b0\u89c6\u89d2\u4e0e 3D \u81ea\u7531\u89c6\u89d2\u95f4\u5207\u6362 (\u5feb\u6377\u952e G)");

        ImGui.spacing();

        if (ImGui.button(ICON_HELP + " \u6253\u5f00\u64cd\u4f5c\u6307\u5357 (H)", -1, 30)) {
            String content = com.wsteam.wandscape.shared.ui.markdown.navigation.DocumentLoader.loadMarkdown("road_spline_guide");
            mc.setScreen(new com.wsteam.wandscape.shared.ui.guide.GuideTestScreen(null, content, "road_spline_guide"));
        }
        WandscapeImGuiTheme.drawTooltip("\u6253\u5f00\u9053\u8def\u5236\u4f5c\u4e0e Spline \u66f2\u7ebf\u64cd\u4f5c\u8bf4\u660e\u6587\u6863 (\u5feb\u6377\u952e H)");

        ImGui.spacing();

        if (ImGui.button("\u6e05\u7a7a\u753b\u5e03", halfW, 28)) {
            model.clear();
            SplineEditorClientState.setSelectedPoint(-1, SplineEditorClientState.SelectionType.NONE);
        }
        WandscapeImGuiTheme.drawTooltip("\u6e05\u7a7a\u5f53\u524d\u753b\u5e03\u4e0a\u7684\u6240\u6709\u63a7\u5236\u8282\u70b9\u3002");

        ImGui.sameLine();

        ImGui.pushStyleColor(ImGuiCol.Button, 0.55f, 0.15f, 0.15f, 0.85f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, 0.75f, 0.20f, 0.20f, 0.95f);
        if (ImGui.button("\u5173\u95ed Studio", halfW, 28)) {
            SplineEditorClientState.exitEditMode();
            ImGuiManager.setVisible(false);
        }
        WandscapeImGuiTheme.drawTooltip("\u9000\u51fa\u9053\u8def\u5236\u4f5c\u5de5\u574a (\u5feb\u6377\u952e ESC)\u3002");
        ImGui.popStyleColor(2);
    }

    // \u2500\u2500 Bottom Action Footer \u2500\u2500
    private static void drawBottomFooter() {
        ImGui.setCursorPosY(ImGui.getWindowHeight() - 26);
        ImGui.separator();
        WandscapeImGuiTheme.textMuted(" [\u53f3\u952e\u6309\u4f4f] \u65cb\u8f6c\u89c6\u89d2 | [G] \u4fef\u77b0 | [H] \u6307\u5357 | [ESC] \u9000\u51fa");
    }
}
