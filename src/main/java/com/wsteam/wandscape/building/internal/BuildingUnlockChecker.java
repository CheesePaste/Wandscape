package com.wsteam.wandscape.building.internal;

import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.shared.ui.I18n;

import net.minecraft.network.chat.Component;

/**
 * Utility for checking whether a building type is unlocked for a given colony.
 *
 * <p>A building is unlocked when the colony's level meets or exceeds the minimum
 * specified in {@link BuildingConfig.UnlockRequirement}.
 * If no requirement is present ({@code NONE}) or no colony exists yet ({@code colonyId == null}),
 * starter buildings (category {@code government}, {@code first_free}, or level 1) are unlocked.
 */
public final class BuildingUnlockChecker {
    private BuildingUnlockChecker() {}

    /**
     * Returns {@code true} if the colony satisfies the building's unlock requirement.
     *
     * @param colonyId the colony to check; if {@code null}, treated as starter colony level 1
     */
    public static boolean isUnlocked(@Nullable UUID colonyId, BuildingConfig config) {
        if (config == null) return false;

        boolean isGovernment = "government".equals(config.category());
        if (colonyId == null) {
            return isGovernment;
        }

        if (isGovernment) return true;
        BuildingConfig.UnlockRequirement req = config.unlockRequirement();
        if (req == BuildingConfig.UnlockRequirement.NONE) return true;

        var levelMgr = WandscapeEngine.getColonyLevelManager();
        int currentLevel = levelMgr != null ? levelMgr.getLevel(colonyId) : 1;
        return currentLevel >= req.minColonyLevel();
    }

    @Nullable
    public static Component getLockReason(@Nullable UUID colonyId, BuildingConfig config) {
        if (config == null) return Component.literal("Invalid building config");

        boolean isGovernment = "government".equals(config.category());
        if (colonyId == null) {
            if (isGovernment) return null;
            return I18n.name("message.wandscape.unlock.need_townhall", "需要先建造市政厅建立小镇");
        }

        if (isGovernment) return null;
        BuildingConfig.UnlockRequirement req = config.unlockRequirement();
        if (req == BuildingConfig.UnlockRequirement.NONE) return null;

        var levelMgr = WandscapeEngine.getColonyLevelManager();
        int currentLevel = levelMgr != null ? levelMgr.getLevel(colonyId) : 1;
        int required = req.minColonyLevel();
        if (currentLevel < required) {
            return I18n.name("message.wandscape.unlock.need_level",
                    "需要小镇等级 %d (当前: %d)", required, currentLevel);
        }
        return null;
    }
}
