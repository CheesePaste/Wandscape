package com.wsteam.wandscape.building.internal;

import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.engine.WandscapeEngine;
/**
 * Utility for checking whether a building type is unlocked for a given colony.
 *
 * <p>A building is unlocked when the colony's level meets or exceeds the minimum
 * specified in {@link BuildingConfig.UnlockRequirement}.
 * If no requirement is present ({@code NONE}) the building is always available.
 */
public final class BuildingUnlockChecker {
    private BuildingUnlockChecker() {}

    /**
     * Returns {@code true} if the colony satisfies the building's unlock requirement.
     *
     * @param colonyId the colony to check; {@code null} treated as not-unlocked
     */
    public static boolean isUnlocked(@Nullable UUID colonyId, BuildingConfig config) {
        if (colonyId == null) return false;
        BuildingConfig.UnlockRequirement req = config.unlockRequirement();
        if (req == BuildingConfig.UnlockRequirement.NONE) return true;
        var levelMgr = WandscapeEngine.getColonyLevelManager();
        if (levelMgr == null) return false;
        return levelMgr.getLevel(colonyId) >= req.minColonyLevel();
    }

    @Nullable
    public static String getLockReason(@Nullable UUID colonyId, BuildingConfig config) {
        BuildingConfig.UnlockRequirement req = config.unlockRequirement();
        if (req == BuildingConfig.UnlockRequirement.NONE) return null;
        if (colonyId == null) return "No colony assigned";
        var levelMgr = WandscapeEngine.getColonyLevelManager();
        if (levelMgr == null) return "Level system not available";
        int current = levelMgr.getLevel(colonyId);
        int required = req.minColonyLevel();
        if (current < required)
            return "Requires colony level %d (current: %d)".formatted(required, current);
        return null;
    }
}
