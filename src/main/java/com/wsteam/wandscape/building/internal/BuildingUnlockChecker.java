package com.wsteam.wandscape.building.internal;

import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.shared.ui.panel.WandscapePanelState;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLEnvironment;

import javax.annotation.Nullable;
import java.util.UUID;

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
        if (isGovernment) return true;
        if (colonyId == null) return false;

        return isUnlockedAtLevel(resolveLevel(colonyId), config);
    }

    /**
     * Pure level-threshold decision, independent of where the level comes from.
     * Separated from level resolution so the rule itself is unit-testable.
     *
     * <p>{@code government} and {@code NONE} (min level 1) are always unlocked.
     */
    static boolean isUnlockedAtLevel(int currentLevel, BuildingConfig config) {
        if (config == null) return false;
        if ("government".equals(config.category())) return true;
        BuildingConfig.UnlockRequirement req = config.unlockRequirement();
        if (req == BuildingConfig.UnlockRequirement.NONE) return true;
        return currentLevel >= req.minColonyLevel();
    }

    /**
     * Resolve the colony's current level.
     *
     * <p>Server side: {@link WandscapeEngine#getColonyLevelManager()} is populated.
     * Client side: the engine is server-only ({@code null}), so fall back to the
     * colony level already synced via {@code ColonyStatsSyncPacket} into
     * {@link WandscapePanelState} — otherwise every level-gated building would read
     * level 1 and stay locked even at max colony level.
     */
    private static int resolveLevel(@Nullable UUID colonyId) {
        var levelMgr = WandscapeEngine.getColonyLevelManager();
        if (levelMgr != null) return levelMgr.getLevel(colonyId);
        if (FMLEnvironment.dist.isClient()) {
            return WandscapePanelState.getColonyLevel();
        }
        return 1;
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

        int currentLevel = resolveLevel(colonyId);
        int required = req.minColonyLevel();
        if (currentLevel < required) {
            return I18n.name("message.wandscape.unlock.need_level",
                    "需要小镇等级 %d (当前: %d)", required, currentLevel);
        }
        return null;
    }
}
