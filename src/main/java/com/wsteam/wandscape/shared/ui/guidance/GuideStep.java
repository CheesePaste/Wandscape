package com.wsteam.wandscape.shared.ui.guidance;

import java.util.List;
import java.util.function.Predicate;

/**
 * A single tutorial step. Three line variants are shown depending on the
 * player's build phase: default (panel idle), building-bar open, or placing
 * a blueprint in world.
 */
public record GuideStep(
        String id,
        String title,
        List<String> defaultLines,
        List<String> barLines,
        List<String> placingLines,
        String hint,
        Predicate<GuideContext> done) {

    /** Lines to show for the given build phase. */
    public List<String> linesFor(boolean buildMode, boolean isPlacing, boolean isBar) {
        if (isPlacing) return placingLines;
        if (buildMode && isBar) return barLines;
        return defaultLines;
    }
}
