package com.wsteam.wandscape.content.task.boundary;

import com.wsteam.wandscape.content.task.types.ResourceId;

/** Called when a resource is added to the warehouse. */
@FunctionalInterface
public interface ResourceAddedListener {
    void onResourceAdded(ResourceId resource, int amount);
}
