package com.wsteam.wandscape.core.op;

/**
 * Synchronous result of executing an AtomicOp.
 */
public enum OpResult {
    /** Operation completed instantly; advance stepIndex. */
    DONE,

    /** Waiting for resources/ritual progress; retry next tick. */
    WAITING,

    /** V2: interrupted (magic not refunded, task fails). Postponed for V1. */
    // INTERRUPTED
}
