package com.wsteam.wandscape.engine.service;

import com.wsteam.wandscape.core.event.NarrativeEventTriggered;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.shared.event.ColonyLevelUpEvent;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Subscribes to {@link NarrativeEventTriggered} and {@link ColonyLevelUpEvent}
 * and evaluates achievement triggers against colony metrics.
 *
 * <p>Skeleton — achievement definitions, trigger evaluation, and unlock persistence
 * will be implemented in a future phase.
 */
public final class AchievementService {
    private static final String TAG = "AchievementService";

    private AchievementService() {}

    public static void register() {
        var world = WandscapeEngine.getWorld();
        if (world == null || world.eventBus == null) {
            Log.warn(TAG, "Cannot register — engine not bootstrapped");
            return;
        }
        world.eventBus.subscribe(NarrativeEventTriggered.class, AchievementService::onNarrativeEvent);
        world.eventBus.subscribe(ColonyLevelUpEvent.class, AchievementService::onColonyLevelUp);
        Log.info(TAG, "registered on engine EventBus");
    }

    private static void onNarrativeEvent(NarrativeEventTriggered event) {
        // TODO: evaluate achievement triggers
        // Use: WandscapeApis.getColonyMetricsApi().getSnapshotSafe(event.event().colonyId())
        Log.debug(TAG, "achievement check on narrative event: %s", event);
    }

    private static void onColonyLevelUp(ColonyLevelUpEvent event) {
        // TODO: check level-based achievements
        // Use: WandscapeApis.getColonyMetricsApi().getSnapshotSafe(event.colonyId())
        Log.debug(TAG, "colony leveled up: %s → Lv.%d", event.colonyId(), event.newLevel());
    }
}
