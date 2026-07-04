package com.wsteam.wandscape.engine.service;

import com.wsteam.wandscape.core.event.NarrativeEventTriggered;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Subscribes to {@link NarrativeEventTriggered} and evaluates achievement triggers.
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
        world.eventBus.subscribe(NarrativeEventTriggered.class, AchievementService::onEvent);
        Log.info(TAG, "registered on engine EventBus");
    }

    private static void onEvent(NarrativeEventTriggered event) {
        // TODO: evaluate achievement triggers
        Log.debug(TAG, "achievement check: %s", event);
    }
}
