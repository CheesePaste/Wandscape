package com.wsteam.wandscape.content.road.client;

import com.wsteam.wandscape.content.road.client.studio.RoadStudioOverlay;

/**
 * Unified input capture query for the native Road Studio overlay.
 */
public final class RoadEditorInputHelper {
    private RoadEditorInputHelper() {}

    /** True if the native Road Studio overlay is consuming mouse input. */
    public static boolean wantsMouse() {
        return RoadStudioOverlay.isVisible() && RoadStudioOverlay.isMouseOverPanel();
    }

    /** True if the native Road Studio overlay is consuming keyboard input. */
    public static boolean wantsKeyboard() {
        return RoadStudioOverlay.isVisible() && RoadStudioOverlay.wantsKeyboard();
    }
}
