package com.wsteam.wandscape.shared.data;

import java.util.Collections;
import java.util.Map;

/**
 * A queued work item inside a building's internal FIFO queue.
 * Contains enough information to construct a {@code TaskRequest} when
 * {@link BuildingTaskSource} polls the building.
 *
 * @param blueprintId the blueprint key (e.g. "build:stone_bricks")
 * @param params      positional and contextual parameters for blueprint generation
 * @param priority    scheduling priority (higher = sooner)
 */
public record WorkItem(
        String blueprintId,
        Map<String, String> params,
        int priority
) {
    public WorkItem {
        if (params == null) params = Collections.emptyMap();
    }

    public WorkItem(String blueprintId, int priority) {
        this(blueprintId, Collections.emptyMap(), priority);
    }
}
