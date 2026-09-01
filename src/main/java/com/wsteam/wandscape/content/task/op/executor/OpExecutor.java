package com.wsteam.wandscape.content.task.op.executor;

import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.task.op.api.AtomicOp;

import java.util.concurrent.CompletableFuture;
/**
 * Strategy for executing one type of AtomicOp.
 * Registered in OpExecutorRegistry and called by TaskExecutionSystem.
 *
 * <p>Returns a {@link CompletableFuture} instead of a sync result:
 * <ul>
 *   <li>Sync ops return {@link CompletableFuture#completedFuture completedFuture(null)}</li>
 *   <li>Async ops return an incomplete future (e.g. from {@link World#startAsyncOp}).
 *       The engine does NOT re-invoke execute() for the same step — when the future
 *       completes, stepIndex advances automatically.</li>
 * </ul>
 *
 * @param <T> the AtomicOp variant this executor handles
 */
public interface OpExecutor<T extends AtomicOp> {

    /** Which AtomicOp variant does this executor handle? */
    Class<T> opType();

    /**
     * Execute the operation.
     * @return a CompletableFuture that completes when the op is done.
     *         Sync ops: {@code CompletableFuture.completedFuture(null)}.
     *         Async ops: incomplete future, completed by MC boundary layer.
     */
    CompletableFuture<Void> execute(T op, World world, long npcId);
}
