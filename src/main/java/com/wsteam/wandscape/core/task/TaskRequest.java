package com.wsteam.wandscape.core.task;

import java.util.Collections;
import java.util.Map;

import com.google.gson.JsonElement;

/**
 * Request to create a global task from a blueprint.
 * Published by TaskSources and compiled by BlueprintRegistry.
 *
 * <p>Parameters are typed {@link JsonElement} values (string, int, pos array, list, map).
 */
public record TaskRequest(
        String blueprintId,
        Map<String, JsonElement> params,
        int priority
) {
    public TaskRequest {
        if (params == null) params = Collections.emptyMap();
    }

    public TaskRequest(String blueprintId, int priority) {
        this(blueprintId, Collections.emptyMap(), priority);
    }
}
