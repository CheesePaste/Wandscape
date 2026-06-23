package com.wsteam.wandscape.production.internal;

import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.production.data.RecipeUnlockRequirement;
import com.wsteam.wandscape.shared.api.BuildingApi;
import com.wsteam.wandscape.shared.registry.WandscapeApis;

/**
 * Utility for checking whether a production recipe is unlocked for a given colony.
 *
 * <p>Unlock is based on the colony's current evaluation snapshot:
 * comfort, magic, and wonder must each meet or exceed the recipe's minimums.
 *
 * <p>Safe to call from both the service thread (data-packet construction) and
 * the server game thread (request validation).
 */
public final class RecipeUnlockChecker {
    private RecipeUnlockChecker() {}

    /**
     * Returns {@code true} if the colony's three evaluation values all satisfy
     * the recipe's minimum requirements.
     *
     * @param colonyId the colony to check; {@code null} or all-zero UUID treated as no-colony (always locked)
     */
    public static boolean isUnlocked(@Nullable UUID colonyId, RecipeUnlockRequirement req) {
        if (colonyId == null) return false;
        BuildingApi api = WandscapeApis.getBuildingApi();
        return api.getColonyComfort(colonyId) >= req.minComfort()
            && api.getColonyMagic(colonyId)   >= req.minMagic()
            && api.getColonyWonder(colonyId)  >= req.minWonder();
    }

    /**
     * Returns a human-readable reason string explaining why a recipe is locked,
     * or {@code null} if it is unlocked.
     */
    @Nullable
    public static String getLockReason(@Nullable UUID colonyId, RecipeUnlockRequirement req) {
        if (req == null || req == RecipeUnlockRequirement.NONE) return null;
        BuildingApi api = WandscapeApis.getBuildingApi();
        int c = api.getColonyComfort(colonyId);
        int m = api.getColonyMagic(colonyId);
        int w = api.getColonyWonder(colonyId);
        if (c < req.minComfort())
            return "Requires Comfort %d (current: %d)".formatted(req.minComfort(), c);
        if (m < req.minMagic())
            return "Requires Magic %d (current: %d)".formatted(req.minMagic(), m);
        if (w < req.minWonder())
            return "Requires Wonder %d (current: %d)".formatted(req.minWonder(), w);
        return null;
    }
}
