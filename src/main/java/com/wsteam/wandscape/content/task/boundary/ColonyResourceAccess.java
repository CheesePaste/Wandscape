package com.wsteam.wandscape.content.task.boundary;

import com.wsteam.wandscape.content.task.types.ResourceId;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Core-layer boundary for colony warehouse resource access.
 * Core only issues requests - the adapter layer owns the actual storage.
 *
 * <p>All operations are scoped to a specific colony to ensure complete parallel isolation.
 */
public interface ColonyResourceAccess {

    /** Check whether the colony warehouse has at least the given amount. */
    boolean hasEnough(@Nullable UUID colonyId, ResourceId resource, int amount);

    /** Reserve resources (optimistic lock for pending operations). */
    boolean reserve(@Nullable UUID colonyId, ResourceId resource, int amount);

    /** Commit reserved resources (finalize the transfer). */
    boolean commit(@Nullable UUID colonyId, ResourceId resource, int amount);

    /** Release a reservation without consuming. */
    void release(@Nullable UUID colonyId, ResourceId resource, int amount);

    /** Get the currently available amount (not reserved). */
    int available(@Nullable UUID colonyId, ResourceId resource);

    /** Add resources directly to the colony warehouse (e.g. from node gathering). */
    void addResource(@Nullable UUID colonyId, ResourceId resource, int amount);

}
