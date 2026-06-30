package com.wsteam.wandscape.engine.system;

import com.wsteam.wandscape.core.event.NarrativeEventTriggered;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Subscribes to {@link NarrativeEventTriggered} and stores chronicle-worthy events.
 *
 * <p>Skeleton — chronicle storage (persistence to {@code ColonySavedData}) will
 * be implemented in a future phase.
 */
public final class ChronicleSystem {
    private static final String TAG = "ChronicleSystem";

    private ChronicleSystem() {}

    public static void register() {
        var world = WandscapeEngine.getWorld();
        if (world == null || world.eventBus == null) {
            Log.warn(TAG, "Cannot register — engine not bootstrapped");
            return;
        }
        world.eventBus.subscribe(NarrativeEventTriggered.class, ChronicleSystem::onEvent);
        Log.info(TAG, "registered on engine EventBus");
    }

    private static void onEvent(NarrativeEventTriggered event) {
        if (!event.event().isChronicleWorthy()) return;
        // TODO: store in ColonyChronicle (ColonySavedData)
        Log.debug(TAG, "chronicle-worthy event: %s", event);
    }
}
