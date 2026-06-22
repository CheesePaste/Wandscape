package com.wsteam.wandscape.shared.ui.task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wsteam.wandscape.shared.data.BlueprintInfo;

/**
 * Client-side state holder for the task editor GUI.
 * Accessible from the screen renderer and packet handlers.
 */
public final class TaskEditorClientState {

    private static volatile List<BlueprintInfo> blueprints = List.of();
    private static volatile BlueprintInfo selectedBlueprint = null;
    private static volatile Map<String, String> draftParams = new LinkedHashMap<>();
    private static volatile int draftPriority = 10;

    private TaskEditorClientState() {}

    // ── Blueprint list ──

    public static List<BlueprintInfo> getBlueprints() {
        return Collections.unmodifiableList(blueprints);
    }

    public static void setBlueprints(List<BlueprintInfo> list) {
        blueprints = list != null ? List.copyOf(list) : List.of();
    }

    // ── Selection ──

    public static BlueprintInfo getSelectedBlueprint() {
        return selectedBlueprint;
    }

    public static void setSelectedBlueprint(BlueprintInfo bp) {
        selectedBlueprint = bp;
        draftParams = new LinkedHashMap<>();
        if (bp != null && bp.params() != null) {
            for (String key : bp.params().keySet()) {
                draftParams.put(key, "");
            }
        }
        draftPriority = 10;
    }

    // ── Draft params ──

    public static Map<String, String> getDraftParams() {
        return Collections.unmodifiableMap(draftParams);
    }

    public static void setDraftParam(String key, String value) {
        draftParams.put(key, value);
    }

    // ── Priority ──

    public static int getDraftPriority() {
        return draftPriority;
    }

    public static void setDraftPriority(int priority) {
        draftPriority = Math.max(0, priority);
    }

    // ── Clear ──

    public static void clear() {
        blueprints = List.of();
        selectedBlueprint = null;
        draftParams = new LinkedHashMap<>();
        draftPriority = 10;
    }
}
