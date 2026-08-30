package com.wsteam.wandscape.shared.data;

import com.google.gson.JsonElement;

import java.util.Collections;
import java.util.Map;
/**
 * A queued work item inside a building's internal FIFO queue.
 * Contains enough information to construct a {@code TaskRequest} when
 * {@code BuildingTaskSource} polls the building.
 *
 * @param blueprintId the blueprint key (e.g. "build:stone_bricks")
 * @param params      positional and contextual parameters for blueprint generation
 *                    (typed {@link JsonElement} values — string, int, pos, list, map)
 * @param priority    scheduling priority (higher = sooner)
 */
public record WorkItem(
        String blueprintId,
        Map<String, JsonElement> params,
        int priority
) {
    public WorkItem {
        if (params == null) params = Collections.emptyMap();
    }

    public WorkItem(String blueprintId, int priority) {
        this(blueprintId, Collections.emptyMap(), priority);
    }
}
