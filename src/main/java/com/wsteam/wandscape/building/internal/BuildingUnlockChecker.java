package com.wsteam.wandscape.building.internal;

import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.registry.WandscapeApis;
/**
 * Utility for checking whether a building type is unlocked for a given colony.
 *
 * <p>A building is unlocked when the colony's evaluation values meet or exceed
 * all minima specified in {@link BuildingConfig.UnlockRequirement}.
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
        BuildingApi api = WandscapeApis.getBuildingApi();
        return api.getColonyComfort(colonyId) >= req.minComfort()
            && api.getColonyMagic(colonyId)   >= req.minMagic()
            && api.getColonyWonder(colonyId)  >= req.minWonder();
    }

    @Nullable
    public static String getLockReason(@Nullable UUID colonyId, BuildingConfig config) {
        BuildingConfig.UnlockRequirement req = config.unlockRequirement();
        if (req == BuildingConfig.UnlockRequirement.NONE) return null;
        if (colonyId == null) return "No colony assigned";
        BuildingApi api = WandscapeApis.getBuildingApi();
        int c = api.getColonyComfort(colonyId);
        int m = api.getColonyMagic(colonyId);
        int w = api.getColonyWonder(colonyId);
        if (c < req.minComfort())
            return "Requires Comfort %d (current: C=%d M=%d W=%d)".formatted(req.minComfort(), c, m, w);
        if (m < req.minMagic())
            return "Requires Magic %d (current: C=%d M=%d W=%d)".formatted(req.minMagic(), c, m, w);
        if (w < req.minWonder())
            return "Requires Wonder %d (current: C=%d M=%d W=%d)".formatted(req.minWonder(), c, m, w);
        return null;
    }
}
