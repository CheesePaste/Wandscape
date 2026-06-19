package com.wsteam.wandscape.core.task;

import java.util.Collections;
import java.util.Map;

/**
 * Request to create a global task from a blueprint.
 * Published by TaskSources and compiled by BlueprintRegistry.
 *
 * <p>Location is carried in {@link #params} as {@code x}/{@code y}/{@code z} keys.
 */
public record TaskRequest(
        String blueprintId,
        Map<String, String> params,
        int priority
) {
    public TaskRequest {
        if (params == null) params = Collections.emptyMap();
    }

    public TaskRequest(String blueprintId, int priority) {
        this(blueprintId, Collections.emptyMap(), priority);
    }
}
