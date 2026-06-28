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
 * <p>Default threshold for every resource is 0 (disabled). Thresholds are
 * set externally (e.g. from player-configured warehouse GUI) via
 * {@link #setThreshold(ResourceId, int)}.
 *
 * <p>Guards: skips entirely when no colonies exist or no storage building is
 * present — avoids spurious gather tasks before the colony is bootstrapped.
 */
public class WarehouseSource implements TaskSource {

    private static final String TAG = "WarehouseSrc";
    private static final int POLL_INTERVAL = 20; // ticks

    /** Singleton instance for external setThreshold access. */
    @Nullable
    private static WarehouseSource activeInstance;

    // Dynamic thresholds (default 0 = disabled). Populated via setThreshold().
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
        activeInstance = this;
    }

    /** Returns the singleton instance, or null if not yet created. */
    @Nullable
    public static WarehouseSource getActive() {
        return activeInstance;
    }

    @Override
    public int pollIntervalTicks() {
        return POLL_INTERVAL;
    }

    @Override
    public void poll(GlobalTaskPool pool, World world) {
        // Guard 0: no thresholds configured → nothing to check
        if (thresholds.isEmpty()) return;

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
        if (threshold <= 0) {
            thresholds.remove(resource);
        } else {
            thresholds.put(resource, threshold);
        }
    }

    /** Returns the threshold for a resource (0 = disabled). */
    public int getThreshold(ResourceId resource) {
        return thresholds.getOrDefault(resource, 0);
    }

    /** Returns an immutable snapshot of all thresholds. */
    public Map<ResourceId, Integer> getAllThresholds() {
        return Map.copyOf(thresholds);
    }
}
