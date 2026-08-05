package com.wsteam.wandscape.engine.service;

import com.wsteam.wandscape.core.event.NarrativeEventTriggered;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Subscribes to {@link NarrativeEventTriggered} and records per-colony statistics.
 *
 * <p>Skeleton — stats tracking (visit counts, satisfaction trends, element throughput)
 * will be implemented in a future phase.
 */
public final class StatsService {
    private static final String TAG = "StatsService";

    private StatsService() {}

    public static void register() {
        var world = WandscapeEngine.getWorld();
        if (world == null || world.eventBus == null) {
            Log.warn(TAG, "Cannot register — engine not bootstrapped");
            return;
        }
        world.eventBus.subscribe(NarrativeEventTriggered.class, StatsService::onEvent);
        Log.info(TAG, "registered on engine EventBus");
    }

    private static void onEvent(NarrativeEventTriggered event) {
        // TODO: record stats (type counts, game-time distribution, etc.)
    }
}
