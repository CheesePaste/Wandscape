package com.wsteam.wandscape.core.task;

import java.util.Collections;
import java.util.Map;

import com.google.gson.JsonElement;
import com.wsteam.wandscape.core.types.BehaviourTag;
/**
 * Request to create a global task from a blueprint.
 * Published by TaskSources and compiled by BlueprintRegistry.
 *
 * <p>Parameters are typed {@link JsonElement} values (string, int, pos array, list, map).
 */
public record TaskRequest(
        String blueprintId,
        Map<String, JsonElement> params,
        int priority,
        Map<BehaviourTag, Integer> wandRequirementOverrides
) {
    public TaskRequest {
        if (params == null) params = Collections.emptyMap();
        if (wandRequirementOverrides == null) wandRequirementOverrides = Collections.emptyMap();
    }

    public TaskRequest(String blueprintId, Map<String, JsonElement> params, int priority) {
        this(blueprintId, params, priority, Collections.emptyMap());
    }

    public TaskRequest(String blueprintId, int priority) {
        this(blueprintId, Collections.emptyMap(), priority, Collections.emptyMap());
    }
}
