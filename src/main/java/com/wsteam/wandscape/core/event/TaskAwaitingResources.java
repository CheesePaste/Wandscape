package com.wsteam.wandscape.core.event;

import com.wsteam.wandscape.core.types.ResourceStack;

/** Emitted when a global task enters AWAITING_RESOURCES state. */
public record TaskAwaitingResources(long taskId, ResourceStack needed) {
    @Override public String toString() { return "TaskAwaitingResources[#" + taskId + " need " + needed + "]"; }
}
