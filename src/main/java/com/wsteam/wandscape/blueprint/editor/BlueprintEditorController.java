package com.wsteam.wandscape.blueprint.editor;

import com.wsteam.wandscape.core.task.BlueprintDefinition;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Controller for the blueprint node editor.
 *
 * <p>Handles save/load/exit actions. The save path sends a
 * {@code BlueprintDefinition} to the server for JSON file persistence.
 */
public final class BlueprintEditorController {

    private static final String TAG = "BlueprintEditorController";

    private BlueprintEditorController() {}

    /** Save the current canvas as a BlueprintDefinition JSON file. */
    public static void doSave() {
        BlueprintEditorCanvas graph = BlueprintEditorClientState.getCanvas();
        if (graph == null) return;

        if (graph.blueprintId.isEmpty()) {
            graph.blueprintId = "custom:untitled";
        }

        BlueprintDefinition def = graph.toDefinition();
        String json = BlueprintEditorNetwork.definitionToJson(def);

        // Send to server for persistence
        BlueprintEditorNetwork.sendSaveToServer(def, json);

        BlueprintEditorClientState.clearDirty();
        Log.info(TAG, "Blueprint saved: {}", graph.blueprintId);
    }

    /** Load a blueprint from the last saved JSON or clipboard. */
    public static void doLoad() {
        // Try clipboard first
        String json = BlueprintEditorClientState.getClipboardJson();
        if (json != null && !json.isEmpty()) {
            doLoadFromJson(json);
        } else {
            Log.warn(TAG, "No blueprint to load (clipboard empty)");
        }
    }

    /** Load a blueprint directly from a JSON string. */
    public static void doLoadFromJson(String json) {
        if (json == null || json.isBlank()) {
            Log.warn(TAG, "Cannot load: JSON string is empty");
            return;
        }
        BlueprintDefinition def = BlueprintEditorNetwork.jsonToDefinition(json);
        if (def != null) {
            BlueprintEditorCanvas graph = BlueprintEditorCanvas.fromDefinition(def);
            BlueprintEditorClientState.setCanvas(graph);
            Log.info(TAG, "Blueprint loaded: {}", def.id());
        } else {
            Log.warn(TAG, "Failed to parse blueprint JSON");
        }
    }

    /** Exit the blueprint editor. Prompts to save if dirty. */
    public static void doExit() {
        if (BlueprintEditorClientState.isDirty()) {
            // Auto-save on exit for safety
            doSave();
        }
        BlueprintEditorClientState.exitEditMode();
        // Re-grab mouse for Minecraft
        com.wsteam.wandscape.imgui.ImGuiManager.setVisible(false);
    }

    /** Handle a successful save response from the server. */
    public static void onSaveSuccess(String blueprintId, String filePath) {
        BlueprintEditorClientState.setLoadedFilePath(filePath);
        BlueprintEditorClientState.clearDirty();
        Log.info(TAG, "Server confirmed save: {} → {}", blueprintId, filePath);
    }

    /** Handle a save failure from the server. */
    public static void onSaveFailure(String blueprintId, String error) {
        Log.warn(TAG, "Server rejected save for {}: {}", blueprintId, error);
    }
}
