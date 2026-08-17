package com.wsteam.wandscape.road.client;

import com.wsteam.wandscape.imgui.ImGuiManager;
import com.wsteam.wandscape.road.client.studio.RoadStudioOverlay;

/**
 * Unified input capture query for both the ImGui studio and the native Road Studio overlay.
 */
public final class RoadEditorInputHelper {
    private RoadEditorInputHelper() {}

    /** True if either ImGui or the native Road Studio overlay is consuming mouse input. */
    public static boolean wantsMouse() {
        if (RoadStudioOverlay.isVisible() && RoadStudioOverlay.isMouseOverPanel()) {
            return true;
        }
        if (ImGuiManager.isVisible() && ImGuiManager.wantsMouse()) {
            return true;
        }
        return false;
    }

    /** True if either ImGui or the native Road Studio overlay is consuming keyboard input. */
    public static boolean wantsKeyboard() {
        if (RoadStudioOverlay.isVisible() && RoadStudioOverlay.wantsKeyboard()) {
            return true;
        }
        if (ImGuiManager.isVisible() && ImGuiManager.wantsKeyboard()) {
            return true;
        }
        return false;
    }
}
