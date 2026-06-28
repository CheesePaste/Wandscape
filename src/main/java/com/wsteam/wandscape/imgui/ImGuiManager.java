package com.wsteam.wandscape.imgui;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.blueprint.editor.BlueprintEditorClientState;
import com.wsteam.wandscape.blueprint.editor.BlueprintEditorImGui;
import com.wsteam.wandscape.building.editor.BuildingEditorClientState;
import com.wsteam.wandscape.building.editor.BuildingEditorImGui;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.extension.nodeditor.NodeEditor;
import imgui.extension.nodeditor.NodeEditorConfig;
import imgui.extension.nodeditor.NodeEditorContext;

import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import org.lwjgl.glfw.GLFW;
import com.wsteam.wandscape.shared.log.Log;

public class ImGuiManager {
    private static final ImGuiImplGlfw imGuiGlfw = new ImGuiImplGlfw();
    private static final ImGuiImplGl3 imGuiGl3 = new ImGuiImplGl3();

    private static boolean initialized = false;
    private static volatile boolean showGui = false;

    // ── Blueprint editor ──
    private static NodeEditorContext blueprintContext = null;

    // 不再使用懒加载，直接返回已经初始化好的上下文
    public static NodeEditorContext getBlueprintContext() {
        return blueprintContext;
    }

    public static boolean isBlueprintEditorActive() {
        return BlueprintEditorClientState.isEditing();
    }

    // ── Deferred toggle ──
    private static volatile boolean pendingBlueprintToggle = false;
    private static volatile boolean pendingShowGui = false;

    public static boolean toggleBlueprintEditor() {
        if (BlueprintEditorClientState.isEditing()) {
            BlueprintEditorClientState.exitEditMode();
            return false;
        }
        pendingBlueprintToggle = true;
        pendingShowGui = true;
        return true;
    }

    public static boolean isVisible() {
        return showGui;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static void setVisible(boolean visible) {
        if (showGui == visible) return;
        ensureInit();
        toggle();
    }

    public static void init(long windowHandle) {
        if (initialized) return;

        ImGui.createContext();

        // Larger font for readability
        ImGui.getIO().setFontGlobalScale(1.6f);

        imGuiGlfw.init(windowHandle, true);
        imGuiGl3.init("#version 150");

        // 【修复点】：在这里直接创建 NodeEditor 上下文！
        // 此时 ImGui 环境已就绪，但尚未开始渲染第一帧 (newFrame 还没被调用)
        NodeEditorConfig config = new NodeEditorConfig();
        config.setSettingsFile(null); // 禁用生成外部配置文件
        blueprintContext = NodeEditor.createEditor(config);

        // Forward window handle to blueprint editor for keyboard shortcuts
        com.wsteam.wandscape.blueprint.editor.BlueprintEditorImGui.setWindowHandle(windowHandle);

        initialized = true;
        Log.info("Wandscape", "ImGui initialized successfully");
    }

    private static void ensureInit() {
        if (initialized) return;
        long window = Minecraft.getInstance().getWindow().getWindow();
        if (window != 0L) {
            init(window);
        }
    }

    public static void toggle() {
        showGui = !showGui;
        if (showGui) {
            releaseMouse();
        } else {
            grabMouse();
        }
    }

    private static void releaseMouse() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.mouseHandler != null) {
            mc.mouseHandler.releaseMouse();
        }
    }

    private static void grabMouse() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.mouseHandler != null) {
            mc.mouseHandler.grabMouse();
        }
    }

    // ── Input blocking ──
    private static boolean anyEditorActive() {
        return BuildingEditorClientState.isEditing() || BlueprintEditorClientState.isEditing();
    }

    @SubscribeEvent
    public static void onMouseButtonPre(InputEvent.MouseButton.Pre event) {
        if (!showGui || !initialized) return;
        if (!anyEditorActive()) return;

        int btn = event.getButton();
        if (btn != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!showGui || !initialized) return;
        if (!anyEditorActive()) return;
        if (!ImGui.getIO().getWantCaptureMouse()) {
            event.setCanceled(true);
        }
    }

    // ── Render ──
    @SubscribeEvent
    public static void onRenderFramePost(RenderFrameEvent.Post event) {
        if (pendingBlueprintToggle) {
            pendingBlueprintToggle = false;
            ensureInit();
            showGui = true;
            releaseMouse();
            BlueprintEditorClientState.enterEditMode();
        }
        if (pendingShowGui) {
            pendingShowGui = false;
            ensureInit();
            showGui = true;
            releaseMouse();
        }

        if (!showGui) return;
        ensureInit();

        imGuiGlfw.newFrame();
        ImGui.newFrame();

        if (BuildingEditorClientState.isEditing()) {
            BuildingEditorImGui.render();
        } else if (BlueprintEditorClientState.isEditing()) {
            BlueprintEditorImGui.render(getBlueprintContext()); // 这里现在一定是非 null 的
        } else {
            drawDebugGui();
        }

        ImGui.render();
        imGuiGl3.renderDrawData(ImGui.getDrawData());
    }

    private static void drawDebugGui() {
        ImGui.setNextWindowPos(20, 20, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(360, 200, ImGuiCond.FirstUseEver);

        if (ImGui.begin("Wandscape Debug")) {
            ImGui.text("ImGui integration test");
            ImGui.separator();

            var io = ImGui.getIO();
            ImGui.text(String.format("FPS: %.1f", io.getFramerate()));
            ImGui.text(String.format("Capture Mouse: %b", io.getWantCaptureMouse()));
            ImGui.text(String.format("Capture Keyboard: %b", io.getWantCaptureKeyboard()));

            ImGui.separator();
            if (ImGui.button("Test Button")) {
                Minecraft.getInstance().player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("[ImGui] Button clicked!"), true);
            }
            ImGui.sameLine();
            if (ImGui.button("Close")) {
                toggle();
            }
            ImGui.text("Press F12 to toggle");

            var activity = (float) (Math.sin(System.currentTimeMillis() / 1000.0) * 0.5 + 0.5);
            ImGui.progressBar(activity, 200, 0f, "Activity");
        }
        ImGui.end();
    }

    // ── Lifecycle ──
    public static void shutdown() {
        if (!initialized) return;
        showGui = false;

        // 【修复点】：优雅销毁 NodeEditorContext 避免内存泄漏
        if (blueprintContext != null) {
            NodeEditor.destroyEditor(blueprintContext);
            blueprintContext = null;
        }

        imGuiGl3.dispose();
        imGuiGlfw.dispose();
        ImGui.destroyContext();
        initialized = false;
    }
}