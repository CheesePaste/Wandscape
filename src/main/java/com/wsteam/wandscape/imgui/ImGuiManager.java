package com.wsteam.wandscape.imgui;

import com.wsteam.wandscape.road.client.SplineEditorClientState;
import com.wsteam.wandscape.road.client.SplineEditorImGui;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.ui.panel.WandscapePanelState;

import imgui.ImFont;
import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;

import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import org.lwjgl.glfw.GLFW;

public class ImGuiManager {
    private static final ImGuiImplGlfw imGuiGlfw = new ImGuiImplGlfw();
    private static final ImGuiImplGl3 imGuiGl3 = new ImGuiImplGl3();

    private static boolean initialized = false;
    private static volatile boolean showGui = false;
    private static volatile boolean pendingShowGui = false;

    public static void register() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(ImGuiManager.class);
    }

    public static boolean wantsMouse() {
        if (!showGui || WandscapePanelState.isPanelHidden() || !initialized) return false;
        return ImGui.getIO().getWantCaptureMouse();
    }

    public static boolean wantsKeyboard() {
        if (!showGui || WandscapePanelState.isPanelHidden() || !initialized) return false;
        return ImGui.getIO().getWantCaptureKeyboard();
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
        showGui = visible;
        if (visible) {
            releaseMouse();
        }
        // 隐藏时不抢鼠标：退出编辑器后面板（overlay）可能仍开着，光标状态由面板管线决定。
        // toggle()（F12）保留对称的 grab/release。
    }

    private static boolean fontsBaked = false;
    private static boolean backendInitialized = false;

    public static void initFontsOnly() {
        if (fontsBaked) return;

        ImGui.createContext();

        // ── Chinese CJK Glyph Ranges ──
        // NOTE: ImGui.getIO().getFonts().getGlyphRangesChineseSimplifiedCommon()
        // is BROKEN in imgui-java 1.86.10 (SpaiR/imgui-java issue #70): CJK
        // codepoints (0x4E00…) exceed the signed-short range, so the JNI
        // return truncates them and the array carries no CJK ranges at all →
        // Chinese renders as "?????" no matter what font is loaded. Build the
        // range array by hand with explicit (short) casts to preserve the
        // unsigned bit pattern (exactly what C++ ImWchar16 expects).
        short[] cjkRanges = new short[]{
                (short) 0x0020, (short) 0x00FF, // Basic Latin + Latin Supplement
                (short) 0x2000, (short) 0x206F, // General Punctuation
                (short) 0x3000, (short) 0x30FF, // CJK Symbols and Punctuations, Hiragana, Katakana
                (short) 0x31F0, (short) 0x31FF, // Katakana Phonetic Extensions
                (short) 0xFF00, (short) 0xFFEF, // Half-width characters
                (short) 0xFFFD, (short) 0xFFFD, // Invalid
                (short) 0x4E00, (short) 0x9FFF, // CJK Unified Ideographs (all 20k+ chars)
                0,
        };

        ImFontConfig fontConfig = new ImFontConfig();
        fontConfig.setOversampleH(2);
        fontConfig.setOversampleV(2);
        fontConfig.setPixelSnapH(true);
        // Keep the array alive for the whole font lifetime: the C++ side stores
        // a pointer to it and only reads it at build(). Holding it via the
        // config's Java field prevents the array from being GC'd.
        fontConfig.setGlyphRanges(cjkRanges);

        // ── 主字体：内置 SimHei(chinese.ttf)，直接从资源字节内存加载 ──
        // 旧实现「解压到临时文件 + addFontFromFileTTF」：若 C++ fopen 打开失败，
        // imgui 原生断言（imAssertCallback 打印后 __debugbreak）会直接杀死进程，
        // Java 的 catch(Exception) 根本拦不住，整个兜底链形同虚设。
        // addFontFromMemoryTTF 完全绕开文件路径，内置字体字节打包时已验证有效。
        byte[] cjkData = loadResourceFont("fonts/chinese.ttf");
        if (cjkData != null && cjkData.length > 0) {
            ImFont f = ImGui.getIO().getFonts().addFontFromMemoryTTF(cjkData, 20.0f, fontConfig);
            if (f == null || f.ptr == 0) {
                Log.warn("ImGui", "[Font] SimHei memory load returned null, falling back to default font");
                ImGui.getIO().getFonts().addFontDefault();
            }
        } else {
            Log.warn("ImGui", "[Font] fonts/chinese.ttf resource missing, using default font");
            ImGui.getIO().getFonts().addFontDefault();
        }
        fontConfig.destroy();

        // ── FontAwesome 图标合并（道路编辑器 icon 字形，U+E000–U+F8FF）──
        byte[] faData = loadResourceFont("fonts/fa-solid-900.ttf");
        if (faData != null && faData.length > 0) {
            ImFontConfig iconConfig = new ImFontConfig();
            iconConfig.setMergeMode(true);
            iconConfig.setPixelSnapH(true);
            iconConfig.setOversampleH(2);
            iconConfig.setOversampleV(2);
            iconConfig.setGlyphRanges(new short[]{ (short)0xe000, (short)0xf8ff, 0 });
            ImGui.getIO().getFonts().addFontFromMemoryTTF(faData, 17.0f, iconConfig);
            iconConfig.destroy();
        }

        ImGui.getIO().getFonts().build();

        // ── Apply Wandscape Medieval-RTS UI Theme ──
        WandscapeImGuiTheme.apply();

        fontsBaked = true;
        Log.info("ImGui", "[Font] Font atlas baked (SimHei CJK + FontAwesome icons)");
    }

    public static void ensureBackendInit(long windowHandle) {
        initFontsOnly();

        if (backendInitialized) return;
        if (windowHandle == 0L) return;

        imGuiGlfw.init(windowHandle, true);
        imGuiGl3.init("#version 150");

        backendInitialized = true;
        initialized = true;
        Log.info("Wandscape", "ImGui GLFW & GL3 backend hooked successfully");
    }

    public static void init(long windowHandle) {
        ensureBackendInit(windowHandle);
    }

    /** 从 mod 资源包读取字体字节（内存加载，不写临时文件）。 */
    private static byte[] loadResourceFont(String path) {
        try {
            var resource = Minecraft.getInstance().getResourceManager()
                    .getResource(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.wsteam.wandscape.Wandscape.MODID, path));
            if (resource.isPresent()) {
                try (java.io.InputStream is = resource.get().open()) {
                    return is.readAllBytes();
                }
            }
            Log.warn("ImGui", "[Font] Resource font not found: " + path);
        } catch (Exception e) {
            Log.error("ImGui", "Failed to read resource font " + path, e);
        }
        return null;
    }

    private static void ensureInit() {
        if (backendInitialized) return;
        long window = Minecraft.getInstance().getWindow().getWindow();
        if (window != 0L) {
            ensureBackendInit(window);
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
        if (!showGui || WandscapePanelState.isPanelHidden() || !initialized) return;

        if (event.getKey() == GLFW.GLFW_KEY_F12 && event.getAction() == GLFW.GLFW_PRESS) {
            if (anyEditorActive()) return;
            toggle();
            return;
        }
    }

    @SubscribeEvent
    public static void onMouseClick(InputEvent.MouseButton.Pre event) {
        if (!showGui || WandscapePanelState.isPanelHidden() || !initialized) return;
        // When a vanilla MC screen is open (e.g. the spline guide document),
        // let the screen handle its own clicks (close button, back/forward…).
        // ImGui already processed the event at the GLFW layer, so canceling
        // here would only strip vanilla of the click.
        if (Minecraft.getInstance().screen != null) return;
        if (anyEditorActive()) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        if (!showGui || WandscapePanelState.isPanelHidden() || !initialized) return;
        if (Minecraft.getInstance().screen != null) return;
        if (anyEditorActive()) {
            event.setCanceled(true);
            return;
        }
        if (!ImGui.getIO().getWantCaptureMouse()) event.setCanceled(true);
    }

    // ── Render ──
    @SubscribeEvent
    public static void onRenderFramePost(RenderFrameEvent.Post event) {
        var mc = Minecraft.getInstance();

        // 纯 CPU 字体图集预热：在进入游戏世界后（mc.level != null），静默解压字体并由 FreeType 烘焙 20,000+ 汉字 Atlas。
        // 此步骤只消耗 CPU 内存/计算，完全不调用 imGuiGlfw.init(...)，零触碰 GLFW 窗口回调与 OpenGL Shaders，因此 100% 绝对零崩溃！
        if (!fontsBaked && mc != null && mc.level != null) {
            initFontsOnly();
        }

        if (pendingShowGui) {
            pendingShowGui = false;
            ensureInit();
            showGui = true;
            releaseMouse();
        }

        if (!showGui) return;
        if (WandscapePanelState.isPanelHidden()) return;
        ensureInit();

        imGuiGlfw.newFrame();
        ImGui.newFrame();

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

        if (ImGui.begin("Wandscape 调试控制台")) {
            ImGui.text("ImGui 集成测试");
            ImGui.separator();

            var io = ImGui.getIO();
            ImGui.text(String.format("帧率 FPS: %.1f", io.getFramerate()));
            ImGui.text(String.format("捕获鼠标: %b", io.getWantCaptureMouse()));
            ImGui.text(String.format("捕获键盘: %b", io.getWantCaptureKeyboard()));

            ImGui.separator();
            if (ImGui.button("测试按钮")) {
                Minecraft.getInstance().player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("[ImGui] 按钮被点击!"), true);
            }
            ImGui.sameLine();
            if (ImGui.button("关闭")) {
                toggle();
            }
            ImGui.text("按 F12 键切换显示");

            var activity = (float) (Math.sin(System.currentTimeMillis() / 1000.0) * 0.5 + 0.5);
            ImGui.progressBar(activity, 200, 0f, "系统运行度");
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