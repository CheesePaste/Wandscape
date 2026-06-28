package com.wsteam.wandscape.core.event;

import com.wsteam.wandscape.core.types.ResourceStack;

import java.util.List;
/** Emitted when a global task enters AWAITING_RESOURCES state. */
public record TaskAwaitingResources(long taskId, List<ResourceStack> needed) {

    /** Backward-compat: single resource. */
    public TaskAwaitingResources(long taskId, ResourceStack single) {
        this(taskId, List.of(single));
    }

    @Override
    public String toString() {
        return "TaskAwaitingResources[#" + taskId + " need " + needed + "]";
    }
}
