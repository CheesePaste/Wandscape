package com.wsteam.wandscape.standalone;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.extension.nodeditor.NodeEditor;
import imgui.extension.nodeditor.NodeEditorConfig;
import imgui.extension.nodeditor.NodeEditorContext;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import com.wsteam.wandscape.blueprint.editor.BlueprintEditorCanvas;
import com.wsteam.wandscape.blueprint.editor.BlueprintEditorClientState;
import com.wsteam.wandscape.blueprint.editor.BlueprintEditorImGui;
import com.wsteam.wandscape.blueprint.editor.BlueprintEditorNetwork;
import com.wsteam.wandscape.task.engine.dsl.BlueprintDefinition;
import com.wsteam.wandscape.shared.log.Log;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Standalone blueprint editor launcher — no Minecraft required.
 *
 * <p>Creates a plain GLFW window, initializes ImGui + NodeEditor,
 * and runs the blueprint node editor in isolation for rapid UI development.
 *
 * <p>Run with: {@code ./gradlew runEditor}
 */
public final class EditorStandalone {

    private static final int WINDOW_WIDTH = 1400;
    private static final int WINDOW_HEIGHT = 900;
    private static final String WINDOW_TITLE = "Wandscape Blueprint Editor (Standalone)";

    private final ImGuiImplGlfw imGuiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3 imGuiGl3 = new ImGuiImplGl3();
    private NodeEditorContext nodeEditorCtx;

    private long window;

    public static void main(String[] args) {
        String blueprintName = args.length > 0 ? args[0] : "build_place_structure.json";
        new EditorStandalone().run(blueprintName);
    }

    private void run(String blueprintName) {
        initGlfw();
        initImGui();
        initNodeEditor();

        // Load demo blueprint
        loadDemoBlueprint(blueprintName);

        // Enter edit mode
        BlueprintEditorClientState.enterEditMode();

        // Main loop
        loop();

        // Shutdown
        BlueprintEditorClientState.exitEditMode();
        shutdown();
    }

    // ═══════════════════════════════════════════════════════════════
    // Init
    // ═══════════════════════════════════════════════════════════════

    private void initGlfw() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("Failed to initialize GLFW");
        }

        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);

        window = GLFW.glfwCreateWindow(WINDOW_WIDTH, WINDOW_HEIGHT, WINDOW_TITLE,
                MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) {
            throw new IllegalStateException("Failed to create GLFW window");
        }

        GLFW.glfwMakeContextCurrent(window);
        GLFW.glfwSwapInterval(1); // vsync
        GLFW.glfwShowWindow(window);
        GL.createCapabilities();  // init OpenGL bindings

        // Forward window handle for keyboard shortcuts
        BlueprintEditorImGui.setWindowHandle(window);

        Log.info("EditorStandalone", "GLFW window created: {}x{}", WINDOW_WIDTH, WINDOW_HEIGHT);
    }

    private void initImGui() {
        ImGui.createContext();
        ImGuiIO io = ImGui.getIO();
        io.setFontGlobalScale(1.3f);

        imGuiGlfw.init(window, true);
        imGuiGl3.init("#version 150");

        Log.info("EditorStandalone", "ImGui initialized");
    }

    private void initNodeEditor() {
        NodeEditorConfig config = new NodeEditorConfig();
        config.setSettingsFile(null);
        nodeEditorCtx = NodeEditor.createEditor(config);
        Log.info("EditorStandalone", "NodeEditor initialized");
    }

    // ═══════════════════════════════════════════════════════════════
    // Demo data
    // ═══════════════════════════════════════════════════════════════

    private void loadDemoBlueprint(String resourcePath) {
        String json = loadJsonFromResources(resourcePath);
        if (json != null && !json.isBlank()) {
            BlueprintDefinition def = BlueprintEditorNetwork.jsonToDefinition(json);
            if (def != null) {
                BlueprintEditorCanvas canvas = BlueprintEditorCanvas.fromDefinition(def);
                BlueprintEditorClientState.setCanvas(canvas);
                Log.info("EditorStandalone", "Loaded blueprint: {}", def.id());
                return;
            }
        }

        // Fallback: create empty canvas (already has Begin node from constructor)
        Log.warn("EditorStandalone", "No blueprint loaded, starting with empty canvas. "
                + "Tried: {}", resourcePath);
    }

    private String loadJsonFromResources(String resourcePath) {
        // Try from classpath (works from gradle build/output directory)
        String fullPath = "/data/wandscape/blueprints/" + resourcePath;
        try (InputStream is = getClass().getResourceAsStream(fullPath)) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Log.warn("EditorStandalone", "Failed to load resource {}: {}", fullPath, e.getMessage());
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    // Main loop
    // ═══════════════════════════════════════════════════════════════

    long tst=0;
    private void loop() {
    float[] clearColor = {0.15f, 0.15f, 0.18f, 1.0f};

    while (!GLFW.glfwWindowShouldClose(window)) {
//        System.out.println("A" + tst);

        GLFW.glfwPollEvents();
//        System.out.println("B" + tst);

        imGuiGlfw.newFrame();
//        System.out.println("C" + tst);

        ImGui.newFrame();
//        System.out.println("D" + tst);

        // Render blueprint editor
        BlueprintEditorImGui.render(nodeEditorCtx);
//        System.out.println("E" + tst);

        // Render
        ImGui.render();
//        System.out.println("F" + tst);

        GL11.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
//        System.out.println("G" + tst);

        imGuiGl3.renderDrawData(ImGui.getDrawData());
//        System.out.println("H" + tst);

        GLFW.glfwSwapBuffers(window);
//        System.out.println("I" + tst);

        tst++;
    }
}

    // ═══════════════════════════════════════════════════════════════
    // Shutdown
    // ═══════════════════════════════════════════════════════════════

    private void shutdown() {
        if (nodeEditorCtx != null) {
            NodeEditor.destroyEditor(nodeEditorCtx);
        }
        imGuiGl3.dispose();
        imGuiGlfw.dispose();
        ImGui.destroyContext();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        GLFWErrorCallback.createPrint(System.err).free();
        Log.info("EditorStandalone", "Shutdown complete");
    }
}
