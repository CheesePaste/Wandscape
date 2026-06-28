package com.wsteam.wandscape.core.event;

import com.wsteam.wandscape.core.types.ResourceId;
/** Emitted when a resource has been replenished (e.g., after a gather task completes). */
public record ResourceFulfilled(ResourceId resource, int amount) {
    @Override public String toString() { return "ResourceFulfilled[" + resource + " +" + amount + "]"; }
}
