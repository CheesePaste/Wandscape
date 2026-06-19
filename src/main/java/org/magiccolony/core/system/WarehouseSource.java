package org.magiccolony.core.system;

import org.magiccolony.core.Log;
import org.magiccolony.core.boundary.EventBus;
import org.magiccolony.core.ecs.World;
import org.magiccolony.core.event.ResourceLow;
import org.magiccolony.core.task.GlobalTaskPool;
import org.magiccolony.core.task.TaskRequest;
import org.magiccolony.core.types.GridPos;
import org.magiccolony.core.types.ResourceId;

import java.util.*;

/**
 * Watches colony warehouse resource levels and publishes gathering tasks
 * when a resource drops below its threshold.
 *
 * This is a minimal V1 stub. In production, thresholds would be configured per resource.
 */
public class WarehouseSource implements TaskSource {

    private static final String TAG = "WarehouseSrc";
    private static final int POLL_INTERVAL = 20; // ticks

    // Default thresholds for key resources
    private final Map<ResourceId, Integer> thresholds = new HashMap<>();
    private final EventBus eventBus;

    public WarehouseSource(EventBus eventBus) {
        this.eventBus = eventBus;
        // Default thresholds
        thresholds.put(ResourceId.STONE_BRICKS, 128);
        thresholds.put(ResourceId.GLASS, 64);
        thresholds.put(ResourceId.IRON_INGOT, 64);
        thresholds.put(ResourceId.WOOD, 128);
        thresholds.put(ResourceId.STONE, 128);
    }

    @Override
    public int pollIntervalTicks() {
        return POLL_INTERVAL;
    }

    @Override
    public void poll(GlobalTaskPool pool, World world) {
        // Check each tracked resource against its threshold
        for (Map.Entry<ResourceId, Integer> entry : thresholds.entrySet()) {
            ResourceId resource = entry.getKey();
            int threshold = entry.getValue();

            if (world.colonyResources != null) {
                int available = world.colonyResources.available(resource);
                if (available < threshold) {
                    Log.debug(TAG, "low %s: %d < %d", resource.id(), available, threshold);
                    eventBus.emit(new ResourceLow(resource, available, threshold));
                }
            }
        }
    }

    /** Set a threshold for a specific resource. */
    public void setThreshold(ResourceId resource, int threshold) {
        thresholds.put(resource, threshold);
    }
}
