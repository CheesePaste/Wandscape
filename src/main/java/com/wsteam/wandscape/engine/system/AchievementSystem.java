package com.wsteam.wandscape.engine.system;

import com.wsteam.wandscape.core.event.NarrativeEventTriggered;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Subscribes to {@link NarrativeEventTriggered} and evaluates achievement triggers.
 *
 * <p>Skeleton — achievement definitions, trigger evaluation, and unlock persistence
 * will be implemented in a future phase.
 */
public final class AchievementSystem {
    private static final String TAG = "AchievementSystem";

    private AchievementSystem() {}

    public static void register() {
        var world = WandscapeEngine.getWorld();
        if (world == null || world.eventBus == null) {
            Log.warn(TAG, "Cannot register — engine not bootstrapped");
            return;
        }
        world.eventBus.subscribe(NarrativeEventTriggered.class, AchievementSystem::onEvent);
        Log.info(TAG, "registered on engine EventBus");
    }

    private static void onEvent(NarrativeEventTriggered event) {
        // TODO: evaluate achievement triggers
        Log.debug(TAG, "achievement check: %s", event);
    }
}
