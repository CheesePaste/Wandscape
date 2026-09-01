package com.wsteam.wandscape.content.building.data;

import java.util.Map;
/**
 * Client-safe DTO for blueprint metadata.
 * Constructed server-side from blueprint definitions,
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
