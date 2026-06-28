package com.wsteam.wandscape.blueprint.editor;

import com.wsteam.wandscape.core.task.ParamType;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Client-side volatile state for the blueprint node editor.
 * Pattern mirrors {@code BuildingEditorClientState}.
 *
 * <p>All fields are volatile for thread safety. The canvas graph is the
 * single source of truth for the current edit session.
 */
public final class BlueprintEditorClientState {
    // ── Layout flags ──
    private static volatile boolean justLoaded = false;

    public static boolean consumeJustLoaded() {
        if (justLoaded) {
            justLoaded = false;
            return true;
        }
        return false;
    }

    private static final String TAG = "BlueprintEditorClientState";

    // ── Edit mode ──

    private static volatile boolean editMode = false;

    // ── Canvas ──

    /** The current canvas graph being edited. */
    private static BlueprintEditorCanvas canvas = null;

    // ── Selection ──

    /** Currently selected node ID, or -1 if nothing selected. */
    private static volatile long selectedNodeId = -1;

    /** Whether the Inspector panel is visible. */
    private static volatile boolean inspectorVisible = true;

    // ── Search palette ──

    /** Current search query in the context menu palette. */
    private static volatile String searchQuery = "";

    /** Whether the search palette is currently open. */
    private static volatile boolean searchPaletteOpen = false;

    // ── Clipboard ──

    /** JSON string of the copied BlueprintDefinition, or null if empty. */
    private static volatile String clipboardJson = null;

    // ── Dirty flag ──

    /** True if unsaved changes exist. */
    private static volatile boolean dirty = false;

    // ── Loaded file path ──

    /** The JSON file path this blueprint was loaded from, or null if new. */
    private static volatile String loadedFilePath = null;

    private BlueprintEditorClientState() {}

    // ═══════════════════════════════════════════════════════════════
    // Edit mode
    // ═══════════════════════════════════════════════════════════════

    public static boolean isEditing() { return editMode; }

    public static void enterEditMode() {
        editMode = true;
        canvas = new BlueprintEditorCanvas();
        selectedNodeId = -1;
        searchQuery = "";
        searchPaletteOpen = false;
        dirty = false;
        loadedFilePath = null;
        Log.info(TAG, "[BlueprintEditor] Entered edit mode");
    }

    public static void exitEditMode() {
        editMode = false;
        canvas = null;
        selectedNodeId = -1;
        searchQuery = "";
        searchPaletteOpen = false;
        clipboardJson = null;
        dirty = false;
        loadedFilePath = null;
        Log.info(TAG, "[BlueprintEditor] Exited edit mode");
    }

    // ═══════════════════════════════════════════════════════════════
    // Canvas access
    // ═══════════════════════════════════════════════════════════════

    public static BlueprintEditorCanvas getCanvas() {
        if (canvas == null) {
            canvas = new BlueprintEditorCanvas();
        }
        return canvas;
    }

    /** Replace the entire canvas (used when loading a blueprint). */
    public static void setCanvas(BlueprintEditorCanvas newCanvas) {
        canvas = newCanvas;
        selectedNodeId = -1;
        dirty = false;
        justLoaded = true; // 新增：通知 UI 进行初始坐标同步和视角居中
    }

    // ═══════════════════════════════════════════════════════════════
    // Selection
    // ═══════════════════════════════════════════════════════════════

    public static long getSelectedNodeId() { return selectedNodeId; }
    public static void setSelectedNodeId(long id) { selectedNodeId = id; }
    public static void clearSelection() { selectedNodeId = -1; }

    // ═══════════════════════════════════════════════════════════════
    // Inspector
    // ═══════════════════════════════════════════════════════════════

    public static boolean isInspectorVisible() { return inspectorVisible; }
    public static void setInspectorVisible(boolean v) { inspectorVisible = v; }
    public static void toggleInspector() { inspectorVisible = !inspectorVisible; }

    // ═══════════════════════════════════════════════════════════════
    // Search palette
    // ═══════════════════════════════════════════════════════════════

    public static String getSearchQuery() { return searchQuery; }
    public static void setSearchQuery(String q) { searchQuery = q; }
    public static boolean isSearchPaletteOpen() { return searchPaletteOpen; }
    public static void setSearchPaletteOpen(boolean v) { searchPaletteOpen = v; }

    // ═══════════════════════════════════════════════════════════════
    // Clipboard
    // ═══════════════════════════════════════════════════════════════

    public static String getClipboardJson() { return clipboardJson; }
    public static void setClipboardJson(String json) { clipboardJson = json; }

    // ═══════════════════════════════════════════════════════════════
    // Dirty flag
    // ═══════════════════════════════════════════════════════════════

    public static boolean isDirty() { return dirty; }
    public static void markDirty() { dirty = true; }
    public static void clearDirty() { dirty = false; }

    // ═══════════════════════════════════════════════════════════════
    // File path
    // ═══════════════════════════════════════════════════════════════

    public static String getLoadedFilePath() { return loadedFilePath; }
    public static void setLoadedFilePath(String path) { loadedFilePath = path; }

    // ═══════════════════════════════════════════════════════════════
    // Quick helpers
    // ═══════════════════════════════════════════════════════════════

    /** Get the currently selected node, or null. */
    public static BlueprintEditorCanvas.CanvasNode getSelectedNode() {
        if (selectedNodeId < 0 || canvas == null) return null;
        return canvas.nodes.get(selectedNodeId);
    }

    /** Add a step parameter to the canvas. */
    public static void addParam(String name, ParamType type) {
        BlueprintEditorCanvas c = getCanvas();
        c.params.put(name, type);
        // Auto-create Input node
        BlueprintEditorCanvas.CanvasNode inputNode = c.createNode("input", -350,
                100 + c.params.size() * 80f);
        inputNode.inlineValues.put("name", name);
        inputNode.inlineValues.put("type", paramTypeToString(type));
        markDirty();
    }

    /** Remove a step parameter and its Input node. */
    public static void removeParam(String name) {
        BlueprintEditorCanvas c = getCanvas();
        c.params.remove(name);
        // Remove the Input node with matching name
        c.nodes.values().removeIf(n -> "input".equals(n.typeId)
                && name.equals(n.inlineValues.get("name")));
        markDirty();
    }

    private static String paramTypeToString(ParamType type) {
        if (type instanceof ParamType.StringType) return "string";
        if (type instanceof ParamType.IntType) return "int";
        if (type instanceof ParamType.PosType) return "pos";
        if (type instanceof ParamType.ListPosType) return "list<pos>";
        if (type instanceof ParamType.ListStringType) return "list<string>";
        if (type instanceof ParamType.MapStringStringType) return "map<string,string>";
        return "string";
    }
}
