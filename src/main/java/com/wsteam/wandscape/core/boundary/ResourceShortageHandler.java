package com.wsteam.wandscape.core.boundary;

import com.wsteam.wandscape.core.types.GridPos;
import com.wsteam.wandscape.core.types.ResourceId;

/**
 * Core-layer boundary for handling resource shortages with module-specific strategies.
 *
 * <p>Called by {@link com.wsteam.wandscape.core.system.EventDrivenTaskSource}
 * before falling back to the default {@code gather:<resource>} task.
 * An engine-layer implementation can check synthesize recipes and enqueue
 * a {@code production:synthesize} task instead.
 *
 * @return true if the shortage was handled (e.g. synthesize task enqueued),
 *         false to fall through to the default gather behavior
 */
@FunctionalInterface
public interface ResourceShortageHandler {
    boolean handle(ResourceId resource, int amount, GridPos location);
}
