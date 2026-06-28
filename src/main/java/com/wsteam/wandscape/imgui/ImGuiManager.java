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

    /**
     * Lazy-init the node editor context AFTER ImGui.createContext() has been called.
     * Critical: if we create it in a static initializer, the native side binds to a
     * wrong/null ImGui context and the canvas renders transparent.
     */
    private static NodeEditorContext getBlueprintContext() {
        if (blueprintContext == null) {
            NodeEditorConfig config = new NodeEditorConfig();
            config.setSettingsFile(null);
            blueprintContext = NodeEditor.createEditor(config);
        }
        return blueprintContext;
    }

    public static boolean isBlueprintEditorActive() {
        return BlueprintEditorClientState.isEditing();
    }

    /** Toggle the blueprint node editor. Returns the new state (true = active). */
    public static boolean toggleBlueprintEditor() {
        if (BlueprintEditorClientState.isEditing()) {
            BlueprintEditorClientState.exitEditMode();
            return false;
        }
        ensureInit(); // must init before entering edit mode (mirrors toggle()/setVisible())
        showGui = true;
        BlueprintEditorClientState.enterEditMode();
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
        ensureInit(); // must init before toggle so Controller can call ImGui.getIO()
        toggle();
    }

    public static void init(long windowHandle) {
        if (initialized) return;

        ImGui.createContext();

        // Larger font for readability
        ImGui.getIO().setFontGlobalScale(1.6f);

        imGuiGlfw.init(windowHandle, true);
        imGuiGl3.init("#version 150");

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
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.mouseHandler != null) {
            if (showGui) {
                mc.mouseHandler.releaseMouse();
            } else {
                mc.mouseHandler.grabMouse();
            }
        }
    }

    // ── Input blocking ──
    // When the editor is active, ALL mouse events are blocked.
    // Only right-click passes through (for camera rotation in Controller).
    // Keyboard is also blocked — Controller reads raw GLFW for WASD.

    @SubscribeEvent
    public static void onMouseButtonPre(InputEvent.MouseButton.Pre event) {
        if (!showGui || !initialized) return;
        if (!BuildingEditorClientState.isEditing()) return;

        // In editor mode: block everything except right-click (button 1)
        int btn = event.getButton();
        if (btn != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            event.setCanceled(true);
        }
        // Right-click (button 1) passes through to MC for camera rotation
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!showGui || !initialized) return;
        if (!BuildingEditorClientState.isEditing()) return;
        // Block scroll from MC hotbar cycling
        if (!ImGui.getIO().getWantCaptureMouse()) {
            event.setCanceled(true);
        }
    }

    // ── Render ──

    @SubscribeEvent
    public static void onRenderFramePost(RenderFrameEvent.Post event) {
        if (!showGui) return;
        ensureInit();

        imGuiGlfw.newFrame();
        ImGui.newFrame();

        if (BuildingEditorClientState.isEditing()) {
            BuildingEditorImGui.render();
        } else if (BlueprintEditorClientState.isEditing()) {
            BlueprintEditorImGui.render(getBlueprintContext());
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
        imGuiGl3.dispose();
        imGuiGlfw.dispose();
        ImGui.destroyContext();
        initialized = false;
    }
}
