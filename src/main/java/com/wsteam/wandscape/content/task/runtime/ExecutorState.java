package com.wsteam.wandscape.content.task.runtime;

/**
 * NPC-local execution state, distinct from GlobalTask's lifecycle.
 */
public enum ExecutorState {
    /** No work (private queue empty, no global task). */
    IDLE,

    /** Currently executing - advance stepIndex next tick. */
    ACTIVE,

    /** Current op returned WAITING - pause but don't advance. */
    WAITING
}
