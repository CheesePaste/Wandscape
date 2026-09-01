package com.wsteam.wandscape.content.building.data;
import com.wsteam.wandscape.content.magic.data.ParamTypeInfo;

import com.wsteam.wandscape.content.task.engine.dsl.BlueprintDefinition;

import java.util.Map;
/**
 * Client-safe DTO for blueprint metadata.
 * Constructed server-side from {@link BlueprintDefinition},
 * serialized over the network, and consumed by the task editor GUI.
 */
public record BlueprintInfo(
        String id,
        String displayName,
        String description,
        Map<String, ParamTypeInfo> params
) {
    /**
     * Convenience: blueprint with no description or params.
     */
    public BlueprintInfo(String id, String displayName) {
        this(id, displayName, "", Map.of());
    }
}
