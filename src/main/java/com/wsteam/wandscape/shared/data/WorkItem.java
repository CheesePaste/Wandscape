package com.wsteam.wandscape.shared.data;

import java.util.Collections;
import java.util.Map;

import com.google.gson.JsonElement;
import com.wsteam.wandscape.core.types.BehaviourTag;
/**
 * A queued work item inside a building's internal FIFO queue.
 * Contains enough information to construct a {@code TaskRequest} when
 * {@code BuildingTaskSource} polls the building.
 *
 * @param blueprintId the blueprint key (e.g. "build:stone_bricks")
 * @param params      positional and contextual parameters for blueprint generation
 *                    (typed {@link JsonElement} values — string, int, pos, list, map)
 * @param priority    scheduling priority (higher = sooner)
 * @param wandRequirementOverrides per-node/per-recipe wand level overrides from JSON config.
 *                                 0 = remove requirement, ≥1 = override/require that level.
 */
public record WorkItem(
        String blueprintId,
        Map<String, JsonElement> params,
        int priority,
        Map<BehaviourTag, Integer> wandRequirementOverrides
) {
    public WorkItem {
        if (params == null) params = Collections.emptyMap();
        if (wandRequirementOverrides == null) wandRequirementOverrides = Collections.emptyMap();
    }

    public WorkItem(String blueprintId, Map<String, JsonElement> params, int priority) {
        this(blueprintId, params, priority, Collections.emptyMap());
    }

    public WorkItem(String blueprintId, int priority) {
        this(blueprintId, Collections.emptyMap(), priority, Collections.emptyMap());
    }
}
