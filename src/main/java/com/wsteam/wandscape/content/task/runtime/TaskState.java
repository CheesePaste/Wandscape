package com.wsteam.wandscape.content.task.runtime;

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

    /** Task finished successfully. */
    COMPLETED
}
