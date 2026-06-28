package com.wsteam.wandscape.core.system;

import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.core.event.ResourceLow;
import com.wsteam.wandscape.core.task.GlobalTaskPool;
import com.wsteam.wandscape.core.types.ResourceId;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Watches colony warehouse resource levels and publishes gathering tasks
 * when a resource drops below its threshold.
 *
 * <p>Guards: skips entirely when no colonies exist or no storage building is
 * present — avoids spurious gather tasks before the colony is bootstrapped.
 */
public class WarehouseSource implements TaskSource {

    private static final String TAG = "WarehouseSrc";
    private static final int POLL_INTERVAL = 20; // ticks

    // Default thresholds for key resources
    private final Map<ResourceId, Integer> thresholds = new HashMap<>();

    /**
     * Optional check for whether at least one storage building exists.
     * Called every poll. When absent, the storage check is skipped (backward compat).
     */
    @Nullable
    private final BooleanSupplier storageCheck;

    public WarehouseSource() {
        this(null);
    }

    public WarehouseSource(@Nullable BooleanSupplier storageCheck) {
        this.storageCheck = storageCheck;
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
        // Guard 1: no colonies yet → nothing to supply
        if (world.colonyResources == null || !world.colonyResources.hasColonies()) {
            return;
        }

        // Guard 2: no storage building → nowhere to store gathered resources
        if (storageCheck != null && !storageCheck.getAsBoolean()) {
            return;
        }

        // Check each tracked resource against its threshold
        for (Map.Entry<ResourceId, Integer> entry : thresholds.entrySet()) {
            ResourceId resource = entry.getKey();
            int threshold = entry.getValue();

            int available = world.colonyResources.available(resource);
            if (available < threshold) {
                Log.debug(TAG, "low %s: %d < %d", resource.id(), available, threshold);
                world.eventBus.emit(new ResourceLow(resource, available, threshold));
            }
        }
    }

    /** Set a threshold for a specific resource. */
    public void setThreshold(ResourceId resource, int threshold) {
        thresholds.put(resource, threshold);
    }
}
