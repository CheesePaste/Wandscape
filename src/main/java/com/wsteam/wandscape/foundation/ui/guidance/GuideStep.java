package com.wsteam.wandscape.foundation.ui.guidance;
import com.wsteam.wandscape.content.items.service.GuideProgressService;
import com.wsteam.wandscape.content.task.ecs.World;

import java.util.List;

/**
 * A single tutorial step (pure content). All text fields hold i18n keys resolved to the
 * client's language at render time by {@code GuideRenderer.text}. Three line variants are
 * shown depending on the player's build phase: default (panel idle), building-bar open, or
 * placing a blueprint in world. Step completion is evaluated server-side by
 * {@code GuideProgressService.computeStep}.
 */
public record GuideStep(
        String id,
        String title,
        List<String> defaultLines,
        List<String> barLines,
        List<String> aimingLines,
        List<String> pinnedLines,
        String hint) {

    /** Lines to show for the given build phase. */
    public List<String> linesFor(boolean buildMode, boolean isPlacing, boolean isBar, boolean isPinned) {
        if (isPlacing) {
            return isPinned ? pinnedLines : aimingLines;
        }
        if (buildMode && isBar) return barLines;
        return defaultLines;
    }
}
