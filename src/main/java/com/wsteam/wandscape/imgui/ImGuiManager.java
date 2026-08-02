package com.wsteam.wandscape.imgui;

import com.wsteam.wandscape.road.client.SplineEditorClientState;
import com.wsteam.wandscape.road.client.SplineEditorImGui;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;

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
    private static volatile boolean pendingShowGui = false;

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

        // ── Fonts ──
        imgui.ImFontConfig fontConfig = new imgui.ImFontConfig();
        fontConfig.setOversampleH(2);
        fontConfig.setOversampleV(2);

        byte[] robotoBytes = loadFontBytes("fonts/Roboto-Regular.ttf");
        if (robotoBytes != null) {
            ImGui.getIO().getFonts().addFontFromMemoryTTF(robotoBytes, 18.0f, fontConfig);
        } else {
            ImGui.getIO().getFonts().addFontDefault();
            ImGui.getIO().setFontGlobalScale(1.4f);
        }

        byte[] faBytes = loadFontBytes("fonts/fa-solid-900.ttf");
        if (faBytes != null) {
            imgui.ImFontConfig iconConfig = new imgui.ImFontConfig();
            iconConfig.setMergeMode(true);
            iconConfig.setPixelSnapH(true);
            iconConfig.setOversampleH(2);
            iconConfig.setOversampleV(2);
            short[] iconRanges = new short[]{ (short)0xe000, (short)0xf8ff, 0 };
            ImGui.getIO().getFonts().addFontFromMemoryTTF(faBytes, 16.0f, iconConfig, iconRanges);
            iconConfig.destroy();
        }

        fontConfig.destroy();
        ImGui.getIO().getFonts().build();

        // ── Apply modern dark theme ──
        imgui.ImGuiStyle style = ImGui.getStyle();
        style.setWindowRounding(6.0f);
        style.setFrameRounding(4.0f);
        style.setPopupRounding(4.0f);
        style.setScrollbarRounding(4.0f);
        style.setGrabRounding(4.0f);
        style.setTabRounding(4.0f);

        style.setWindowPadding(12.0f, 12.0f);
        style.setFramePadding(8.0f, 4.0f);
        style.setItemSpacing(8.0f, 8.0f);
        style.setWindowBorderSize(0.0f);
        style.setFrameBorderSize(0.0f);

        imGuiGlfw.init(windowHandle, true);
        imGuiGl3.init("#version 150");

        initialized = true;
        Log.info("Wandscape", "ImGui initialized successfully");
    }

    private static byte[] loadFontBytes(String path) {
        try {
            var resource = net.minecraft.client.Minecraft.getInstance().getResourceManager()
                    .getResource(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.wsteam.wandscape.Wandscape.MODID, path));
            if (resource.isPresent()) {
                try (java.io.InputStream is = resource.get().open()) {
                    return is.readAllBytes();
                }
            }
        } catch (Exception e) {
            com.wsteam.wandscape.shared.log.Log.error("ImGui", "Failed to load font " + path, e);
        }
        return null;
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

    public static boolean anyEditorActive() {
        return SplineEditorClientState.isEditing();
    }

    // ── Input interception ──
    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (!showGui || !initialized) return;

        if (event.getKey() == GLFW.GLFW_KEY_F12 && event.getAction() == GLFW.GLFW_PRESS) {
            // While an editor is active, F12 must not hide ImGui: the editor
            // owns mouse blocking and would start leaking input to the game.
            if (anyEditorActive()) return;
            toggle();
            return;
        }
    }

    @SubscribeEvent
    public static void onMouseClick(InputEvent.MouseButton.Pre event) {
        if (!showGui || !initialized) return;
        // While an editor is active, the editor owns every mouse button.
        // ImGui already processed the event at the GLFW callback layer, so
        // canceling here only stops vanilla: no item use, no attack, and no
        // re-grab of the cursor when left-clicking the world.
        if (anyEditorActive()) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!showGui || !initialized) return;
        // Scroll belongs to ImGui over its panels and to the editor camera
        // elsewhere; vanilla must never see it (no hotbar switching).
        if (anyEditorActive()) {
            event.setCanceled(true);
            return;
        }
        if (!ImGui.getIO().getWantCaptureMouse()) event.setCanceled(true);
    }

    // ── Render ──
    @SubscribeEvent
    public static void onRenderFramePost(RenderFrameEvent.Post event) {
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

        // Minecraft draws into its mainRenderTarget FBO, whose size matches the GLFW
        // window's LOGICAL size (MainTarget is created with window.getWidth()/getHeight()).
        // ImGuiImplGlfw however sets DisplayFramebufferScale to fbSize/winSize (e.g. 1.5
        // with 150% Windows DPI scaling), so ImGui would project onto a canvas larger
        // than the FBO we actually draw into, clipping the panel's bottom/right edge.
        // Force 1:1 — Minecraft's own blitToScreen handles the physical scaling.
        ImGui.getIO().setDisplayFramebufferScale(1.0f, 1.0f);

        if (SplineEditorClientState.isEditing()) {
            SplineEditorImGui.render();
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