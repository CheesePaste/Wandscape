package com.wsteam.wandscape.content.task.op.executor;

import com.wsteam.wandscape.content.task.types.ResourceStack;
import com.wsteam.wandscape.content.task.runtime.TaskState;
import com.wsteam.wandscape.content.task.scheduler.TaskExecutionSystem;

import java.util.List;
/**
 * Thrown by {@link OpExecutor}s when a resource-dependent operation
 * cannot proceed because the colony warehouse lacks sufficient stock.
 *
 * <p>This is NOT a fatal error — it is a signal that
 * {@link TaskExecutionSystem}
 * recognizes and converts into an
 * {@link TaskState#AWAITING_RESOURCES}
 * state transition. The task stays intact at its current stepIndex
 * and resumes when the warehouse is replenished.
 *
 * <p>Wrapped in {@link java.util.concurrent.CompletionException} by
 * {@link java.util.concurrent.CompletableFuture#failedFuture(Throwable)}.
 */
public class ResourceShortageException extends RuntimeException {

    private final List<ResourceStack> requested;

    public ResourceShortageException(List<ResourceStack> requested) {
        super("Resource shortage: need " + requested.stream()
                .map(r -> r.amount() + " x " + r.resource().id())
                .collect(java.util.stream.Collectors.joining(", ")));
        this.requested = List.copyOf(requested);
    }

    /** All resources that were requested. */
    public List<ResourceStack> requestedItems() {
        return requested;
    }
}
