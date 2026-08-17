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
 * Self-drawn overlay panel for the Road Studio — replaces ImGui {@code SplineEditorImGui}.
 * Renders a right-side panel using MC-native {@link GuiGraphics} with the
 * immediate-mode widget system in {@link StudioWidgets}.
 *
 * <p>Toggle between this and ImGui with F11 while the spline editor is active.
 */
public final class RoadStudioOverlay {
    private static final String TAG = "RoadStudioOverlay";

    private static boolean visible = false;
    private static boolean registered = false;

    // Panel geometry (GUI-scaled coordinates)
    private static float panelWidthRatio = 0.30f;
    private static final float MIN_PANEL_RATIO = 0.22f;
    private static final float MAX_PANEL_RATIO = 0.60f;
    private static final int PAD = 10;

    // Scroll
    private static int scrollOffset = 0;
    private static int lastContentHeight = 0;

    // Input state per frame
    private static int frameMouseX, frameMouseY;
    private static boolean frameMouseDown, frameMouseClicked, frameMouseReleased;
    private static double pendingScroll = 0;
    private static boolean wasMouseDown = false;

    // Splitter drag
    private static boolean splitterDragging = false;
    private static int splitterDragStartX = 0;
    private static float splitterDragStartRatio = 0;

    // Spline tab index (for SPLINE mode)
    private static int splineTabIndex = 0;

    // Global shift accumulators (mirrors ImGui ImDouble fields)
    private static double globalShiftX = 0, globalShiftY = 0, globalShiftZ = 0;

    private RoadStudioOverlay() {}

    // ════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ════════════════════════════════════════════════════════════════

