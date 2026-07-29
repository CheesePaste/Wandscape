package com.wsteam.wandscape.production.internal;

import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.production.data.RecipeUnlockRequirement;
/**
 * Utility for checking whether a production recipe is unlocked for a given colony.
 *
 * <p>Unlock is based on the colony's current level.
 *
 * <p>Safe to call from both the service thread (data-packet construction) and
 * the server game thread (request validation).
 */
public final class RecipeUnlockChecker {
    private RecipeUnlockChecker() {}

    /**
     * Returns {@code true} if the colony's level meets or exceeds the recipe's minimum.
     *
     * @param colonyId the colony to check; {@code null} or all-zero UUID treated as no-colony (always locked)
     */
    public static boolean isUnlocked(@Nullable UUID colonyId, RecipeUnlockRequirement req) {
        if (colonyId == null) return false;
        if (req == null || req == RecipeUnlockRequirement.NONE) return true;
        var levelMgr = WandscapeEngine.getColonyLevelManager();
        if (levelMgr == null) return false;
        return levelMgr.getLevel(colonyId) >= req.minColonyLevel();
    }

    /**
     * Returns a human-readable reason string explaining why a recipe is locked,
     * or {@code null} if it is unlocked.
     */
    @Nullable
    public static String getLockReason(@Nullable UUID colonyId, RecipeUnlockRequirement req) {
        if (req == null || req == RecipeUnlockRequirement.NONE) return null;
        var levelMgr = WandscapeEngine.getColonyLevelManager();
        if (levelMgr == null) return "Level system not available";
        int current = levelMgr.getLevel(colonyId);
        int required = req.minColonyLevel();
        if (current < required)
            return "Requires colony level %d (current: %d)".formatted(required, current);
        return null;
    }
}
