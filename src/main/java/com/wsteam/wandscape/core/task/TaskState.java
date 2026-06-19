package com.wsteam.wandscape.core.task;

/**
 * Lifecycle states for a GlobalTask.
 */
public enum TaskState {
    /** Awaiting player approval (large tasks only). */
    PENDING_APPROVAL,

    /** Ready to be assigned by the scheduler. */
    PENDING_ASSIGN,

    /** Assigned to an NPC, currently being executed. */
    IN_PROGRESS,

    /** Waiting for resources - NPC released, task paused. */
    AWAITING_RESOURCES,

    /** Task was interrupted mid-execution. Enters cooldown period. */
    INTERRUPTED,

    /** Task finished successfully. */
    COMPLETED
}
