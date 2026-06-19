package com.wsteam.wandscape.core.op;

import com.wsteam.wandscape.core.ecs.World;

/**
 * Strategy for executing one type of AtomicOp.
 * Registered in OpExecutorRegistry and called by TaskExecutionSystem.
 *
 * @param <T> the AtomicOp variant this executor handles
 */
public interface OpExecutor<T extends AtomicOp> {

    /** Which AtomicOp variant does this executor handle? */
    Class<T> opType();

    /** Execute the operation. Returns DONE or WAITING (retry next tick). */
    OpResult execute(T op, World world, long npcId);
}
