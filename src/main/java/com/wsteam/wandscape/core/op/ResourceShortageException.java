package com.wsteam.wandscape.core.op;

import com.wsteam.wandscape.core.types.ResourceStack;

/**
 * Thrown by {@link OpExecutor}s when a resource-dependent operation
 * cannot proceed because the colony warehouse lacks sufficient stock.
 *
 * <p>This is NOT a fatal error — it is a signal that
 * {@link com.wsteam.wandscape.core.system.TaskExecutionSystem}
 * recognizes and converts into an
 * {@link com.wsteam.wandscape.core.task.TaskState#AWAITING_RESOURCES}
 * state transition. The task stays intact at its current stepIndex
 * and resumes when the warehouse is replenished.
 *
 * <p>Wrapped in {@link java.util.concurrent.CompletionException} by
 * {@link java.util.concurrent.CompletableFuture#failedFuture(Throwable)}.
 */
public class ResourceShortageException extends RuntimeException {

    private final ResourceStack requested;

    public ResourceShortageException(ResourceStack requested) {
        super("Resource shortage: need " + requested.amount() + " x " + requested.resource().id());
        this.requested = requested;
    }

    public ResourceStack requested() {
        return requested;
    }
}
