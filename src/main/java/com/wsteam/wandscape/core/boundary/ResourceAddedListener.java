package com.wsteam.wandscape.core.boundary;

import com.wsteam.wandscape.core.types.ResourceId;

/** Called when a resource is added to the warehouse. */
@FunctionalInterface
public interface ResourceAddedListener {
    void onResourceAdded(ResourceId resource, int amount);
}
