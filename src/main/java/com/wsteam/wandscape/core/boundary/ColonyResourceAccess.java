package com.wsteam.wandscape.core.boundary;

import com.wsteam.wandscape.core.types.ResourceId;
/**
 * Core-layer boundary for colony warehouse resource access.
 * Core only issues requests - the adapter layer owns the actual storage.
 */
public interface ColonyResourceAccess {

    /** Check whether the colony warehouse has at least the given amount. */
    boolean hasEnough(ResourceId resource, int amount);

    /** Reserve resources (optimistic lock for pending operations). */
    boolean reserve(ResourceId resource, int amount);

    /** Commit reserved resources (finalize the transfer). */
    boolean commit(ResourceId resource, int amount);

    /** Release a reservation without consuming. */
    void release(ResourceId resource, int amount);

    /** Get the currently available amount (not reserved). */
    int available(ResourceId resource);

    /** Add resources directly to the colony warehouse (e.g. from node gathering). */
    void addResource(ResourceId resource, int amount);

}