    public static void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(RenderGuiEvent.Post.class, RoadStudioOverlay::onRenderGui);
        NeoForge.EVENT_BUS.addListener(InputEvent.MouseButton.Pre.class, RoadStudioOverlay::onMouseButton);
        NeoForge.EVENT_BUS.addListener(InputEvent.MouseScrollingEvent.class, RoadStudioOverlay::onMouseScroll);
        NeoForge.EVENT_BUS.addListener(InputEvent.Key.class, RoadStudioOverlay::onKey);
        Log.info(TAG, "RoadStudioOverlay registered");
    }

    public static boolean isVisible() { return visible; }

    public static void setVisible(boolean v) {
        visible = v;
        if (v) {
            scrollOffset = 0;
            splineTabIndex = 0;
        }
    }

    /** True if the mouse cursor is currently over the overlay panel. */
    public static boolean isMouseOverPanel() {
        if (!visible) return false;
        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int panelW = (int)(screenW * panelWidthRatio);
        int panelX = screenW - panelW;
        return frameMouseX >= panelX;
    }

    /** True if the overlay wants to capture keyboard input (e.g. focused widget). */
    public static boolean wantsKeyboard() {
        return false; // No text input fields in first iteration
    }

    // ════════════════════════════════════════════════════════════════
    //  INPUT EVENTS
    // ════════════════════════════════════════════════════════════════

    private static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (!visible || !SplineEditorClientState.isEditing()) return;
        if (Minecraft.getInstance().screen != null) return;

        // Update mouse position
        updateMousePos();

        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (event.getAction() == GLFW.GLFW_PRESS) {
                frameMouseClicked = true;
                frameMouseDown = true;
            } else if (event.getAction() == GLFW.GLFW_RELEASE) {
                frameMouseReleased = true;
                frameMouseDown = false;
            }

            // Cancel if mouse is over the panel
            if (isMouseOverPanel()) {
                event.setCanceled(true);
            }
        }

        // Right-click: don't cancel if over panel, let the controller handle camera
    }

    private static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!visible || !SplineEditorClientState.isEditing()) return;
        if (Minecraft.getInstance().screen != null) return;
        updateMousePos();

        if (isMouseOverPanel()) {
            pendingScroll += event.getScrollDeltaY();
            event.setCanceled(true);
        }
    }

    private static void onKey(InputEvent.Key event) {
        if (!SplineEditorClientState.isEditing()) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        // F11: toggle native overlay visibility
        if (event.getKey() == GLFW.GLFW_KEY_F11) {
            visible = !visible;
            if (visible) {
                // Hide ImGui when showing native overlay
                com.wsteam.wandscape.imgui.ImGuiManager.setVisible(false);
                Log.info(TAG, "Switched to native overlay");
            } else {
                // Show ImGui when hiding native overlay
                com.wsteam.wandscape.imgui.ImGuiManager.setVisible(true);
                Log.info(TAG, "Switched to ImGui overlay");
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(
                        visible ? "§a[RoadStudio] Native overlay ON (F11 to switch)"
                                : "§e[RoadStudio] ImGui overlay ON (F11 to switch)"), true);
            }
        }
    }

    private static void updateMousePos() {
        Minecraft mc = Minecraft.getInstance();
        double[] mx = new double[1], my = new double[1];
        GLFW.glfwGetCursorPos(mc.getWindow().getWindow(), mx, my);
        double guiScale = mc.getWindow().getGuiScale();
        frameMouseX = (int)(mx[0] / guiScale);
        frameMouseY = (int)(my[0] / guiScale);
    }

    // ════════════════════════════════════════════════════════════════
    //  RENDER
    // ════════════════════════════════════════════════════════════════

    private static void onRenderGui(RenderGuiEvent.Post event) {
        if (!visible || !SplineEditorClientState.isEditing()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (mc.screen != null) return;

        // Update mouse state from GLFW
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

        int panelW = (int)(screenW * panelWidthRatio);
        panelW = Math.max(160, Math.min(screenW - 40, panelW));
        int panelX = screenW - panelW;
        int panelH = screenH;

        // Handle scroll
        if (pendingScroll != 0 && isMouseOverPanel()) {
            scrollOffset -= (int)(pendingScroll * 12);
            int maxScroll = Math.max(0, lastContentHeight - panelH + 60);
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset));
        }
        pendingScroll = 0;

        // Handle splitter drag
        handleSplitter(screenW, panelX);

        // Begin widget frame
        StudioWidgets.beginFrame(g, font, frameMouseX, frameMouseY,
                leftDown, clicked, released, 0);

        // ── Panel background ──
        g.fill(panelX, 0, screenW, screenH, StudioColors.PANEL_BG);

        // Splitter line
        boolean splitterHov = Math.abs(frameMouseX - panelX) < 4;
        int lineColor = (splitterHov || splitterDragging)
                ? StudioColors.SPLITTER_ACTIVE : StudioColors.SPLITTER_NORMAL;
        g.fill(panelX, 0, panelX + 1, screenH, lineColor);

        // ── Content area (with scroll) ──
        int contentX = panelX + PAD;
        int contentW = panelW - PAD * 2 - 8; // 8 for scrollbar
        int contentY = PAD - scrollOffset;

        g.enableScissor(panelX + 1, 0, screenW, screenH);

        StudioWidgets.beginLayout(contentX, contentY, contentW);

        SplineModel model = SplineEditorClientState.getModel();

        drawHeaderBanner(g, font, contentX, contentW, model);
        drawToolModeSelector();

        RoadPlacementState.ToolMode currentTool = RoadPlacementState.getActiveTool();
        switch (currentTool) {
            case REPLACE -> drawReplaceModeTab(mc);
            case FILL -> drawFillModeTab(mc);
            case DESTROY_FILL -> drawDestroyFillModeTab(mc);
            case SPLINE -> drawSplineModeTab(model, mc);
        }

        drawBottomFooter(g, font, panelX, panelW, screenH);

        lastContentHeight = StudioWidgets.endLayout();

        g.disableScissor();

        // Scrollbar
        if (lastContentHeight > panelH - 30) {
            StudioWidgets.verticalScrollbar(
                    screenW - 8, 0, screenH, lastContentHeight + 30, scrollOffset);
        }

        // Combo dropdown (renders on top of everything)
        int comboResult = StudioWidgets.renderComboDropdown();
        if (comboResult >= 0) {
            RoadPlacementState.setSelectedPresetIndex(comboResult);
        }

        // Reset click state at end of frame
        frameMouseClicked = false;
        frameMouseReleased = false;
    }

    private static void handleSplitter(int screenW, int panelX) {
        boolean leftDown = GLFW.glfwGetMouseButton(
                Minecraft.getInstance().getWindow().getWindow(),
                GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;

        if (!splitterDragging && leftDown && Math.abs(frameMouseX - panelX) < 6) {
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

    private static void drawHeaderBanner(GuiGraphics g, Font font, int x, int w, SplineModel model) {
        int bannerH = 34;
        int by = StudioWidgets.getY();
        StudioWidgets.gradientBox(x - 2, by, w + 4, bannerH,
                StudioColors.HEADER_BG_TOP, StudioColors.HEADER_BG_BOTTOM);

        g.drawString(font, I18n.name("gui.wandscape.roadstudio.banner",
                "WANDSCAPE \u9053\u8DEF\u5236\u4F5C\u5DE5\u574A").getString(),
                x + 4, by + 3, StudioColors.TEXT_GOLD);

        String toolName = switch (RoadPlacementState.getActiveTool()) {
            case REPLACE -> I18n.name("gui.wandscape.roadstudio.tool_replace", "\u76F4\u7EBF\u66FF\u6362").getString();
            case FILL -> I18n.name("gui.wandscape.roadstudio.tool_fill", "\u7ACB\u65B9\u4F53\u586B\u5145").getString();
            case DESTROY_FILL -> I18n.name("gui.wandscape.roadstudio.tool_destroy", "\u94F2\u5E73\u57AB\u5E73").getString();
            case SPLINE -> I18n.name("gui.wandscape.roadstudio.tool_spline", "\u6837\u6761\u66F2\u7EBF").getString();
        };
        String viewStr = SplineEditorClientState.isTopDown()
                ? I18n.name("gui.wandscape.roadstudio.view_topdown", "2D \u4FEF\u77B0").getString()
                : I18n.name("gui.wandscape.roadstudio.view_free", "3D \u81EA\u7531").getString();
        g.drawString(font, I18n.name("gui.wandscape.roadstudio.mode_format",
                "\u6A21\u5F0F: %s  |  \u89C6\u89D2: %s", toolName, viewStr).getString(),
                x + 4, by + 16, StudioColors.TEXT_CYAN);

        StudioWidgets.setY(by + bannerH + 4);
    }

    // ════════════════════════════════════════════════════════════════
    //  TOOL MODE SELECTOR (4 buttons)
    // ════════════════════════════════════════════════════════════════

    private static void drawToolModeSelector() {
        StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.mode_title",
                "\u6A21\u5F0F\u9009\u62E9").getString());

        RoadPlacementState.ToolMode current = RoadPlacementState.getActiveTool();
        int y = StudioWidgets.getY();
        int x = StudioWidgets.getLayoutX();
        int totalW = StudioWidgets.getLayoutW();
        int gap = 3;
        int btnW = (totalW - gap * 3) / 4;
        int btnH = 20;

        String[] labels = {
                I18n.name("gui.wandscape.roadstudio.mode_replace", "\u66FF\u6362").getString(),
                I18n.name("gui.wandscape.roadstudio.mode_fill", "\u586B\u5145").getString(),
                I18n.name("gui.wandscape.roadstudio.mode_destroy", "\u94F2\u5E73").getString(),
                I18n.name("gui.wandscape.roadstudio.mode_spline", "\u6837\u6761").getString(),
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
        StudioWidgets.spacing();
    }

    // ════════════════════════════════════════════════════════════════
    //  PRESET COMBO (shared by REPLACE, FILL, ARRAY)
    // ════════════════════════════════════════════════════════════════

    private static void drawPresetCombo() {
        List<RoadPreset> presets = RoadPlacementState.getPresets();
        int currentIdx = RoadPlacementState.getSelectedPresetIndex();
        String[] names = presets.stream()
                .map(p -> I18n.name("gui.wandscape.road.preset." + p.id(), p.displayName()).getString())
                .toArray(String[]::new);

        int newIdx = StudioWidgets.combo("##preset", names, currentIdx, 20);
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
                "\u94FA\u8BBE\u65B9\u5757\u9884\u8BBE").getString());
        drawPresetCombo();

        StudioWidgets.spacing();
        StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.replace_points_header",
                "\u94FA\u8BBE\u8DEF\u7EBF\u8D77\u7EC8\u70B9").getString());

        drawPositionControls(mc, I18n.name("gui.wandscape.roadstudio.start_label",
                "\u8D77\u70B9\u5750\u6807 (Start)").getString(), true);
        StudioWidgets.spacing();
        drawPositionControls(mc, I18n.name("gui.wandscape.roadstudio.end_label",
                "\u7EC8\u70B9\u5750\u6807 (End)").getString(), false);

        // Evaluation
        StudioWidgets.spacing();
        BlockPos start = RoadPlacementState.getStartPos();
        BlockPos end = RoadPlacementState.getEndPos();
        if (start != null && end != null) {
            int dx = Math.abs(end.getX() - start.getX()) + 1;
            int dz = Math.abs(end.getZ() - start.getZ()) + 1;
            double dist = Math.sqrt((double)(end.getX() - start.getX()) * (end.getX() - start.getX())
                    + (double)(end.getZ() - start.getZ()) * (end.getZ() - start.getZ()));
            StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.replace_eval_header",
                    "\u94FA\u8BBE\u6570\u636E\u8BC4\u4F30").getString());
            StudioWidgets.text(I18n.name("gui.wandscape.roadstudio.replace_span",
                    "\u8986\u76D6\u8DE8\u5EA6: %d \u00D7 %d \u65B9\u5757\u8303\u56F4", dx, dz).getString());
            StudioWidgets.text(I18n.name("gui.wandscape.roadstudio.replace_dist",
                    "\u76F4\u7EBF\u8DDD\u79BB: %.1f \u65B9\u5757", dist).getString());
        } else {
            StudioWidgets.textMuted(I18n.name("gui.wandscape.roadstudio.replace_hint",
                    "\u63D0\u793A: \u8BF7\u9009\u62E9\u8D77\u70B9\u4E0E\u7EC8\u70B9").getString());
        }

        StudioWidgets.spacingLarge();
        if (StudioWidgets.buttonFull(
                I18n.name("gui.wandscape.roadstudio.replace_submit",
                        "\u4E0B\u53D1\u76F4\u7EBF\u94FA\u8BBE\u4EFB\u52A1").getString(),
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
                "\u586B\u5145\u65B9\u5757\u9884\u8BBE").getString());
        drawPresetCombo();

        StudioWidgets.spacing();
        StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.fill_corners_header",
                "3D \u7ACB\u65B9\u4F53\u5BF9\u89D2\u70B9").getString());
        drawPositionControls(mc, I18n.name("gui.wandscape.roadstudio.fill_corner1",
                "\u89D2\u70B9 1 (Start)").getString(), true);
        StudioWidgets.spacing();
        drawPositionControls(mc, I18n.name("gui.wandscape.roadstudio.fill_corner2",
                "\u89D2\u70B9 2 (End)").getString(), false);

        StudioWidgets.spacing();
        BlockPos start = RoadPlacementState.getStartPos();
        BlockPos end = RoadPlacementState.getEndPos();
        if (start != null && end != null) {
            int dx = Math.abs(end.getX() - start.getX()) + 1;
            int dy = Math.abs(end.getY() - start.getY()) + 1;
            int dz = Math.abs(end.getZ() - start.getZ()) + 1;
            long volume = (long) dx * dy * dz;
            StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.fill_eval_header",
                    "\u7ACB\u65B9\u4F53\u4F53\u79EF\u8BC4\u4F30").getString());
            StudioWidgets.text(I18n.name("gui.wandscape.roadstudio.fill_size",
                    "\u5C3A\u5BF8: %d (\u5BBD) \u00D7 %d (\u9AD8) \u00D7 %d (\u6DF1)", dx, dy, dz).getString());
            StudioWidgets.text(I18n.name("gui.wandscape.roadstudio.fill_volume",
                    "\u603B\u4F53\u79EF: %d \u4E2A\u65B9\u5757", volume).getString());
        } else {
            StudioWidgets.textMuted(I18n.name("gui.wandscape.roadstudio.fill_hint",
                    "\u63D0\u793A: \u8BF7\u9009\u62E9\u4E24\u4E2A\u5BF9\u89D2\u70B9").getString());
        }

        StudioWidgets.spacingLarge();
        if (StudioWidgets.buttonFull(
                I18n.name("gui.wandscape.roadstudio.fill_submit",
                        "\u4E0B\u53D1\u7ACB\u65B9\u4F53\u586B\u5145\u4EFB\u52A1").getString(),
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
                "\u53C2\u7167\u57FA\u51C6\u65B9\u5757").getString());

        String refBlock = RoadPlacementState.getRefBlockId();
        if (refBlock.isEmpty()) {
            StudioWidgets.textDisabled(I18n.name("gui.wandscape.roadstudio.destroy_no_ref",
                    "\u672A\u6355\u83B7\u53C2\u7167\u65B9\u5757 (\u53F3\u952E\u70B9\u51FB\u65B9\u5757\u6355\u83B7)").getString());
        } else {
            StudioWidgets.textColored(
                    I18n.name("gui.wandscape.roadstudio.destroy_ref_fmt",
                            "\u53C2\u7167\u65B9\u5757: %s", refBlock).getString(),
                    StudioColors.TEXT_GREEN);
        }

        if (StudioWidgets.buttonFull(
                I18n.name("gui.wandscape.roadstudio.destroy_capture_feet",
                        "\u6355\u6349\u811A\u4E0B\u65B9\u5757\u4E3A\u53C2\u7167").getString(),
                18, StudioColors.BUTTON_NORMAL, StudioColors.BUTTON_HOVER)) {
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
                "\u5E73\u6574\u533A\u57DF\u8FB9\u754C").getString());
        drawPositionControls(mc, I18n.name("gui.wandscape.roadstudio.destroy_start",
                "\u8FB9\u754C\u8D77\u70B9 (Start)").getString(), true);
        StudioWidgets.spacing();
        drawPositionControls(mc, I18n.name("gui.wandscape.roadstudio.destroy_end",
                "\u8FB9\u754C\u7EC8\u70B9 (End)").getString(), false);

        StudioWidgets.spacing();
        BlockPos start = RoadPlacementState.getStartPos();
        BlockPos end = RoadPlacementState.getEndPos();
        if (start != null && end != null) {
            int dx = Math.abs(end.getX() - start.getX()) + 1;
            int dz = Math.abs(end.getZ() - start.getZ()) + 1;
            StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.destroy_eval_header",
                    "\u5E73\u6574\u9762\u79EF\u8BC4\u4F30").getString());
            StudioWidgets.text(I18n.name("gui.wandscape.roadstudio.destroy_size",
                    "\u5E95\u9762\u5C3A\u5BF8: %d \u00D7 %d \u65B9\u5757", dx, dz).getString());
            StudioWidgets.text(I18n.name("gui.wandscape.roadstudio.destroy_area",
                    "\u5E73\u6574\u9762\u79EF: %d \u5E73\u65B9\u65B9\u5757", dx * dz).getString());
        } else {
            StudioWidgets.textMuted(I18n.name("gui.wandscape.roadstudio.destroy_hint",
                    "\u63D0\u793A: \u8BF7\u9009\u62E9\u8D77\u70B9\u4E0E\u7EC8\u70B9\u4EE5\u786E\u5B9A\u5E73\u6574\u533A\u57DF").getString());
        }

        StudioWidgets.spacingLarge();
        if (StudioWidgets.buttonFull(
                I18n.name("gui.wandscape.roadstudio.destroy_submit",
                        "\u4E0B\u53D1\u5730\u5F62\u5E73\u6574\u4EFB\u52A1").getString(),
                26, StudioColors.BUTTON_GREEN, StudioColors.BUTTON_GREEN_HOVER)) {
            submitDestroyFill(mc);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  MODE: SPLINE (3 sub-tabs)
    // ════════════════════════════════════════════════════════════════

    private static void drawSplineModeTab(SplineModel model, Minecraft mc) {
        String[] tabs = {
                I18n.name("gui.wandscape.roadstudio.tab_curve", "\u66F2\u7EBF\u7F16\u8F91").getString(),
                I18n.name("gui.wandscape.roadstudio.tab_array", "\u9635\u5217\u751F\u6210").getString(),
                I18n.name("gui.wandscape.roadstudio.tab_templates", "\u6A21\u677F\u4E0E\u5DE5\u5177").getString(),
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
                "\u7F16\u8F91\u6A21\u5F0F\u5207\u6362").getString());

        boolean isAdd = SplineEditorClientState.getEditMode() == SplineEditorClientState.EditMode.ADD;
        int y = StudioWidgets.getY();
        int x = StudioWidgets.getLayoutX();
        int halfW = (StudioWidgets.getLayoutW() - 4) / 2;

        if (StudioWidgets.modeButton(I18n.name("gui.wandscape.roadstudio.curve_add_point",
                "\u70B9\u51FB\u6DFB\u52A0\u70B9").getString(), isAdd, x, y, halfW, 24)) {
            SplineEditorClientState.setEditMode(SplineEditorClientState.EditMode.ADD);
        }
        if (StudioWidgets.modeButton(I18n.name("gui.wandscape.roadstudio.curve_select_drag",
                "\u9009\u62E9\u4E0E\u62D6\u62FD").getString(), !isAdd, x + halfW + 4, y, halfW, 24)) {
            SplineEditorClientState.setEditMode(SplineEditorClientState.EditMode.EDIT);
        }
        StudioWidgets.setY(y + 24 + 6);

        // Curve geometry
        StudioWidgets.spacing();
        StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.curve_geom_header",
                "\u66F2\u7EBF\u51E0\u4F55\u4E0E\u5E73\u79FB").getString());

        boolean closed = model.isClosed();
        if (StudioWidgets.checkbox(I18n.name("gui.wandscape.roadstudio.curve_closed",
                "\u95ED\u5408\u73AF\u5F62\u9053\u8DEF (\u8FDE\u63A5\u9996\u5C3E)").getString(), closed)) {
            model.setClosed(!closed);
        }

        StudioWidgets.spacing();
        StudioWidgets.textMuted(I18n.name("gui.wandscape.roadstudio.curve_shift_label",
                "\u6574\u4F53\u5E73\u79FB\u504F\u79FB\u91CF:").getString());

        // Global shift display + button
        StudioWidgets.text(String.format("  X:%.1f  Y:%.1f  Z:%.1f", globalShiftX, globalShiftY, globalShiftZ));

        if (StudioWidgets.buttonFull(
                I18n.name("gui.wandscape.roadstudio.curve_shift_btn", "\u6574\u4F53\u5E73\u79FB").getString(),
                20, StudioColors.BUTTON_NORMAL, StudioColors.BUTTON_HOVER)) {
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
                "\u63A7\u5236\u70B9\u5217\u8868 (%d)", model.getPoints().size()).getString());

        if (model.getPoints().isEmpty()) {
            StudioWidgets.textDisabled(I18n.name("gui.wandscape.roadstudio.curve_list_empty1",
                    "  \u5F53\u524D\u65E0\u63A7\u5236\u70B9\u3002").getString());
            StudioWidgets.textDisabled(I18n.name("gui.wandscape.roadstudio.curve_list_empty2",
                    "  \u8BF7\u5728\u4E16\u754C\u4E2D\u5DE6\u952E\u70B9\u51FB\u65B9\u5757\u6DFB\u52A0\u3002").getString());
        } else {
            String[] pointLabels = new String[model.getPoints().size()];
            for (int i = 0; i < model.getPoints().size(); i++) {
                SplinePoint pt = model.getPoints().get(i);
                SplineVec3 anchor = pt.getAnchor();
                String symTag = pt.isLocked() ? "[\u5BF9\u79F0]" : "[\u81EA\u7531]";
                pointLabels[i] = String.format("#%d  (%.1f, %.1f, %.1f) %s",
                        i, anchor.x(), anchor.y(), anchor.z(), symTag);
            }

            int selected = SplineEditorClientState.getSelectedPointIndex();
            int clicked = StudioWidgets.selectableList(pointLabels, selected, 100);
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
                "\u8282\u70B9\u5C5E\u6027\u68C0\u67E5\u5668 #%d", selectedIdx).getString());

        // Selection type radio buttons
        int y = StudioWidgets.getY();
        int x = StudioWidgets.getLayoutX();
        int segW = StudioWidgets.getLayoutW() / 3;

        if (StudioWidgets.radioButtonAt(I18n.name("gui.wandscape.roadstudio.curve_anchor",
                "\u4E3B\u951A\u70B9").getString(),
                selectedType == SplineEditorClientState.SelectionType.ANCHOR, x, y)) {
            SplineEditorClientState.setSelectedPoint(selectedIdx, SplineEditorClientState.SelectionType.ANCHOR);
        }
        if (StudioWidgets.radioButtonAt(I18n.name("gui.wandscape.roadstudio.curve_handle_prev",
                "\u524D\u624B\u67C4").getString(),
                selectedType == SplineEditorClientState.SelectionType.CONTROL_PREV, x + segW, y)) {
            SplineEditorClientState.setSelectedPoint(selectedIdx, SplineEditorClientState.SelectionType.CONTROL_PREV);
        }
        if (StudioWidgets.radioButtonAt(I18n.name("gui.wandscape.roadstudio.curve_handle_next",
                "\u540E\u624B\u67C4").getString(),
                selectedType == SplineEditorClientState.SelectionType.CONTROL_NEXT, x + segW * 2, y)) {
            SplineEditorClientState.setSelectedPoint(selectedIdx, SplineEditorClientState.SelectionType.CONTROL_NEXT);
        }
        StudioWidgets.setY(y + 16);

        // Show coordinate of selected handle
        SplineVec3 targetPos = switch (selectedType) {
            case ANCHOR -> pt.getAnchor();
            case CONTROL_PREV -> pt.getControlPrev();
            case CONTROL_NEXT -> pt.getControlNext();
            default -> null;
        };
        if (targetPos != null) {
            StudioWidgets.text(String.format("  X: %.2f  Y: %.2f  Z: %.2f",
                    targetPos.x(), targetPos.y(), targetPos.z()));
        }

        // Symmetry lock
        boolean locked = pt.isLocked();
        if (StudioWidgets.checkbox(I18n.name("gui.wandscape.roadstudio.curve_sym_lock",
                "\u5BF9\u79F0\u5207\u7EBF\u624B\u67C4\u9501\u5B9A").getString(), locked)) {
            pt.setLocked(!locked);
        }

        // Focus button
        int yRow = StudioWidgets.getY();
        int halfW = (StudioWidgets.getLayoutW() - 4) / 2;
        if (StudioWidgets.buttonAt(I18n.name("gui.wandscape.roadstudio.curve_focus",
                "\u89C6\u89D2\u805A\u7126").getString(),
                x, yRow, halfW, 20,
                StudioColors.BUTTON_NORMAL, StudioColors.BUTTON_HOVER, StudioColors.BUTTON_ACTIVE)) {
            SplineVec3 pos = pt.getAnchor();
            SplineEditorClientState.setCamPosition(pos.x(), pos.y() + 3.0, pos.z() + 5.0);
        }

        // Delete button
        if (StudioWidgets.buttonAt(I18n.name("gui.wandscape.roadstudio.curve_delete_btn",
                "\u5220\u9664\u8282\u70B9 #%d", selectedIdx).getString(),
                x + halfW + 4, yRow, halfW, 20,
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
        StudioWidgets.setY(yRow + 20 + 6);
    }

    // ── Sub-tab 2: Array Generation ──

    private static void drawArrayTab(Minecraft mc) {
        StudioWidgets.spacing();
        StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.array_source_header",
                "\u6A21\u677F\u6765\u6E90\u4E0E V \u9762\u677F\u8054\u52A8").getString());

        var sourceMode = SplineEditorClientState.getTemplateSourceMode();
        int y = StudioWidgets.getY();
        int x = StudioWidgets.getLayoutX();
        int halfW = StudioWidgets.getLayoutW() / 2;

        if (StudioWidgets.radioButtonAt(I18n.name("gui.wandscape.roadstudio.array_source_vpanel",
                "V \u9762\u677F\u9884\u8BBE").getString(),
                sourceMode == SplineEditorClientState.TemplateSourceMode.VPANEL_PRESET, x, y)) {
            SplineEditorClientState.setTemplateSourceMode(SplineEditorClientState.TemplateSourceMode.VPANEL_PRESET);
        }
        if (StudioWidgets.radioButtonAt(I18n.name("gui.wandscape.roadstudio.array_source_json",
                "JSON \u9884\u8BBE").getString(),
                sourceMode == SplineEditorClientState.TemplateSourceMode.JSON_FILE, x + halfW, y)) {
            SplineEditorClientState.setTemplateSourceMode(SplineEditorClientState.TemplateSourceMode.JSON_FILE);
        }
        StudioWidgets.setY(y + 16);

        if (sourceMode == SplineEditorClientState.TemplateSourceMode.VPANEL_PRESET) {
            StudioWidgets.spacing();
            StudioWidgets.textMuted(I18n.name("gui.wandscape.roadstudio.array_vpanel_label",
                    "V \u9762\u677F\u9009\u4E2D\u7684\u65B9\u5757:").getString());
            drawPresetCombo();

            StudioWidgets.spacing();
            StudioWidgets.textMuted(I18n.name("gui.wandscape.roadstudio.array_dynamic_header",
                    "\u52A8\u6001\u9053\u8DEF\u89C4\u683C\u751F\u6210\u5668:").getString());

            int width = StudioWidgets.sliderInt("##dynW",
                    I18n.name("gui.wandscape.roadstudio.array_width", "\u9053\u8DEF\u5BBD\u5EA6").getString(),
                    SplineEditorClientState.getDynamicWidth(), 1, 15);
            if (width != SplineEditorClientState.getDynamicWidth()) {
                SplineEditorClientState.setDynamicWidth(width);
            }

            int depth = StudioWidgets.sliderInt("##dynD",
                    I18n.name("gui.wandscape.roadstudio.array_depth", "\u57FA\u5C42\u539A\u5EA6").getString(),
                    SplineEditorClientState.getDynamicDepth(), 1, 3);
            if (depth != SplineEditorClientState.getDynamicDepth()) {
                SplineEditorClientState.setDynamicDepth(depth);
            }

            boolean border = SplineEditorClientState.isDynamicHasBorder();
            if (StudioWidgets.checkbox(I18n.name("gui.wandscape.roadstudio.array_border",
                    "\u8FB9\u7F18\u77F3\u7816\u8FB9\u6846").getString(), border)) {
                SplineEditorClientState.setDynamicHasBorder(!border);
            }
        }

        // Preview toggle
        StudioWidgets.spacing();
        boolean preview = SplineEditorClientState.isArrayPreview();
        if (StudioWidgets.checkbox(I18n.name("gui.wandscape.roadstudio.array_preview",
                "\u9884\u89C8\u9635\u5217\u751F\u6210\u7ED3\u679C").getString(), preview)) {
            SplineEditorClientState.setArrayPreview(!preview);
        }

        // Step distance
        StudioWidgets.spacing();
        StudioWidgets.textMuted(I18n.name("gui.wandscape.roadstudio.array_step_label",
                "\u91C7\u6837\u53C2\u6570\u8BBE\u7F6E:").getString());

        float stepDist = StudioWidgets.sliderFloat("##step",
                I18n.name("gui.wandscape.roadstudio.array_step", "\u91C7\u6837\u6B65\u8DDD").getString(),
                (float) SplineEditorClientState.getArrayStepDistance(), 0.5f, 8.0f, "%.1f");
        SplineEditorClientState.setArrayStepDistance(stepDist);

        // Rotation
        StudioWidgets.spacing();
        StudioWidgets.textMuted(I18n.name("gui.wandscape.roadstudio.array_rot_label",
                "3D \u9635\u5217\u59FF\u6001\u65CB\u8F6C\u5FAE\u8C03:").getString());

        float roll = StudioWidgets.sliderFloat("##roll",
                I18n.name("gui.wandscape.roadstudio.array_roll", "\u6EDA\u52A8\u89D2 Roll").getString(),
                (float) SplineEditorClientState.getArrayOffsetRoll(), -180f, 180f, "%.1f\u00B0");
        SplineEditorClientState.setArrayOffsetRoll(roll);

        float pitch = StudioWidgets.sliderFloat("##pitch",
                I18n.name("gui.wandscape.roadstudio.array_pitch", "\u4FEF\u4EF0\u89D2 Pitch").getString(),
                (float) SplineEditorClientState.getArrayOffsetPitch(), -180f, 180f, "%.1f\u00B0");
        SplineEditorClientState.setArrayOffsetPitch(pitch);

        float yaw = StudioWidgets.sliderFloat("##yaw",
                I18n.name("gui.wandscape.roadstudio.array_yaw", "\u504F\u822A\u89D2 Yaw").getString(),
                (float) SplineEditorClientState.getArrayOffsetYaw(), -180f, 180f, "%.1f\u00B0");
        SplineEditorClientState.setArrayOffsetYaw(yaw);

        // Reset button
        if (StudioWidgets.buttonFull("0\u00B0 \u91CD\u7F6E", 18,
                StudioColors.BUTTON_NORMAL, StudioColors.BUTTON_HOVER)) {
            SplineEditorClientState.setArrayOffsetRoll(0);
            SplineEditorClientState.setArrayOffsetPitch(0);
            SplineEditorClientState.setArrayOffsetYaw(0);
        }

        // Build button
        StudioWidgets.spacingLarge();
        if (StudioWidgets.buttonFull(
                I18n.name("gui.wandscape.roadstudio.array_build",
                        "\u4E0B\u53D1\u9053\u8DEF\u5EFA\u9020\u4EFB\u52A1").getString(),
                26, StudioColors.BUTTON_GREEN, StudioColors.BUTTON_GREEN_HOVER)) {
            SplineEditorController.doBuildArray();
        }
    }

    // ── Sub-tab 3: Templates & Tools ──

    private static void drawTemplatesTab(SplineModel model, Minecraft mc) {
        StudioWidgets.spacing();
        StudioWidgets.sectionHeader(I18n.name("gui.wandscape.roadstudio.tpl_header",
                "\u6A21\u677F\u6587\u4EF6\u7BA1\u7406").getString());

        // Template name display (no text input yet — use /wandscape command)
        StudioWidgets.textMuted(I18n.name("gui.wandscape.roadstudio.tpl_name_hint",
                "\u8F93\u5165\u6A21\u677F\u540D\u79F0 (cli: /wandscape spline)").getString());

        int y = StudioWidgets.getY();
        int x = StudioWidgets.getLayoutX();
        int halfW = (StudioWidgets.getLayoutW() - 4) / 2;

        // Save / Load buttons
        if (StudioWidgets.buttonAt(I18n.name("gui.wandscape.roadstudio.tpl_save",
                "\u4FDD\u5B58 JSON \u6A21\u677F").getString(),
                x, y, halfW, 22,
                StudioColors.BUTTON_BLUE, StudioColors.BUTTON_HOVER, StudioColors.BUTTON_ACTIVE)) {
            // Default name save
            SplineEditorClientState.saveTemplate("native_export");
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(
                        "\u00a7aSaved to config/wandscape/splines/native_export.json"), true);
            }
        }
        if (StudioWidgets.buttonAt(I18n.name("gui.wandscape.roadstudio.tpl_load",
                "\u8BFB\u53D6 JSON \u6A21\u677F").getString(),
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
                "\u89C6\u56FE\u4E0E\u5FEB\u6377\u5DE5\u5177").getString());

        boolean topDown = SplineEditorClientState.isTopDown();
        String topDownLabel = topDown
                ? I18n.name("gui.wandscape.roadstudio.tpl_exit_topdown",
                "\u9000\u51FA 2D \u4FEF\u77B0\u89C6\u89D2 (G)").getString()
                : I18n.name("gui.wandscape.roadstudio.tpl_enter_topdown",
                "\u5207\u6362 2D \u4FEF\u77B0\u89C6\u89D2 (G)").getString();

        if (StudioWidgets.buttonFull(topDownLabel, 22,
                StudioColors.BUTTON_NORMAL, StudioColors.BUTTON_HOVER)) {
            if (topDown) SplineEditorClientState.exitTopDown();
            else SplineEditorClientState.enterTopDown();
        }

        if (StudioWidgets.buttonFull(
                I18n.name("gui.wandscape.roadstudio.tpl_help",
                        "\u6253\u5F00\u64CD\u4F5C\u6307\u5357 (H)").getString(),
                22, StudioColors.BUTTON_NORMAL, StudioColors.BUTTON_HOVER)) {
            String content = com.wsteam.wandscape.shared.ui.markdown.navigation.DocumentLoader.loadMarkdown("road_spline_guide");
            mc.setScreen(new com.wsteam.wandscape.shared.ui.guide.GuideTestScreen(null, content, "road_spline_guide"));
        }

        StudioWidgets.spacing();
        y = StudioWidgets.getY();
        halfW = (StudioWidgets.getLayoutW() - 4) / 2;

        if (StudioWidgets.buttonAt(I18n.name("gui.wandscape.roadstudio.tpl_clear",
                "\u6E05\u7A7A\u753B\u5E03").getString(),
                StudioWidgets.getLayoutX(), y, halfW, 22,
                StudioColors.BUTTON_NORMAL, StudioColors.BUTTON_HOVER, StudioColors.BUTTON_ACTIVE)) {
            model.clear();
            SplineEditorClientState.setSelectedPoint(-1, SplineEditorClientState.SelectionType.NONE);
        }

        if (StudioWidgets.buttonAt(I18n.name("gui.wandscape.roadstudio.tpl_close",
                "\u5173\u95ED Studio").getString(),
                StudioWidgets.getLayoutX() + halfW + 4, y, halfW, 22,
                StudioColors.BUTTON_RED, StudioColors.BUTTON_RED_HOVER, StudioColors.BUTTON_RED_HOVER)) {
            SplineEditorClientState.exitEditMode();
            setVisible(false);
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
                " [\u53F3\u952E\u6309\u4F4F] \u65CB\u8F6C\u89C6\u89D2 | [G] \u4FEF\u77B0 | [H] \u6307\u5357 | [ESC] \u9000\u51FA").getString(),
                panelX + PAD, footerY + 3, StudioColors.TEXT_MUTED);
    }

    // ════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════

    private static void drawPositionControls(Minecraft mc, String label, boolean isStart) {
        BlockPos pos = isStart ? RoadPlacementState.getStartPos() : RoadPlacementState.getEndPos();
        int result = StudioWidgets.positionRow(label, pos);
        if (result == 1) {
            // Clear
            if (isStart) RoadPlacementState.clearStartPos();
            else RoadPlacementState.clearEndPos();
        } else if (result == 2) {
            // Capture
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
            mc.player.displayClientMessage(Component.literal("[Road] \u00a7aRoad task submitted!"), true);
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
            mc.player.displayClientMessage(Component.literal("[Fill] \u00a7aFill task submitted!"), true);
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
            mc.player.displayClientMessage(Component.literal("[Destroy/Fill] \u00a7aTerrain flatten task submitted!"), true);
        }
        RoadPlacementState.clearAll();
    }
}
