package com.wsteam.wandscape.shared.ui.guidance;

import java.util.List;

/**
 * A single tutorial step (pure content). Multiple line variants are shown
 * depending on the player's build phase: default (panel idle), building-bar
 * open, placing a blueprint in world (compact by default to reduce view
 * obstruction), or pinned with gizmo. Step completion is evaluated
 * server-side by {@code GuideProgressService.computeStep}.
 */
public record GuideStep(
        String id,
        String title,
        List<String> defaultLines,
        List<String> barLines,
        List<String> aimingLines,
        List<String> pinnedLines,
        String hint,
        List<String> compactAimingLines) {

    /** Lines to show for the given build phase. */
    public List<String> linesFor(boolean buildMode, boolean isPlacing, boolean isBar, boolean isPinned) {
        if (isPlacing) {
            if (isPinned) return pinnedLines;
            // Compact mode during aiming — only the key instruction to avoid
            // blocking too much of the 3D view while the player aims.
            return compactAimingLines != null && !compactAimingLines.isEmpty()
                    ? compactAimingLines : aimingLines;
        }
        if (buildMode && isBar) return barLines;
        return defaultLines;
    }
}
